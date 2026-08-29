# Hchat 插件接口文档

::: warning 提示
本文档是 Hchat 脚本插件接口的单文件简洁版，格式按 WA 文档风格整理。Hchat 尽量兼容 WA 常用写法，少量接口会比 WA 多返回 `boolean`，脚本不接返回值也可以直接调用。

插件 Agent 对本文档和当前运行时没有明确确认的能力、可用性或限制，必须说明未知或需要运行时验证，不得猜测。
:::

## 快速开始

### 你先知道这几件事

- 插件主文件固定为 `main.java`。
- 常用入口是 `onLoad()`、`onHandleMsg(Object msgInfoBean)`、`onClickSendBtn(String text)`、`onLongClickSendBtn(String text)`。
- 想给当前聊天发消息，通常用 `getTargetTalker()`。
- 临时文件、下载文件、生成图片，通常放在 `cacheDir`。
- 读取插件自带文件，通常从 `pluginDir` 开始。

### 最小插件目录结构

```text
插件名/
├─ info.prop
├─ main.java
└─ config.prop
```

完整路径：

```text
Hchat/脚本插件/插件名/main.java
```

`info.prop` 示例：

```properties
name=测试插件
author=作者
version=1.0.0
updateTime=2026-06-28
process=main
```

`process` 默认为 `main`；Hook 小程序进程时使用 `appbrand`，确需两类进程分别运行时使用 `all`。小程序进程没有联系人/消息数据库、主进程消息回调和 DexKit，四个 DexKit 变量均为 `null`；在 `onLoad()` 中使用当前 `classLoader`、已确认的稳定完整类名、反射及 `hookBefore/hookAfter/hookReplace`。混淆目标必须先由 Agent 逆向工具确认；需要主进程 DexKit 运行时定位时，用 `process=all` 让主进程实例缓存 descriptor，小程序实例只读取缓存，禁止在小程序进程新建 DexKit。

### 最小自动回复示例

```beanshell
void onHandleMsg(Object msgInfoBean) {
    if (msgInfoBean.isSend()) return;
    if (!msgInfoBean.isText()) return;

    String talker = msgInfoBean.getTalker();
    String content = msgInfoBean.getContent();

    if (content.equals("在吗")) {
        sendText(talker, "在");
    }
}
```

### 最小拦截发送示例

```beanshell
boolean onClickSendBtn(String text) {
    if (text.equals("/ping")) {
        sendText(getTargetTalker(), "pong");
        return true;
    }
    return false;
}
```

`onClickSendBtn` 和 `onLongClickSendBtn` 都在微信主线程执行；插件解释器正忙时本次回调会被跳过并放行原始事件。网络、文件和长循环操作必须异步执行。

## 回调方法

这些方法由宿主自动调用，不需要你手动执行。

### 打开插件设置

```beanshell
void openSettings();
```

点击插件设置入口时触发。

### 插件加载

```beanshell
void onLoad();
```

插件被加载时触发，一般用于初始化变量、注册 Hook、读取配置。

### 插件卸载

```beanshell
void onUnload();
```

插件被卸载时触发，一般用于释放资源、取消定时任务、卸载 Hook。

### 监听消息

```beanshell
void onHandleMsg(Object msgInfoBean);
```

- `msgInfoBean`：消息对象，结构见“相关结构”。

### 图片自动下载

```beanshell
void onImageDownload(Object msgInfoBean, String imagePath, String talker, String senderWxid);
```

仅在已启用插件声明该回调时下载图片。消息去重后同一张图片只下载一份到 `Hchat/Cache`，再分发给所有订阅插件；回调只在微信主进程触发。共享文件不要直接删除，需要长期使用时先复制到插件目录。外部方法可用 `useOnImageDownload(String methodName)` 绑定。

### 视频自动下载

```beanshell
void onVideoDownload(Object msgInfoBean, String videoPath, String talker, String senderWxid);
```

仅在已启用插件实际声明该回调时自动下载普通聊天视频。接收视频只有取得长度元数据后才复用本地文件，否则从 `imgPath` 等待原生 `VideoInfo` 并下载；同一消息有独立任务去重。所有订阅插件处理结束后模块自动删除临时视频，需要保留时在回调内复制。该回调不是微信界面任意下载任务的全局监听，也不包含视频号分享。外部方法可用 `useOnVideoDownload(String methodName)` 绑定。

### 视频号媒体自动下载

```beanshell
void onFinderMediaDownload(Object msgInfoBean, String mediaPath, String talker, String senderWxid);
```

`alt-entry` 专属回调。仅在已启用插件声明该回调时，结构化解析收到的视频号分享消息并自动下载。多媒体动态每个文件回调一次；所有订阅插件处理完当前文件后模块会删除临时文件，需要保留时在回调内复制。外部方法可用 `useOnFinderMediaDownload(String methodName)` 绑定。

### 单击发送按钮

```beanshell
boolean onClickSendBtn(String text);
```

- `text`：输入框中的文本
- 返回 `true`：拦截本次发送，并清空输入框
- 返回 `false`：不拦截，继续正常发送

### 长按发送按钮

```beanshell
boolean onLongClickSendBtn(String text);
```

- `text`：输入框中的文本
- 返回 `true`：消费长按、阻止随后单击发送，并清空输入框
- 返回 `false`：继续微信原长按流程；原流程未消费时，松手可能继续触发普通单击
- 模块按系统长按时间和移动阈值直接跟踪真实发送按钮的触摸序列，不依赖发送按钮是否启用 Android 原生 `longClickable`。移动超出阈值、多指或取消手势不会触发回调。

### 监听成员变动

```beanshell
void onMemberChange(String type, String groupWxid, String userWxid, String userName);
```

- `type`：事件类型，常见值为 `join`、`left`
- `groupWxid`：群聊 ID
- `userWxid`：成员 wxid
- `userName`：成员显示名

### 监听好友申请

```beanshell
void onNewFriend(String wxid, String ticket, int scene);
```

- `wxid`：申请人 wxid
- `ticket`：申请票据
- `scene`：来源场景值

### 回调别名

```beanshell
void useCallback(String callbackName, String methodName);
void useOnLoad(String methodName);
void useOnUnload(String methodName);
void useOpenSettings(String methodName);
void useOnClickSendBtn(String methodName);
void useOnLongClickSendBtn(String methodName);
void useOnHandleMsg(String methodName);
void useOnMemberChange(String methodName);
void useOnNewFriend(String methodName);
```

入口通过 `loadJava`、`eval` 或 `evalSnapshot` 加载外部方法时，可以用这些接口绑定标准回调。

## 全局变量

以下变量可在插件脚本中直接使用，无需自行声明。

### 宿主相关

- `hostContext`
  - 微信 `Context`
  - 示例：`log(hostContext.getPackageName());`

- `classLoader`
  - 微信进程 `ClassLoader`

- `hostVerName`
  - 微信版本名

- `hostVerCode`
  - 微信版本号

- `hostVerClient`
  - 微信客户端/热更新标识

- `moduleVer`
  - Hchat 模块版本

### 路径相关

- `cacheDir`
  - Hchat 全局缓存目录
  - 示例：`String imgPath = cacheDir + "/demo.jpg";`

- `cacheDirFile`
  - Hchat 全局缓存目录 `File`

- `pluginDir`
  - 当前插件目录
  - 示例：`String cfg = pluginDir + "/config.prop";`

- `pluginDirFile`
  - 当前插件目录 `File`

- `scriptDir`
  - 脚本插件根目录

- `scriptDirFile`
  - 脚本插件根目录 `File`

### 插件自身信息

- `pluginId`
- `pluginName`
- `pluginAuthor`
- `pluginVersion`
- `pluginUpdateTime`

### 桥接对象

- `wa` / `waBridge`
- `http` / `httpClient`
- `audio` / `audioBridge`
- `bridge` / `apis`
- `dexKit`
- `dexKitBridge`
- `dexFinder`
- `dexBridgeHolder`

## 相关结构

### 消息结构

`onHandleMsg(Object msgInfoBean)` 中的 `msgInfoBean` 可按下列结构使用。

```beanshell
MsgInfoBean {
    long getMsgId();// 本地消息 ID
    long getMsgSvrId();// 服务端消息 ID
    String getMsgType();// 消息类型，字符串形式
    String getType();// 消息类型，字符串形式
    long getCreateTime();// 创建时间戳
    long getCreateTimeSeconds();// 创建时间戳，秒
    String getTalker();// 目标会话 ID，私聊为好友 wxid，群聊为 chatroom id
    String getSendTalker();// 发送者 wxid
    String getSender();// 发送者 wxid
    String getSelfWxId();// 当前登录账号 wxid
    String getContent();// 消息内容
    String getText();// 文本内容，同 getContent()
    String getXml();// XML 内容
    String getMsgSource();// 消息来源
    String getNativeUrl();// nativeUrl
    List<String> getAtUserList();// @ 列表
    String getSource();// 来源标识
    String getKind();// 消息分类

    Object getMessage();// 原始消息对象
    Object getStoredMessage();// 数据库消息对象
    ImageMsg getImageMsg();// 图片消息结构
    VideoMsg getVideoMsg();// 视频消息结构，支持 43/62
    QuoteMsg getQuoteMsg();// 引用消息结构
    PatMsg getPatMsg();// 拍一拍消息结构
    FileMsg getFileMsg();// 文件消息结构
    TransferMsg getTransferMsg();// 转账消息结构

    boolean isPrivateChat();// 是否私聊
    boolean isOpenIM();// 是否企业微信私聊
    boolean isGroupChat();// 是否群聊
    boolean isChatroom();// 是否普通群聊
    boolean isImChatroom();// 是否企业微信群聊
    boolean isOfficialAccount();// 是否公众号
    boolean isSend();// 是否自己发送
    boolean isSelf();// 是否自己发送

    boolean isText();// 是否文本
    boolean isImage();// 是否图片
    boolean isVoice();// 是否语音
    boolean isShareCard();// 是否名片
    boolean isVideo();// 是否视频
    boolean isEmoji();// 是否表情
    boolean isLocation();// 是否位置
    boolean isAppMsg();// 是否应用消息
    boolean isApp();// 是否应用消息
    boolean isVoip();// 是否通话消息
    boolean isVoipVoice();// 是否语音通话
    boolean isVoipVideo();// 是否视频通话
    boolean isSystem();// 是否系统消息
    boolean isRecalled();// 是否撤回消息
    boolean isLink();// 是否链接消息
    boolean isTransfer();// 是否转账消息
    boolean isRedPacket();// 是否红包消息
    boolean isRedBag();// 是否红包消息
    boolean isVideoNumberVideo();// 是否视频号视频
    boolean isNote();// 是否接龙消息
    boolean isQuote();// 是否引用消息
    boolean isPat();// 是否拍一拍
    boolean isFile();// 是否文件消息
    boolean isMusic();// 是否音乐消息
    boolean isAnnounceAll();// 是否公告全体
    boolean isNotifyAll();// 是否 @全体
    boolean isAtMe();// 是否 @我
}
```

### 图片消息结构

```beanshell
ImageMsg {
    String getMd5();// 图片 MD5
    String getBigImgUrl();// 高清图链接
    String getMidImgUrl();// 普通图链接
    String getThumbUrl();// 缩略图链接
    String getCdnUrl();// 缩略图 CDN 链接
    String getKey();// 图片密钥
    String getAesKey();// 图片密钥
    int getBigLength();// 高清图长度
    int getMidLength();// 普通图长度
    int getThumbLength();// 缩略图长度
}
```

### 视频消息结构

```beanshell
VideoMsg {
    String getMd5();// 视频 MD5
    String getNewMd5();// 新版视频 MD5
    String getCdnVideoUrl();// CDN 地址，部分消息可能为空
    String getAesKey();// 解密密钥
    long getLength();// 文件长度
    int getPlayLength();// 播放时长
}
```

### 引用消息结构

```beanshell
QuoteMsg {
    String getTitle();// 引用标题
    String getMsgSource();// 消息来源
    String getSendTalker();// 原消息发送者 wxid；优先按 svrid 回查，表情等 svrid 不完整时按会话、类型、时间和内容回查
    String getSenderId();// 原消息发送者 wxid，旧别名
    String getDisplayName();// 显示昵称
    String getTalker();// 目标会话 ID
    String getTalkerId();// 目标会话 ID，旧别名
    int getType();// 原消息类型
    String getContent();// 原消息内容
    long getSvrId();// 原消息服务端 ID
    String getStrId();// 原消息字符串 ID
    long getCreateTime();// 原消息创建时间
}
```

### 拍一拍消息结构

```beanshell
PatMsg {
    String getTalker();// 目标会话 ID
    String getFromUser();// 发起者 wxid
    String getPattedUser();// 被拍者 wxid
    String getTemplate();// 展示模板
    long getCreateTime();// 创建时间戳
}
```

### 文件消息结构

```beanshell
FileMsg {
    String getTitle();// 文件标题
    String getFileName();// 文件名
    long getSize();// 文件大小，单位字节
    String getExt();// 文件后缀
    String getMd5();// 文件 MD5
    String getUrl();// 文件链接
    String getKey();// 文件密钥
    String getAttachId();// 附件 ID
}
```

### 转账消息结构

```beanshell
TransferMsg {
    String transactionId;// 转账交易 ID
    String transferId;// 转账 ID
    String payerUsername;// 付款方账号

    String getTransactionId();// 转账交易 ID
    String getTransferId();// 转账 ID
    String getTransId();// 转账 ID
    String getPayerUsername();// 付款方账号
    String getPayer();// 付款方账号
    String getReceiver();// 收款方账号
    long getInvalidTime();// 失效时间
    long getFee();// 金额，单位按微信原始字段
    String getDescription();// 描述
    String getRawXml();// 原始 XML
}
```

### 联系人结构

`getFriendList()` 返回元素兼容以下常用方法。

```beanshell
FriendInfo {
    String getWxid();// wxid
    String getName();// 显示名
    String getNickname();// 昵称
    String getRemark();// 备注
    String getRemarkName();// 备注
}
```

### 群聊结构

`getGroupList()` 返回元素兼容以下常用方法。

```beanshell
GroupInfo {
    String getRoomId();// 群 ID
    String getName();// 群名称
    String getNickname();// 群聊昵称
    String getRemark();// 群备注
    String getRemarkName();// 群备注
    String getDisplayName();// 显示名，有备注时为 备注 (群名)
    List<String> getMemberList();// 成员列表
    int getMemberCount();// 成员数量
}
```

### 标签结构

```beanshell
ContactLabelBean {
    String getLabelId();// 标签 ID
    String getId();// 标签 ID
    String getLabelName();// 标签名
    String getName();// 标签名
    List<String> getUserNameList();// 联系人列表
    List<String> getUsernameList();// 联系人列表
    List<String> getContactList();// 联系人列表
}
```

## 配置方法

配置保存在当前插件目录的 `config.prop`。

### 读取配置

```beanshell
String getString(String key, String defValue);
Set getStringSet(String key, Set defValue);
boolean getBoolean(String key, boolean defValue);
int getInt(String key, int defValue);
float getFloat(String key, float defValue);
long getLong(String key, long defValue);
```

### 写入配置

```beanshell
void putString(String key, String value);
void putStringSet(String key, Set value);
void putBoolean(String key, boolean value);
void putInt(String key, int value);
void putFloat(String key, float value);
void putLong(String key, long value);
```

## 联系方法

### 取当前登录 Wxid

```beanshell
String getLoginWxid();
```

### 取当前登录微信号

```beanshell
String getLoginAlias();
```

### 取上下文聊天对象

```beanshell
String getTargetTalker();
```

返回当前目标会话 ID，私聊为对方 wxid，群聊为群 chatroom id。

### 取顶部 Activity

```beanshell
Activity getTopActivity();
```

### 获取公众号列表

```beanshell
Object getOfficialList();
```

### 取好友列表

```beanshell
Object getFriendList();
Object getFriendListInfo();
```

`getFriendListInfo()` 返回 `Map` 列表，常见字段：

```text
wxid, nickname, remarkName, displayName, customWxId,
gender, province, city, region, avatarUrl, avatarBackupUrl, type
```

### 取好友资料

```beanshell
String getFriendNickName(String friendWxid);
String getFriendRemarkName(String friendWxid);
String getFriendName(String friendWxid);
String getFriendName(String friendWxid, String roomId);
String getFriendDisplayName(String friendWxid, String roomId);
int getFriendGender(String friendWxid);
String getFriendProvince(String friendWxid);
String getFriendCity(String friendWxid);
String getFriendRegion(String friendWxid);
String getAvatarUrl(String username);
String getAvatarUrl(String username, boolean isBigHeadImg);
```

### 取群聊列表

```beanshell
Object getGroupList();
Object getGroupListInfo();
```

`getGroupListInfo()` 返回 `Map` 列表，常见字段：

```text
roomId, name, nickname, remarkName, displayName, owner, memberCount, memberList, rawDisplayNames
```

### 取群成员列表

```beanshell
Object getGroupMemberList(String groupWxid);
Object getGroupMemberListInfo(String groupWxid);
```

`getGroupMemberListInfo()` 返回 `Map` 列表，常见字段：

```text
wxid, displayName, groupNick, groupNickName, rawGroupNickName,
nickname, remarkName, customWxId, gender, province, city, region, avatarUrl
```

### 取群成员数量

```beanshell
int getGroupMemberCount(String groupWxid);
```

### 取群聊和群成员资料

```beanshell
String getGroupName(String groupWxid);
String getChatroomName(String chatroomId);
String getGroupRemarkName(String groupWxid);
String getGroupMemberName(String groupWxid, String memberWxid);
String getGroupNickName(String groupWxid, String memberWxid);
int getGroupMemberGender(String groupWxid, String memberWxid);
String getGroupMemberProvince(String groupWxid, String memberWxid);
String getGroupMemberCity(String groupWxid, String memberWxid);
String getGroupMemberRegion(String groupWxid, String memberWxid);
```

### 标签方法

```beanshell
List getContactLabelList();
List getContactLabelListInfo();
List getContactByLabelId(String labelId);
List getContactByLabelName(String labelName);
String addContactLabel(String labelName);
void modifyContactLabelList(String username, String labelName);
void modifyContactLabelList(String username, List labelNames);
```

### 添加群成员

```beanshell
void addChatroomMember(String chatroomId, String addMember);
void addChatroomMember(String chatroomId, List addMemberList);
```

### 邀请群成员

```beanshell
void inviteChatroomMember(String chatroomId, String inviteMember);
void inviteChatroomMember(String chatroomId, List inviteMemberList);
```

### 移除群成员

```beanshell
void delChatroomMember(String chatroomId, String delMember);
void delChatroomMember(String chatroomId, List delMemberList);
```

### 通过好友申请

```beanshell
void verifyUser(String wxid, String ticket, int scene);
void verifyUser(String wxid, String ticket, int scene, int privacy);
```

## 消息方法

### 发送文本消息

```beanshell
void sendText(String talker, String content);
void sendText(String talker, String content, Consumer callback);
```

示例：

```beanshell
sendText(getTargetTalker(), "你好");
sendText("123@chatroom", "[AtWx=wxid_xxx] 你好");
sendText("123@chatroom", "[AtWx=notify@all] 大家好");
```

### 发送语音消息

```beanshell
boolean sendVoice(String talker, String sendPath);
boolean sendVoice(String talker, String sendPath, int durationSeconds);
```

无时长重载会按文件头识别真实音频类型，非 Silk 的常见音频会先转 Silk 再发送，并自动读取真实时长；三参重载的时长单位是秒。超过 60 秒的语音按真实文件发送，微信界面仍最多显示 60 秒；模块的 `伪造语音时长` 开启时，全局设置优先决定微信界面显示时长。

### 发送图片消息

```beanshell
boolean sendImage(String talker, String sendPath);
boolean sendImage(String talker, String sendPath, String appId);
boolean sendOriginalImage(String talker, String sendPath);
```

三参重载会按 WA 同款把 `appId` 写入图片消息 `appinfo.appid`；可传空字符串。

### 发送视频消息

```beanshell
boolean sendVideo(String talker, String sendPath);
```

### 发送表情消息

```beanshell
boolean sendEmoji(String talker, String sendPath);
```

### 发送文件消息

```beanshell
boolean sendFile(String talker, String sendPath);
boolean sendFile(String talker, String sendPath, String title);
```

### 收藏消息

```beanshell
Object getFavoriteList(int limit);
Object getFavorite(long localId);
boolean sendFavorite(String talker, long localId);
boolean sendFavorite(String talker, String localId);
```

### 发送拍一拍

```beanshell
void sendPat(String talker, String pattedUser);
```

### 发送分享名片

```beanshell
void sendShareCard(String talker, String wxid);
```

### 发送位置消息

```beanshell
void sendLocation(String talker, String poiName, String label, String x, String y, String scale);
void sendLocation(String talker, JSONObject jsonObj);
```

### 发送引用消息

```beanshell
void sendQuoteMsg(String talker, long msgId, String content);
void sendQuoteMsg(String talker, String content, long msgId);
```

### 发送 XML 消息

```beanshell
void sendXmlMsg(String talker, String content);
```

### 发送小程序消息

```beanshell
void sendAppBrandMsg(String talker, String title, String pagePath, String ghName);
```

### 发送媒体消息

```beanshell
void sendMediaMsg(String talker, Object mediaMessage, String appId);
```

### 撤回指定消息

```beanshell
void revokeMsg(long msgId);
```

### 插入系统消息

```beanshell
long insertSystemMsg(String talker, String content, long createTime);
```

### 查询历史消息

```beanshell
List<MsgInfoBean> queryHistoryMsg(String talker, long startTime, int count);
```

`startTime` 使用毫秒时间戳；大于 `0` 时查询该时间之后的消息，传 `0L` 时返回最近消息。列表项与 `onHandleMsg(...)` 的 `MsgInfoBean` 用法一致。

### 获取未读消息数

```beanshell
int getUnreadCount(String talker);
```

### 获取所有未读消息总数

```beanshell
int getAllUnreadCount();
```

### 清空未读消息

```beanshell
boolean clearUnread(String talker);
```

### 清空所有未读消息

```beanshell
boolean clearAllUnread();
```

## 媒体方法

### 分享文件

```beanshell
void shareFile(String talker, String title, String filePath, String appId);
```

### 分享小程序

```beanshell
void shareMiniProgram(String talker, String title, String description, String userName, String path, byte[] thumbData, String appId);
```

### 分享音乐

```beanshell
void shareMusic(String talker, String title, String description, String musicUrl, String musicDataUrl, byte[] thumbData, String appId);
```

### 分享音乐视频

```beanshell
void shareMusicVideo(String talker, String title, String description, String musicUrl, String musicDataUrl, String singerName, int duration, String songLyric, byte[] thumbData, String appId);
```

### 分享文本

```beanshell
void shareText(String talker, String text, String appId);
```

### 分享视频

```beanshell
void shareVideo(String talker, String title, String description, String videoUrl, byte[] thumbData, String appId);
```

### 分享网页

```beanshell
void shareWebpage(String talker, String title, String description, String webpageUrl, byte[] thumbData, String appId);
```

## 朋友圈方法

### 读取和原样转发

```beanshell
Object getSnsPostList();
Object getSnsPostList(int limit);
Object getSnsPostList(String userName, int limit);
Object getSnsPost(String snsId);
boolean prepareSnsPostMedia(String snsId, Consumer callback);
boolean publishSnsPost(Object prepared);
boolean refreshSnsTimeline();
```

读取接口只返回本机已经缓存的朋友圈，默认取最近 50 条，最大 200 条。记录 Bean 可调用 `getSnsId/getUserName/getDisplayName/getCreateTimeSeconds/getContentType/getType/getText/getMediaList` 和类型判断方法；网页链接、音乐和已确认扩展协议类型的 `getType()` 返回 `card`，未识别类型返回 `unknown`，原始值由 `getContentType()` 返回。原样转发先异步调用 `prepareSnsPostMedia`，回调结果 `isSuccess()` 后再传给 `publishSnsPost`；媒体准备失败原因由 `getMessage()` 返回。

### 上传文字

```beanshell
void uploadText(String content);
void uploadText(String content, String sdkId, String sdkAppName);
void uploadText(JSONObject jsonObj);
```

### 上传图文

```beanshell
void uploadTextAndPicList(String content, String picPath);
void uploadTextAndPicList(String content, String picPath, String sdkId, String sdkAppName);
void uploadTextAndPicList(String content, List picPathList);
void uploadTextAndPicList(String content, List picPathList, String sdkId, String sdkAppName);
void uploadTextAndPicList(JSONObject jsonObj);
```

### 上传实况照片

```beanshell
void uploadLivePhoto(String livePhotoPath);
void uploadLivePhoto(String imagePath, String videoPath);
void uploadLivePhoto(JSONObject jsonObj);
void uploadLivePhotoList(List livePhotoList);
void uploadLivePhotoList(JSONObject jsonObj);
void uploadTextAndLivePhoto(String content, String livePhotoPath);
void uploadTextAndLivePhoto(String content, String livePhotoPath, String sdkId, String sdkAppName);
void uploadTextAndLivePhoto(String content, String imagePath, String videoPath);
void uploadTextAndLivePhoto(String content, String imagePath, String videoPath, String sdkId, String sdkAppName);
void uploadTextAndLivePhoto(JSONObject jsonObj);
void uploadTextAndLivePhotoList(String content, List livePhotoList);
void uploadTextAndLivePhotoList(String content, List livePhotoList, String sdkId, String sdkAppName);
void uploadTextAndLivePhotoList(JSONObject jsonObj);
```

单张接口优先直接传包含内嵌视频的 `livePhotoPath`。多张使用列表接口，列表项可传单文件路径，也可传包含 `livePhotoPath`、`imagePath`、`videoPath` 和 `coverTimeMs` 的 `JSONObject`，最多 9 项；JSON 外层使用 `livePhotoList`，兼容 `livePhotoPathList`。旧的双路径重载继续兼容。多项逐张保留实况，配套视频无效的项单独按静态封面发布；当前微信没有原生实况入口时，多项整体按静态封面发布，单张旧接口仍返回失败。

### 上传视频

```beanshell
void uploadVideo(String videoPath);
void uploadVideo(JSONObject jsonObj);
void uploadTextAndVideo(String content, String videoPath);
void uploadTextAndVideo(String content, String videoPath, String sdkId, String sdkAppName);
void uploadTextAndVideo(JSONObject jsonObj);
```

## 网络方法

网络请求为异步接口，结果在回调里返回。

### GET 请求

```beanshell
void get(String url, Map headerMap, Consumer callback);
void get(String url, Map headerMap, long timeoutSeconds, Consumer callback);
void get(String url, Map headerMap, PluginCallBack.HttpCallback callback);
void get(String url, Map headerMap, long timeoutSeconds, PluginCallBack.HttpCallback callback);
```

### POST 请求

```beanshell
void post(String url, Map paramMap, Map headerMap, Consumer callback);
void post(String url, Map paramMap, Map headerMap, long timeoutSeconds, Consumer callback);
void post(String url, Map paramMap, Map headerMap, PluginCallBack.HttpCallback callback);
void post(String url, Map paramMap, Map headerMap, long timeoutSeconds, PluginCallBack.HttpCallback callback);
```

### 下载文件

```beanshell
void download(String url, String path, Map headerMap, Consumer callback);
void download(String url, String path, Map headerMap, long timeoutSeconds, Consumer callback);
void download(String url, String path, Map headerMap, PluginCallBack.DownloadCallback callback);
void download(String url, String path, Map headerMap, long timeoutSeconds, PluginCallBack.DownloadCallback callback);
void downloadImage(String url, Consumer callback);
void downloadImage(String url, String fileName, Consumer callback);
void downloadImages(List urlList, Consumer callback);
void downloadImages(List urlList, String prefix, Consumer callback);
void downloadImg(String md5, String cdnUrl, String aesKey, String savePath);
void downloadImg(String md5, String cdnUrl, String aesKey, String savePath, PluginCallBack.DownloadCallback callback);
void downloadImg(Object imageMsg, String savePath);
void downloadImg(Object imageMsg, String savePath, PluginCallBack.DownloadCallback callback);
void downloadVideo(String md5, String cdnUrl, String aesKey, String savePath, PluginCallBack.DownloadCallback callback);
void downloadVideo(Object videoMessage, String savePath, PluginCallBack.DownloadCallback callback);
void downloadFinderMedia(Object finderFeedOrMessage, String savePath, PluginCallBack.DownloadCallback callback);
void downloadFinderMedia(Object finderFeedOrMessage, int mediaIndex, String savePath, PluginCallBack.DownloadCallback callback);
```

`PluginCallBack.HttpCallback` 实现 `onSuccess(int statusCode, String response)` / `onError(Exception error)`；`PluginCallBack.DownloadCallback` 实现 `onSuccess(File file)` / `onError(Exception error)`。

`downloadImage(s)` 异步保存到 `Hchat/Image`。无回调的 `downloadImg(...)` 支持 HTTP URL 和微信 CDN fileid，并等待完整文件落盘；五参 `downloadImg(...)` 在后台复用同一下载链路并通过 `DownloadCallback` 返回结果。图片对象重载优先下载高清图。`downloadVideo(...)` 始终异步；优先传整条 `msg`，接口会先复用本地完整 MP4，再从 `imgPath` 查询原生 `VideoInfo` 并下载到指定路径，`savePath` 为空时保存到 `Hchat/Video`。`downloadFinderMedia(...)` 是 `alt-entry` 专属接口，接受原生 Finder 对象、聊天消息对象或视频号分享 XML 字符串；聊天分享 XML 缺少解密信息时，模块会按 `objectId` 与 `objectNonceId` 调用微信原生详情请求补齐后下载，视频完成解密并通过 MP4 文件头校验后才返回。默认下载索引 `0`，多图按索引分别调用。视频号 `savePath` 为空时保存到 `Hchat/Finder`，目录应已存在或以 `/` 结尾。媒体下载成功或失败只回调一次，回调线程不固定。

## 菜单扩展接口

菜单接口只用于微信主进程；`process=appbrand` 的插件不要调用，`process=all` 的插件应先判断 `isMainProcess`。

### 右上角加号菜单

```beanshell
Object registerPlusMenu(String title, String iconPath, Consumer callback);
Object registerPlusMenu(String title, String iconPath, boolean front, Consumer callback);
Object registerPlusMenu(String title, Consumer callback);
Object registerPlusMenu(String title, boolean front, Consumer callback);
```

回调的 `Consumer.accept(Object value)` 参数是当前 `Activity`，解析不到时可能为 `null`。点击插件条目时模块会拦截原点击方法、去重并先关闭加号菜单，再执行回调。

### 聊天长按消息菜单

```beanshell
Object registerMessageMenu(String title, String iconPath, Consumer callback);
Object registerMessageMenu(String title, String iconPath, boolean front, Consumer callback);
Object registerMessageMenu(String title, Consumer callback);
Object registerMessageMenu(String title, boolean front, Consumer callback);
```

回调参数是当前长按消息对应的真实 `ScriptMessageBean`。模块从长按行绑定的原生消息读取本地消息 ID，再按 ID 查询消息；没有有效绑定时不回调，不使用列表位置猜测消息。可调用 `getMsgId()`、`getTalker()`、`getSendTalker()`、`getContent()`、`isText()` 等消息接口。点击时模块会拦截微信原处理、去重并清理本次消息绑定，再执行插件回调；插件无需调用原生点击方法或自行清理选中状态。

### 参数和移除

```beanshell
void removeMenu(Object handle);
```

- `title` 去除首尾空白后不能为空，回调也不能为空；注册失败返回 `null`。
- `iconPath` 支持绝对路径或相对插件目录的图片路径；为空或读取失败时不设置自定义图标。
- `front=true` 把条目放到微信原生菜单之前；默认 `false`，多个前置条目按注册顺序排列。右上角加号菜单会按最终显示顺序整体重排为从 `0` 开始的连续位置 key，插件移除或重载后也会压平空位置。
- 注册成功返回独立句柄，使用 `removeMenu(handle)` 主动移除。插件关闭、重载或加载失败清理时会自动移除该插件的全部菜单。

```beanshell
Object plusHandle;
Object messageHandle;

void onLoad() {
    plusHandle = registerPlusMenu("插件入口", "icons/plus.png", true, new java.util.function.Consumer() {
        public void accept(Object activity) {
            openSettings();
        }
    });

    messageHandle = registerMessageMenu("处理消息", "icons/message.png", new java.util.function.Consumer() {
        public void accept(Object msg) {
            log("msgId=" + msg.getMsgId() + ", talker=" + msg.getTalker());
        }
    });
}

void onUnload() {
    removeMenu(plusHandle);
    removeMenu(messageHandle);
    plusHandle = null;
    messageHandle = null;
}
```

视频号媒体使用 `downloadFinderMedia(...)`，普通聊天视频使用 `downloadVideo(...)`；两者不要混用。

## Hook 方法

### 查找类

```beanshell
Class findClass(String className);
```

### 前置 Hook

```beanshell
Object hookBefore(Member member, Consumer callback);
```

### 后置 Hook

```beanshell
Object hookAfter(Member member, Consumer callback);
```

### 替换 Hook

```beanshell
Object hookReplace(Member member, Function callback);
```

### 卸载 Hook

```beanshell
void unhook(Object handle);
```

通过封装注册的 Hook 会在插件关闭或加载失败时自动清理。

## DexKit 方法

脚本可复用模块共享 DexKit，不需要自行初始化 DexKit。

### 查找类列表

```beanshell
List findClassList(Object usingStrings);
```

### 查找成员列表

```beanshell
List findMemberList(Object usingStrings);
```

示例：

```beanshell
List classes = findClassList({"MicroMsg", "LauncherUI"});
List members = findMemberList({"sendAppMsg", "attachFilePath"});
```

`findClass(String)` 只适合跨版本稳定的完整类名，混淆类应通过稳定字符串使用 `findClassList` / `findMemberList` 定位。`findMemberList` 会先返回字符串直接命中的方法/构造器，再追加类命中展开的全部成员。类展开导致多候选时，先确认直接查询的 descriptor，再按声明类和完整签名从前往后筛选，不要对整个展开列表强求全局唯一。

## Reflect 方法

### 查找第一个方法

```beanshell
Method firstMethod(Object instance, String methodName);
Method firstMethod(Object instance, String methodName, int paramCount);
```

### 查找第一个构造函数

```beanshell
Constructor firstConstructor(Object instance, int paramCount);
```

### 查找第一个字段

```beanshell
Field firstField(Object instance, String fieldName);
```

### 调用方法

```beanshell
Object invokeMethod(Object instance, String methodName);
Object invokeMethod(Object instance, String methodName, Object[] params);
Object invokeMethod(Object instance, String methodName, int paramCount);
Object invokeMethod(Object instance, String methodName, int paramCount, Object[] params);
```

不带 `params` 的 `paramCount` 重载只用于 `paramCount=0`；调用有参数方法必须传参数数组。

### 创建实例

```beanshell
Object createInstance(Object instance, int paramCount);
Object createInstance(Object instance, int paramCount, Object[] params);
```

不带 `params` 的重载只用于无参构造；有参数构造必须传参数数组。

### 读取字段

```beanshell
Object getField(Object instance, String fieldName);
```

### 设置字段

```beanshell
void setField(Object instance, String fieldName, Object value);
```

## 音频方法

### mp3 转 Silk

```beanshell
int mp3ToSilk(String mp3Path, String silkPath);
int mp3ToSilk(String mp3Path, String silkPath, int hz);
```

### Silk 转 mp3

```beanshell
int silkToMp3(String silkPath, String mp3Path);
int silkToMp3(String silkPath, String mp3Path, int hz);
```

### 取音频信息

```beanshell
long getDuration(String filePath);
long getDurationLimited(String filePath);
int getFileType(String filePath);
Map getAudioInfo(String filePath);
String getErrorMessage(int code);
```

### 转 Silk

```beanshell
int wavToSilk(String wavPath, String silkPath, int hz);
int flacToSilk(String flacPath, String silkPath, int hz);
int oggToSilk(String oggPath, String silkPath, int hz);
int pcmToSilk(String pcmPath, String silkPath, int hz, int pcmHz, int channels);
int autoToSilk(String audioPath, String silkPath, int hz);
int mp4ToSilk(String mp4Path, String silkPath, int hz);
int m4aToSilk(String m4aPath, String silkPath, int hz);
int aacToSilk(String aacPath, String silkPath, int hz);
int autoAacToSilk(String inputPath, String silkPath, int hz);
```

### 转 PCM

```beanshell
int silkToPcm(String silkPath, String pcmPath, int hz);
int mp3ToPcm(String mp3Path, String pcmPath);
int wavToPcm(String wavPath, String pcmPath);
int flacToPcm(String flacPath, String pcmPath);
int oggToPcm(String oggPath, String pcmPath);
int autoToPcm(String audioPath, String pcmPath);
int aacToPcm(String aacPath, String pcmPath);
int m4aToPcm(String m4aPath, String pcmPath);
```

### AAC / M4A / MP4

```beanshell
int decodeAacFile(String aacPath, String pcmPath);
int decodeM4aFile(String m4aPath, String pcmPath);
int encodePcmToAac(String pcmPath, String aacPath, int sampleRate, int channels);
int encodePcmToM4a(String pcmPath, String m4aPath, int sampleRate, int channels);
int pcmToAac(String pcmPath, String aacPath, int sampleRate, int channels);
int pcmToM4a(String pcmPath, String m4aPath, int sampleRate, int channels);
int mp4ToM4a(String mp4Path, String m4aPath, int hz);
int mp4ToAac(String mp4Path, String aacPath, int hz);
int silkToM4a(String silkPath, String m4aPath, int hz);
int silkToAac(String silkPath, String aacPath, int hz);
int m4aToAac(String m4aPath, String aacPath, int hz);
int m4aToM4a(String m4aPath, String m4aPathOut, int hz);
int autoToAac(String inputPath, String aacPath, int hz);
int autoToM4a(String inputPath, String m4aPath, int hz);
```

### 通用转换

```beanshell
void startTransform(int type, String inputPath, String outputPath, int sampleRate, Consumer callback);
```

## 其他方法

### 日志

```beanshell
void log(Object msg);
```

写入当前插件目录 `log.txt`，同时输出到 LSPosed。

### 提示

```beanshell
void toast(Object msg);
```

### 模块弹窗

```beanshell
boolean showModuleDialog(String title, String message);
boolean showModuleDialog(String title, String message, String position);
boolean showModuleConfirmDialog(String title, String message, Consumer callback);
boolean showModuleConfirmDialog(String title, String message, String position, Consumer callback);
boolean showModuleInputDialog(String title, String summary, String initialValue, String placeholder, Consumer callback);
boolean showModuleInputDialog(String title, String summary, String initialValue, String placeholder, String position, Consumer callback);
boolean showModuleChoiceDialog(String title, String summary, List choices, Consumer callback);
boolean showModuleChoiceDialog(String title, String summary, List choices, String position, Consumer callback);
boolean showModuleMultiChoiceDialog(String title, String summary, List choices, Set initialSelected, Consumer callback);
boolean showModuleMultiChoiceDialog(String title, String summary, List choices, Set initialSelected, String position, Consumer callback);
```

普通弹窗默认使用这些 Miuix 模块接口，不直接创建 Android `Dialog` / `AlertDialog`。带 `position` 的重载支持 `top`、`center`、`bottom`（也识别“顶部”“居中”“底部”），省略或使用未知值时保持默认底部。接口返回是否成功提交显示请求；没有前台 Activity 或选项为空时返回 `false`。确认回调接收 `Boolean`，输入回调接收文本，单选回调接收从 `0` 开始的索引，多选回调接收索引 `Set`；输入、单选和多选取消时不调用回调。

### 原生悬浮玻璃底栏

```beanshell
Object applyModuleFloatingGlassBar(View bottomBar);
Object applyModuleFloatingGlassBar(View bottomBar, Map options);
```

传入已挂到当前 Activity 内容树的真实底栏 `View`，成功返回句柄，失败返回 `null`。句柄提供 `restore()` 和 `isApplied()`；同一个 Activity 同时只能托管一个底栏，插件关闭、重载、原父容器离开窗口或 Activity 销毁时模块会自动恢复。`options` 可设置 `glass`、`clearBackground`、`horizontalMarginDp` 和 `bottomMarginDp`；液态玻璃只在 Android 13 及以上启用，低版本自动回退普通悬浮样式。接口只处理样式和生命周期，不负责定位微信各版本的底栏 View。

### 延迟执行

```beanshell
void delay(long millis, Runnable action);
```

### 通知

```beanshell
void notify(String title, String text);
```

### 上传设备步数

```beanshell
void uploadDeviceStep(long step);
```

### 重载插件

```beanshell
void reloadPlugin();
```

### 执行代码

```beanshell
void eval(String code);
```

### 导入 Java 源文件

```beanshell
void loadJava(String path);
```

### 导入 Dex

```beanshell
ClassLoader loadDex(String path);
```

### 加载 Native SO

```beanshell
void loadSo(String path);
void loadSo(String path, ClassLoader loader);
```

相对路径从插件目录解析。不要在脚本顶层声明 `native` 方法，顶层函数没有可供 JNI 绑定的 Java 类。可以把 JNI 方法声明在 BeanShell 类中，并把 `NativeClass.class.getClassLoader()` 传给第二个重载；也可以通过 `loadDex()` 加载编译好的 JNI 包装类，再传入其返回的 `ClassLoader`。类全名和方法名必须匹配 SO 的 JNI 符号或 `RegisterNatives` 目标。SO 必须匹配微信进程 ABI；替换后重新加载插件，并把新 JNI 类的 `ClassLoader` 传给双参数重载，即可加载新版本。单参数重载使用固定宿主 `ClassLoader`，不能热更新。旧版本无法热卸载，会驻留到微信进程结束。

```beanshell
package com.example.plugin;

class NativeBridge {
    public static native String decrypt(String name);
}

void onLoad() {
    loadSo("libplugin.so", NativeBridge.class.getClassLoader());
    String result = NativeBridge.decrypt("demo");
}
```

### 快照脚本

```beanshell
String compileSnapshot(String path);
Object evalSnapshot(String path);
Object evalSnapshot(InputStream inputStream);
Object evalSnapshot(byte[] data);
```

## 内置类和兼容

常用类可直接使用或导入：

```beanshell
XposedBridge;
XposedHelpers;
XC_MethodHook;
WeChatApis;
KavaReflector;
DexKitBridge;
DexFinder;
DexBridgeHolder;
JSON;
JSONObject;
JSONArray;
JSONPath;
```

兼容 WA 常见对象：

```beanshell
me.hd.wauxv.data.bean.info.FriendInfo
me.hd.wauxv.data.bean.info.GroupInfo
me.hd.wauxv.plugin.api.callback.PluginCallBack.HttpCallback
me.hd.wauxv.plugin.api.callback.PluginCallBack.DownloadCallback
```

## 注意事项

- 插件修改 `main.java` 后会自动重载。
- 加载失败会自动关闭当前插件，并写入当前插件目录 `log.txt`。
- `cacheDir` 是 Hchat 全局缓存目录，不是单个插件目录。
- 普通消息监听来源是微信 `message` 数据库，可能比实时消息稍晚。
- WA 旧插件优先直接迁移；遇到缺接口，再按 WA 签名补兼容。
