package h.Hchat.hooks.items.payment.grab;

import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.core.WeChatApis;
import h.Hchat.hooks.api.net.WeChatNetworkDispatcher;
import h.Hchat.hooks.api.runtime.WeChatTaskApi;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.hooks.items.payment.core.RedPacketSettings;
import h.Hchat.hooks.items.payment.core.RedPacketState;
import h.Hchat.hooks.items.payment.detect.RedPacketParser;
import h.Hchat.hooks.items.payment.detect.RedPacketReflector;
import h.Hchat.hooks.items.payment.fake.RedPacketFakePacketCompat;
import h.Hchat.utils.KavaReflector;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 静默抢红包链路。
 * 负责构造收红包请求、监听收包回调、构造拆包请求、监听拆包回调。
 */
public class RedPacketSilentHandler {
    private static final int MAX_RECEIVE_RETRY = 2;
    private static final int MAX_OPEN_RETRY = 1;
    private static final long RECEIVE_TIMEOUT_MS = 4500L;
    private static final long OPEN_TIMEOUT_MS = 4500L;

    public interface Logger {
        void log(String message);
    }

    public interface StatsCallback {
        void incrementStats(String nativeUrl);
    }

    public interface NotifyCallback {
        void onReceived(String amount, String talker, String nativeUrl, String sendId, Object jsonObj);
    }

    public interface FailureCallback {
        void onFailed(String talker, String nativeUrl, String sendId, String reason);
    }

    private final DexFinder dexFinder;
    private final RedPacketSettings settings;
    private final RedPacketState state;
    private final WeChatNetworkDispatcher networkDispatcher;
    private final StatsCallback statsCallback;
    private final NotifyCallback notifyCallback;
    private final FailureCallback failureCallback;
    private final Logger logger;

    private boolean receiveHooked = false;
    private boolean openHooked = false;

    public RedPacketSilentHandler(
            DexFinder dexFinder,
            RedPacketSettings settings,
            RedPacketState state,
            WeChatNetworkDispatcher networkDispatcher,
            StatsCallback statsCallback,
            NotifyCallback notifyCallback,
            FailureCallback failureCallback,
            Logger logger
    ) {
        this.dexFinder = dexFinder;
        this.settings = settings;
        this.state = state;
        this.networkDispatcher = networkDispatcher;
        this.statsCallback = statsCallback;
        this.notifyCallback = notifyCallback;
        this.failureCallback = failureCallback;
        this.logger = logger;
    }

    public void tryReceive(String content, String talker, String nativeUrl) {
        tryReceiveInternal(content, talker, nativeUrl, 0);
    }

    private void tryReceiveInternal(String content, String talker, String nativeUrl, int attempt) {
        log("trySilentReceive 开始, mode=" + settings.getInt(RedPacketSettings.KEY_GRAB_MODE, RedPacketSettings.DEFAULT_GRAB_MODE)
                + " recvClass=" + (dexFinder.receiveLuckyMoneyClass != null)
                + " openClass=" + (dexFinder.openLuckyMoneyClass != null)
                + " dispatcher=" + networkDispatcher.hasDispatcherInstance()
                + " method=" + networkDispatcher.hasDispatcherMethod());

        if (!settings.isSilentGrabEnabled()) {
            log("  放弃: silentGrabEnabled=false");
            return;
        }
        if (dexFinder.receiveLuckyMoneyClass == null && dexFinder.receiveLuckyMoneyUnionClass == null) {
            log("  放弃: receiveLuckyMoneyClass=null union=null");
            return;
        }
        if (TextUtils.isEmpty(nativeUrl)) {
            log("  放弃: nu=empty");
            return;
        }

        try {
            String sendId = RedPacketParser.getNativeUrlParam(nativeUrl, "sendid");
            log("  sendid=" + sendId);
            if (TextUtils.isEmpty(sendId)) return;

            if (state.silentFinishedSet.contains(sendId)
                    || state.silentReceivingSet.contains(sendId)
                    || state.silentOpeningSet.contains(sendId)) {
                log("  放弃: sendid 已处理中");
                return;
            }
            if (!state.silentReceivingSet.add(sendId)) {
                log("  放弃: sendid add竞争失败");
                return;
            }
            state.silentReceiveRetryMap.put(sendId, attempt);

            int msgType = RedPacketParser.safeParseInt(RedPacketParser.getNativeUrlParam(nativeUrl, "msgtype"), 1);
            int channelId = RedPacketParser.safeParseInt(RedPacketParser.getNativeUrlParam(nativeUrl, "channelid"), 1);
            String headImg = RedPacketParser.getXmlParamByTag(content, "headimgurl");
            String nickName = RedPacketParser.getXmlParamByTag(content, "sendertitle");
            String requestNativeUrl = normalizeFakePacketNativeUrl(nativeUrl, talker);
            boolean useUnion = RedPacketParser.getLuckyMoneySceneId(content, talker, nativeUrl) == 1005
                    && dexFinder.receiveLuckyMoneyUnionClass != null;

            Map<String, Object> info = new HashMap<>();
            info.put("sendid", sendId);
            info.put("content", content != null ? content : "");
            info.put("nativeurl", nativeUrl);
            info.put("requestNativeUrl", requestNativeUrl);
            info.put("talker", talker);
            info.put("msgtype", msgType);
            info.put("channelid", channelId);
            info.put("headimg", headImg != null ? headImg : "");
            info.put("nickname", nickName != null ? nickName : "");
            info.put("isUnion", useUnion);
            state.silentRedPacketMap.put(sendId, info);

            log("  构造请求: mt=" + msgType + " ci=" + channelId
                    + " union=" + useUnion + " ctor=" + (dexFinder.receiveCtor != null));

            int sentCount = 0;
            if (useUnion) {
                Object[] args = {msgType, channelId, sendId, requestNativeUrl, 1, "v1.0"};
                Object request = newReceiveRequest(dexFinder.receiveLuckyMoneyUnionClass,
                        dexFinder.unionReceiveCtor, args);
                if (request != null && sendReceiveRequest(request, info, talker)) sentCount++;
            } else {
                for (String requestTalker : buildGroupNameCandidates(talker, nativeUrl)) {
                    Object[] args = {msgType, channelId, sendId, requestNativeUrl, 1, "v1.0", requestTalker};
                    Object request = newReceiveRequest(dexFinder.receiveLuckyMoneyClass,
                            dexFinder.receiveCtor, args);
                    if (request != null && sendReceiveRequest(request, info, requestTalker)) sentCount++;
                }
            }

            if (sentCount <= 0) {
                if (!retryReceiveLater(sendId, "无法创建或发送请求对象")) {
                    cleanup(sendId);
                    log("  放弃: 无法创建或发送请求对象");
                }
                return;
            }

            log("静默收包: " + sendId + " count=" + sentCount + (useUnion ? " [Union]" : ""));
            scheduleReceiveTimeout(sendId);
        } catch (Throwable e) {
            log("ERROR trySilentReceive: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Object newReceiveRequest(Class<?> clazz, java.lang.reflect.Constructor<?> ctor, Object[] args) {
        Object request = null;
        if (ctor != null) {
            try {
                request = KavaReflector.newInstance(ctor, args);
            } catch (Throwable e) {
                log("  ctor.newInstance 失败: " + e.getMessage());
            }
        }
        if (request == null) request = RedPacketReflector.newInstanceByArgs(clazz, args);
        if (request != null) log("  请求对象已创建: " + request.getClass().getName());
        return request;
    }

    private boolean sendReceiveRequest(Object request, Map<String, Object> info, String requestTalker) {
        Map<String, Object> requestInfo = new HashMap<>(info);
        requestInfo.put("requestTalker", requestTalker);
        state.silentReceiveRequestInfoMap.put(request, requestInfo);
        if (networkDispatcher.send(request)) return true;
        log("  sendNetworkRequest 失败!");
        state.silentReceiveRequestInfoMap.remove(request);
        return false;
    }

    private List<String> buildGroupNameCandidates(String talker, String nativeUrl) {
        List<String> result = new ArrayList<>();
        if (!TextUtils.isEmpty(talker)) result.add(talker);
        if (!settings.getBoolean(RedPacketSettings.KEY_FAKE_PACKET_RECEIVE_ENABLE, false)
                || !isNormalChatroomUsername(talker)) {
            return result;
        }
        String duplicated = RedPacketFakePacketCompat.duplicateAt(talker);
        RedPacketFakePacketCompat.rememberGroupFix(talker, duplicated);
        addCandidate(result, duplicated);
        String doubledSuffix = talker + "@chatroom";
        RedPacketFakePacketCompat.rememberGroupFix(talker, doubledSuffix);
        addCandidate(result, doubledSuffix);
        String sender = RedPacketParser.getNativeUrlParam(nativeUrl, "sendusername");
        if (!TextUtils.isEmpty(sender)) {
            String senderSuffix = talker + sender + "@chatroom";
            RedPacketFakePacketCompat.rememberGroupFix(talker, senderSuffix);
            addCandidate(result, senderSuffix);
        }
        return result;
    }

    private boolean isNormalChatroomUsername(String value) {
        return RedPacketFakePacketCompat.isNormalChatroomUsername(value);
    }

    private void addCandidate(List<String> list, String value) {
        if (!TextUtils.isEmpty(value) && !list.contains(value)) list.add(value);
    }

    private String normalizeFakePacketNativeUrl(String nativeUrl, String talker) {
        if (!settings.getBoolean(RedPacketSettings.KEY_FAKE_PACKET_RECEIVE_ENABLE, false)) return nativeUrl;
        return RedPacketFakePacketCompat.normalizeNativeUrl(nativeUrl, talker);
    }

    public void hookReceiveCallback() {
        if (receiveHooked) return;
        boolean hooked = false;
        hooked = hookOneReceiveCallback(dexFinder.receiveLuckyMoneyClass, "normal") || hooked;
        hooked = hookOneReceiveCallback(dexFinder.receiveLuckyMoneyUnionClass, "union") || hooked;
        receiveHooked = hooked;
    }

    private boolean hookOneReceiveCallback(Class<?> receiveClass, String label) {
        if (receiveClass == null) return false;
        try {
            java.lang.reflect.Method onGYNetEnd = null;
            for (java.lang.reflect.Method method : KavaReflector.declaredMethods(receiveClass)) {
                if ("onGYNetEnd".equals(method.getName()) && method.getParameterTypes().length == 3) {
                    onGYNetEnd = method;
                    break;
                }
            }
            if (onGYNetEnd == null) return false;

            HookRegistry.get().hook(onGYNetEnd, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!settings.isSilentGrabEnabled()) return;
                    try {
                        Object jsonObj = param.args[2];
                        if (jsonObj == null) return;

                        String sendId = RedPacketReflector.readJsonString(jsonObj, "sendId");
                        String timingIdentifier = RedPacketReflector.readJsonString(jsonObj, "timingIdentifier");
                        log("收红包响应: sendid=" + sendId + " timingId=" + timingIdentifier);
                        if (TextUtils.isEmpty(sendId) || TextUtils.isEmpty(timingIdentifier)) return;

                        Map<String, Object> reqInfo = null;
                        try {
                            reqInfo = state.silentReceiveRequestInfoMap.remove(param.thisObject);
                        } catch (Throwable ignored) {}
                        Map<String, Object> info = reqInfo != null ? reqInfo : state.silentRedPacketMap.get(sendId);
                        if (info == null || !state.silentReceivingSet.contains(sendId)) return;
                        if (!state.silentOpeningSet.add(sendId)) return;
                        state.silentReceivingSet.remove(sendId);
                        cancelTask(receiveTimeoutKey(sendId));

                        String requestNativeUrl = (String) info.get("requestNativeUrl");
                        if (TextUtils.isEmpty(requestNativeUrl)) requestNativeUrl = (String) info.get("nativeurl");
                        String requestTalker = (String) info.get("requestTalker");
                        if (TextUtils.isEmpty(requestTalker)) requestTalker = (String) info.get("talker");
                        boolean useUnionOpen = Boolean.TRUE.equals(info.get("isUnion"))
                                && dexFinder.openLuckyMoneyUnionClass != null;

                        int msgType = info.get("msgtype") instanceof Integer ? (int) info.get("msgtype") : 1;
                        int channelId = info.get("channelid") instanceof Integer ? (int) info.get("channelid") : 1;
                        String headImg = info.get("headimg") != null ? String.valueOf(info.get("headimg")) : "";
                        String nickName = info.get("nickname") != null ? String.valueOf(info.get("nickname")) : "";

                        Object openRequest = null;
                        if (useUnionOpen && dexFinder.unionOpenCtor10 != null) {
                            try {
                                openRequest = KavaReflector.newInstance(dexFinder.unionOpenCtor10,
                                        msgType, channelId, sendId, requestNativeUrl,
                                        headImg, nickName, requestTalker, "v1.0", timingIdentifier, "");
                            } catch (Throwable ignored) {}
                        }
                        if (openRequest == null && useUnionOpen && dexFinder.unionOpenCtor9 != null) {
                            try {
                                openRequest = KavaReflector.newInstance(dexFinder.unionOpenCtor9,
                                        msgType, channelId, sendId, requestNativeUrl,
                                        headImg, nickName, requestTalker, "v1.0", timingIdentifier);
                            } catch (Throwable ignored) {}
                        }
                        if (openRequest == null && useUnionOpen) {
                            Object[] openArgs = {msgType, channelId, sendId, requestNativeUrl,
                                    headImg, nickName, requestTalker, "v1.0", timingIdentifier, ""};
                            openRequest = RedPacketReflector.newInstanceByArgs(
                                    dexFinder.openLuckyMoneyUnionClass, openArgs);
                        }
                        if (openRequest == null && dexFinder.openCtor10 != null) {
                            try {
                                openRequest = KavaReflector.newInstance(dexFinder.openCtor10,
                                        msgType, channelId, sendId, requestNativeUrl,
                                        headImg, nickName, requestTalker, "v1.0", timingIdentifier, "");
                            } catch (Throwable ignored) {}
                        }
                        if (openRequest == null && dexFinder.openCtor8 != null) {
                            try {
                                openRequest = KavaReflector.newInstance(dexFinder.openCtor8,
                                        msgType, channelId, sendId, requestNativeUrl,
                                        headImg, nickName, requestTalker, timingIdentifier);
                            } catch (Throwable ignored) {}
                        }
                        if (openRequest == null && dexFinder.openCtor9 != null) {
                            try {
                                openRequest = KavaReflector.newInstance(dexFinder.openCtor9,
                                        msgType, channelId, sendId, requestNativeUrl,
                                        headImg, nickName, requestTalker, "v1.0", timingIdentifier);
                            } catch (Throwable ignored) {}
                        }
                        if (openRequest == null) {
                            Object[] openArgs = {msgType, channelId, sendId, requestNativeUrl,
                                    headImg, nickName, requestTalker, "v1.0", timingIdentifier, ""};
                            openRequest = RedPacketReflector.newInstanceByArgs(dexFinder.openLuckyMoneyClass, openArgs);
                        }

                        if (openRequest == null) {
                            log("拆红包请求构造失败");
                            notifyFailure(info, sendId, "拆红包请求构造失败");
                            cleanup(sendId);
                            return;
                        }

                        info.put("openReq", openRequest);
                        if (networkDispatcher.send(openRequest)) {
                            log("拆红包请求已发送: " + sendId);
                            scheduleOpenTimeout(sendId);
                        } else {
                            log("拆红包发包失败: " + sendId);
                            if (!retryOpenLater(info, sendId, "拆红包发包失败")) {
                                notifyFailure(info, sendId, "拆红包发包失败");
                                cleanup(sendId);
                            }
                        }
                    } catch (Throwable e) {
                        log("ERROR receiveCallback: " + e.getMessage());
                    }
                }
            });
            log("Hook收红包回调成功: " + label + " -> " + receiveClass.getName());
            return true;
        } catch (Throwable e) {
            log("Hook收红包回调失败(" + label + "): " + e.getMessage());
        }
        return false;
    }

    public void hookOpenCallback() {
        if (openHooked) return;
        boolean hooked = false;
        hooked = hookOneOpenCallback(dexFinder.openLuckyMoneyClass, "normal") || hooked;
        hooked = hookOneOpenCallback(dexFinder.openLuckyMoneyUnionClass, "union") || hooked;
        openHooked = hooked;
    }

    private boolean hookOneOpenCallback(Class<?> openClass, String label) {
        if (openClass == null) return false;
        try {
            java.lang.reflect.Method onGYNetEnd = null;
            for (java.lang.reflect.Method method : KavaReflector.declaredMethods(openClass)) {
                if ("onGYNetEnd".equals(method.getName()) && method.getParameterTypes().length == 3) {
                    onGYNetEnd = method;
                    break;
                }
            }
            if (onGYNetEnd == null) return false;

            HookRegistry.get().hook(onGYNetEnd, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!settings.isSilentGrabEnabled()) return;
                    try {
                        Object jsonObj = (param.args != null && param.args.length > 2) ? param.args[2] : null;
                        String sendId = null;
                        if (jsonObj != null) {
                            sendId = RedPacketReflector.readJsonString(jsonObj, "sendId");
                            if (TextUtils.isEmpty(sendId)) {
                                sendId = RedPacketReflector.readJsonString(jsonObj, "sendid");
                            }
                        }
                        if (TextUtils.isEmpty(sendId) && state.silentOpeningSet.size() == 1) {
                            try {
                                sendId = state.silentOpeningSet.iterator().next();
                            } catch (Throwable ignored) {}
                        }
                        if (TextUtils.isEmpty(sendId) || !state.silentOpeningSet.contains(sendId)) return;
                        cancelTask(openTimeoutKey(sendId));

                        int errCode = 0;
                        try {
                            if (param.args != null && param.args.length > 0 && param.args[0] instanceof Number) {
                                errCode = ((Number) param.args[0]).intValue();
                            }
                        } catch (Throwable ignored) {}

                        String amount = RedPacketReflector.extractReceivedAmount(jsonObj, errCode);
                        Map<String, Object> info = state.silentRedPacketMap.get(sendId);
                        if (!RedPacketReflector.isPositiveAmount(amount)) {
                            log("拆红包完成但未取到本人实收金额: sendid=" + sendId
                                    + " json=" + String.valueOf(jsonObj));
                            notifyFailure(info, sendId, "未抢到本人实收金额");
                            cleanup(sendId);
                            return;
                        }

                        String talker = null;
                        if (info != null) talker = (String) info.get("talker");

                        state.silentOpeningSet.remove(sendId);
                        state.silentFinishedSet.add(sendId);

                        log("拆红包完成: sendid=" + sendId + " amount=" + amount + " talker=" + talker);

                        String nativeUrl = info != null ? (String) info.get("nativeurl") : null;
                        if (!TextUtils.isEmpty(nativeUrl) && statsCallback != null) {
                            statsCallback.incrementStats(nativeUrl);
                        }

                        if (notifyCallback != null) {
                            notifyCallback.onReceived(
                                    amount,
                                    talker != null ? talker : "",
                                    nativeUrl,
                                    sendId,
                                    jsonObj);
                        }
                    } catch (Throwable e) {
                        log("ERROR openCallback: " + e.getMessage());
                    }
                }
            });
            log("Hook拆红包回调成功: " + label + " -> " + openClass.getName());
            return true;
        } catch (Throwable e) {
            log("Hook拆红包回调失败(" + label + "): " + e.getMessage());
        }
        return false;
    }

    private void cleanup(String sendId) {
        cancelTask(receiveTimeoutKey(sendId));
        cancelTask(openTimeoutKey(sendId));
        state.cleanupSilentPacket(sendId);
    }

    private void notifyFailure(Map<String, Object> info, String sendId, String reason) {
        if (failureCallback == null) return;
        String talker = info != null ? (String) info.get("talker") : "";
        String nativeUrl = info != null ? (String) info.get("nativeurl") : "";
        failureCallback.onFailed(talker, nativeUrl, sendId, reason);
    }

    private void scheduleReceiveTimeout(String sendId) {
        runDelayed(receiveTimeoutKey(sendId), RECEIVE_TIMEOUT_MS, () -> {
            if (TextUtils.isEmpty(sendId)
                    || state.silentFinishedSet.contains(sendId)
                    || state.silentOpeningSet.contains(sendId)
                    || !state.silentReceivingSet.contains(sendId)) {
                return;
            }
            if (!retryReceiveLater(sendId, "收红包响应超时")) {
                Map<String, Object> info = state.silentRedPacketMap.get(sendId);
                notifyFailure(info, sendId, "网络超时未收到收红包响应");
                cleanup(sendId);
            }
        });
    }

    private boolean retryReceiveLater(String sendId, String reason) {
        Map<String, Object> info = state.silentRedPacketMap.get(sendId);
        if (info == null) return false;
        int attempt = state.silentReceiveRetryMap.get(sendId) != null
                ? state.silentReceiveRetryMap.get(sendId) : 0;
        if (attempt >= MAX_RECEIVE_RETRY) return false;
        String content = String.valueOf(info.get("content"));
        String talker = (String) info.get("talker");
        String nativeUrl = (String) info.get("nativeurl");
        if (TextUtils.isEmpty(nativeUrl)) return false;
        state.silentReceivingSet.remove(sendId);
        state.silentOpeningSet.remove(sendId);
        int nextAttempt = attempt + 1;
        log("静默收包重试: sendid=" + sendId + " attempt=" + nextAttempt + " reason=" + reason);
        runDelayed(receiveRetryKey(sendId), 900L * nextAttempt,
                () -> tryReceiveInternal(content, talker, nativeUrl, nextAttempt));
        return true;
    }

    private void scheduleOpenTimeout(String sendId) {
        runDelayed(openTimeoutKey(sendId), OPEN_TIMEOUT_MS, () -> {
            if (TextUtils.isEmpty(sendId)
                    || state.silentFinishedSet.contains(sendId)
                    || !state.silentOpeningSet.contains(sendId)) {
                return;
            }
            Map<String, Object> info = state.silentRedPacketMap.get(sendId);
            if (!retryOpenLater(info, sendId, "拆红包响应超时")) {
                notifyFailure(info, sendId, "网络超时未收到拆红包响应");
                cleanup(sendId);
            }
        });
    }

    private boolean retryOpenLater(Map<String, Object> info, String sendId, String reason) {
        if (info == null || TextUtils.isEmpty(sendId)) return false;
        int attempt = state.silentOpenRetryMap.get(sendId) != null
                ? state.silentOpenRetryMap.get(sendId) : 0;
        if (attempt >= MAX_OPEN_RETRY) return false;
        Object openRequest = info.get("openReq");
        if (openRequest == null) return false;
        int nextAttempt = attempt + 1;
        state.silentOpenRetryMap.put(sendId, nextAttempt);
        log("静默拆包重试: sendid=" + sendId + " attempt=" + nextAttempt + " reason=" + reason);
        runDelayed(openRetryKey(sendId), 1200L * nextAttempt, () -> {
            if (!state.silentOpeningSet.contains(sendId) || state.silentFinishedSet.contains(sendId)) return;
            if (networkDispatcher.send(openRequest)) {
                scheduleOpenTimeout(sendId);
            } else if (!retryOpenLater(info, sendId, "拆红包重试发包失败")) {
                notifyFailure(info, sendId, "拆红包重试发包失败");
                cleanup(sendId);
            }
        });
        return true;
    }

    private void runDelayed(String key, long delayMs, Runnable runnable) {
        try {
            WeChatTaskApi tasks = WeChatApis.runtime().tasks();
            if (tasks != null && tasks.isAvailable()) {
                tasks.runOnMainDelayed(key, delayMs, runnable);
                return;
            }
        } catch (Throwable ignored) {}
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(runnable, delayMs);
    }

    private void cancelTask(String key) {
        try {
            WeChatTaskApi tasks = WeChatApis.runtime().tasks();
            if (tasks != null && tasks.isAvailable()) tasks.cancel(key);
        } catch (Throwable ignored) {}
    }

    private String receiveTimeoutKey(String sendId) {
        return "redpacket_receive_timeout:" + sendId;
    }

    private String receiveRetryKey(String sendId) {
        return "redpacket_receive_retry:" + sendId;
    }

    private String openTimeoutKey(String sendId) {
        return "redpacket_open_timeout:" + sendId;
    }

    private String openRetryKey(String sendId) {
        return "redpacket_open_retry:" + sendId;
    }

    private void log(String message) {
        if (logger != null) logger.log(message);
    }
}
