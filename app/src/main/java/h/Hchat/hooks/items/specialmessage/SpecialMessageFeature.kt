package h.Hchat.hooks.items.specialmessage

import h.Hchat.hooks.core.*
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.items.script.ScriptSendButtonHook
import h.Hchat.event.Events
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.utils.KavaReflector
import java.util.ArrayDeque

/** 迁移特殊消息插件的 sec_msg_node，使用原生消息发送链路。 */
class SpecialMessageFeature : BaseFeature() {
    private var hostProfile: SafetyMessageHostProfile? = null
    private var hostVersion = ""
    private val hooks = mutableListOf<XC_MethodHook.Unhook>()
    @Volatile private var textInstalled = false
    private var emojiInstalled = false
    private var emojiDispatchInstalled = false
    private var sendSubscription: ScriptSendButtonHook.Subscription? = null
    private val pending = ArrayDeque<PendingSafety>()
    override fun featureId() = ID
    override fun name() = "特殊消息"
    // 开关保存在 HchatStorage 中，并且需要运行时即时读取；功能本身必须始终安装监听。
    override fun isEnabled(context: FeatureContext): Boolean = true
    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(object : SimpleFeatureSettingsProvider(ID, name(), "安全消息", FeatureSettingsProvider.CATEGORY_PRACTICAL) {})
    }
    override fun onFeatureInstall(context: FeatureContext) {
        // 仅启用已用宿主 APK 交叉确认的版本，避免混淆类碰撞。
        val host = context.hostContext()
        val version = host.packageManager.getPackageInfo(host.packageName, 0)
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) version.longVersionCode else version.versionCode.toLong()
        hostVersion = "${version.versionName} ($versionCode)"
        hostProfile = SafetyMessageHostProfile.forVersion(version.versionName, versionCode)
        if (hostProfile == null) {
            logError("安全消息未适配微信 $hostVersion，未安装 Hook", null)
            return
        }
        val sp = HchatStorage.preferences(host, PREFS)
        sendSubscription = ScriptSendButtonHook.registerHandler(ID) { value ->
            if (!textInstalled) return@registerHandler false
            if (!sp.getBoolean("enabled", false) && !sp.getBoolean("link", false)) return@registerHandler false
            if (!isAllowedTalker(sp, WeChatApis.chatPage()?.currentTalker().orEmpty())) return@registerHandler false
            val text = value
            val talker = WeChatApis.chatPage()?.currentTalker().orEmpty().trim()
            if (text.isBlank() || talker.isEmpty()) return@registerHandler false
            // 按正文分类后只读取对应开关，链接开关不能接管普通文本。
            val link = containsWebLink(text)
            if (!sp.getBoolean(if (link) "link" else "enabled", false)) return@registerHandler false
            synchronized(pending) {
                val now = android.os.SystemClock.elapsedRealtime()
                pending.removeAll { it.expireAt <= now }
                if (pending.size >= 64) return@registerHandler false
                pending.addLast(PendingSafety(text, talker, now + 30_000L, link))
            }
            val sent = try { WeChatApis.messages()?.sendText(talker, text) == true }
            catch (error: Throwable) { logError("安全消息发送失败", error); false }
            if (!sent) {
                synchronized(pending) {
                    if (pending.peekLast()?.text == text && pending.peekLast()?.talker == talker) {
                        pending.removeLast()
                    }
                }
            }
            sent
        }
        DexInstallScheduler.schedule("shared:send_button", "聊天发送按钮", stage = DexInstallScheduler.Stage.WARMUP) {
            ScriptSendButtonHook.install(context)
        }
        schedule(context)
        subscribe(Events.DexReady::class.java) {
            ScriptSendButtonHook.install(context)
            schedule(context)
        }
    }
    private fun schedule(context: FeatureContext) {
        DexInstallScheduler.schedule(ID, name()) { installHooks(context) }
    }
    @Synchronized private fun installHooks(context: FeatureContext): Boolean {
        val profile = hostProfile ?: return false
        val sp = HchatStorage.preferences(context.hostContext(), PREFS)
        if (!textInstalled) {
            // 只解析版本配置指定的入口，不遍历可能碰撞的混淆类候选。
            val targets = runCatching {
                val loader = context.hostClassLoader()
                val messageClass = HostReflection.findClass("com.tencent.mm.storage.e9", loader)
                check(HostReflection.method(messageClass, profile.talkerGetter).returnType == String::class.java)
                check(HostReflection.method(messageClass, "j").returnType == String::class.java)
                check(HostReflection.method(messageClass, "s3", String::class.java).returnType == Void.TYPE)
                check(HostReflection.findField(messageClass, "G").type == String::class.java)
                listOf(HostReflection.method(HostReflection.findClass(profile.textOwner, loader),
                    profile.textMethod,
                    HostReflection.findClass(profile.textRequest, loader),
                    messageClass
                )).filter { it.returnType == Void.TYPE }
            }.getOrElse {
                logError("微信 $hostVersion 定位 ${profile.textOwner}.${profile.textMethod} 失败", it)
                emptyList()
            }
            if (targets.isNotEmpty()) {
                targets.forEach { target ->
                    hooks += de.robv.android.xposed.XposedBridge.hookMethod(target, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!sp.getBoolean("enabled", false) && !sp.getBoolean("link", false)) return
                            val message = param.args?.getOrNull(1) ?: return
                            val now = android.os.SystemClock.elapsedRealtime()
                            val candidate = synchronized(pending) {
                                while (pending.peekFirst()?.expireAt?.let { it <= now } == true) pending.removeFirst()
                                pending.firstOrNull()
                            } ?: return
                            try {
                                // 脚本桥的第三个参数 0 是参数个数，不是传给微信方法的实参。
                                // 按正文和会话精确匹配；同一消息的重复发送按登记顺序消费。
                                val content = HostReflection.callMethod(message, "j")?.toString().orEmpty()
                                val talker = HostReflection.callMethod(message, profile.talkerGetter)?.toString().orEmpty()
                                if (content != candidate.text || talker != candidate.talker || now >= candidate.expireAt) return
                                if (!isAllowedTalker(sp, talker) ||
                                    !sp.getBoolean(if (candidate.link) "link" else "enabled", false)) return
                                val source = HostReflection.getObjectField(message, "G") as? String ?: ""
                                val updated = appendNode(source, candidate.link)
                                HostReflection.callMethod(message, "s3", updated)
                                check(HostReflection.getObjectField(message, "G") == updated) {
                                    "安全节点写回校验失败"
                                }
                                synchronized(pending) {
                                    if (pending.peekFirst() === candidate) pending.removeFirst()
                                }
                            } catch (error: Throwable) { logError("微信 $hostVersion 安全消息节点注入失败: $target", error) }
                        }
                    })
                }
                textInstalled = true
            }
        }
        // 图片模式保留配置占位，未验证的图片注入不安装。
        if (!emojiInstalled) emojiInstalled = installMediaHook(context)
        if (!emojiDispatchInstalled) emojiDispatchInstalled = installEmojiDispatchHook(context)
        return textInstalled && emojiInstalled && emojiDispatchInstalled
    }
    private fun installMediaHook(context: FeatureContext): Boolean = runCatching {
        val profile = checkNotNull(hostProfile)
        val loader = context.hostClassLoader()
        val target = HostReflection.method(HostReflection.findClass(profile.sourceOwner, loader),
            "a", HostReflection.findClass("com.tencent.mm.storage.e9", loader)
        )
        check(target.returnType == String::class.java)
        val sp = HchatStorage.preferences(context.hostContext(), PREFS)
        hooks += de.robv.android.xposed.XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable() ||
                    !sp.getBoolean("emoji", false)) return
                val message = param.args.getOrNull(0) ?: return
                try {
                    if ((HostReflection.callMethod(message, "getType") as? Number)?.toInt() !=
                        47) return
                    val talker = HostReflection.callMethod(message, profile.talkerGetter)?.toString().orEmpty()
                    if (!isAllowedTalker(sp, talker)) return
                    // 表情直接消费本方法返回的 MsgSource。
                    val source = param.result as? String ?: ""
                    val updated = appendNode(source, false)
                    param.result = updated
                } catch (error: Throwable) { logError("微信 $hostVersion 表情安全节点注入失败: $target", error) }
            }
        })
        true
    }.getOrElse {
        logError("微信 $hostVersion 表情安全链路未就绪", it)
        false
    }

    /** 在表情请求进入网络分发前再写一次 MsgSource，覆盖后续上传阶段重建请求的情况。 */
    private fun installEmojiDispatchHook(context: FeatureContext): Boolean = runCatching {
        val profile = checkNotNull(hostProfile)
        val loader = context.hostClassLoader()
        val scene = HostReflection.findClass(profile.emojiScene, loader)
        val dispatch = HostReflection.method(scene, "doScene",
            HostReflection.findClass("com.tencent.mm.network.s", loader),
            HostReflection.findClass("com.tencent.mm.modelbase.u0", loader))
        check(dispatch.returnType == Int::class.javaPrimitiveType)
        check(HostReflection.findField(scene, "m").type == String::class.java)
        val envelope = HostReflection.findField(scene, "d").type
        val wrapper = HostReflection.findField(envelope, "a").type
        HostReflection.findField(wrapper, "a")
        val sp = HchatStorage.preferences(context.hostContext(), PREFS)
        hooks += de.robv.android.xposed.XposedBridge.hookMethod(dispatch, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!sp.getBoolean("emoji", false)) return
                try {
                    val request = HostReflection.getObjectField(param.thisObject, "d") ?: return
                    val list = HostReflection.getObjectField(HostReflection.getObjectField(request, "a"), "a") ?: return
                    val items = HostReflection.getObjectField(list, "e") as? java.util.LinkedList<*> ?: return
                    val item = items.firstOrNull() ?: return
                    // 此 Hook 的目标类本身是微信的 sendemoji 请求（cmdId=175）。
                    val talker = HostReflection.getObjectField(param.thisObject, "m")?.toString().orEmpty()
                    if (!isAllowedTalker(sp, talker)) return
                    val field = HostReflection.findField(item.javaClass, "p")
                    field.isAccessible = true
                    field.set(item, appendNode(field.get(item)?.toString().orEmpty(), false))
                } catch (error: Throwable) { logError("微信 $hostVersion 表情请求安全节点注入失败: $dispatch", error) }
            }
        })
        true
    }.getOrElse { logError("微信 $hostVersion 表情请求分发链路未就绪", it); false }

    override fun onFeatureDestroy(context: FeatureContext) {
        sendSubscription?.unsubscribe()
        sendSubscription = null
        synchronized(pending) { pending.clear() }
        hooks.forEach { it.unhook() }
        hooks.clear()
        textInstalled = false
        emojiInstalled = false
        emojiDispatchInstalled = false
        hostProfile = null
    }
    private data class PendingSafety(
        val text: String,
        val talker: String,
        val expireAt: Long,
        val link: Boolean
    )

    companion object {
        internal fun containsWebLink(text: String): Boolean {
            val matcher = android.util.Patterns.WEB_URL.matcher(text)
            while (matcher.find()) {
                // 邮箱中的域名不是独立网页链接。
                if (matcher.start() == 0 || text[matcher.start() - 1] != '@') return true
            }
            return false
        }

        private val safetyNodePattern = Regex("<sec_msg_node\\b[^>]*>.*?</sec_msg_node\\s*>|<sec_msg_node\\b[^>]*/>", RegexOption.DOT_MATCHES_ALL)
        private val policyFieldPatterns = listOf("sfn", "show-h5", "clip-len", "share-tip-url", "sec-ctrl-flag",
            "fold-reduce", "media-to-emoji", "block-range", "bubble-type", "preview-type", "url-click-type").map { field ->
            Regex("<$field\\b[^>]*>.*?</$field\\s*>|<$field\\b[^>]*/>", RegexOption.DOT_MATCHES_ALL)
        }

        private fun isAllowedTalker(sp: android.content.SharedPreferences, talker: String): Boolean {
            val value = talker.trim()
            if (value.isEmpty()) return false
            val list = sp.getString("talkers", "").orEmpty().split("|").map { it.trim() }.filter { it.isNotEmpty() }
            return list.any { it == value }
        }

        const val ID = "special_message"
        const val PREFS = "hchat_special_message"
        internal fun appendNode(source: String, link: Boolean): String {
            val flag = if (link) "1" else ""
            val preview = if (link) 1 else 0
            val node = "<sec_msg_node><sfn>1</sfn><show-h5><![CDATA[]]></show-h5>" +
                "<clip-len>0</clip-len><share-tip-url><![CDATA[]]></share-tip-url>" +
                "<sec-ctrl-flag><![CDATA[$flag]]></sec-ctrl-flag><fold-reduce>0</fold-reduce>" +
                "<media-to-emoji>0</media-to-emoji><block-range>0</block-range>" +
                "<bubble-type>2</bubble-type><preview-type>$preview</preview-type>" +
                "<url-click-type>$preview</url-click-type></sec_msg_node>"
            if (source.isBlank()) return "<msgsource>$node</msgsource>"
            val existing = safetyNodePattern.find(source)
            if (existing != null) {
                val raw = existing.value
                val opening = raw.substringBefore('>') + ">"
                var preserved = if (opening.endsWith("/>")) "" else raw.substringAfter('>').substringBeforeLast("</sec_msg_node")
                // 更新全部策略字段，保留 uuid、未知字段和节点属性；支持换行及自闭合字段。
                policyFieldPatterns.forEach { pattern ->
                    preserved = preserved.replace(pattern, "")
                }
                val policy = node.removePrefix("<sec_msg_node>").removeSuffix("</sec_msg_node>")
                val merged = opening.removeSuffix("/>") .let { if (opening.endsWith("/>")) "$it>" else it } + preserved + policy + "</sec_msg_node>"
                return source.replaceRange(existing.range, merged)
            }
            val close = source.indexOf("</msgsource>")
            return if (close >= 0) source.substring(0, close) + node + source.substring(close)
                else "<msgsource>$source$node</msgsource>"
        }
    }
}

/** 所有成员解析统一经 KavaReflector；失败抛回带阶段的模块日志。 */
private object HostReflection {
    fun findClass(name: String, loader: ClassLoader): Class<*> =
        requireNotNull(KavaReflector.loadClass(name, loader)) { "未找到宿主类 $name" }
    fun method(owner: Class<*>, name: String, vararg types: Class<*>): java.lang.reflect.Method =
        requireNotNull(KavaReflector.findMethodRecursive(owner, name, *types)) { "未找到宿主方法 ${owner.name}.$name" }
    fun findField(owner: Class<*>, name: String): java.lang.reflect.Field =
        requireNotNull(KavaReflector.findFieldRecursive(owner, name)) { "未找到宿主字段 ${owner.name}.$name" }
    fun getObjectField(receiver: Any?, name: String): Any? {
        requireNotNull(receiver) { "读取 $name 时宿主对象为空" }
        return findField(receiver.javaClass, name).get(receiver)
    }
    fun callMethod(receiver: Any, name: String, vararg args: Any?): Any? =
        KavaReflector.invokeOrThrow(
            requireNotNull(KavaReflector.findCompatibleMethod(receiver.javaClass, name, *args)) { "未找到宿主方法 ${receiver.javaClass.name}.$name" },
            receiver, *args
        )
}
