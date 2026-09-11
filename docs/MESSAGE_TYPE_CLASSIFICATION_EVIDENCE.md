# 当前微信消息分类证据（8.0.76，2026-09-11）

使用 DexClub MCP 对本地微信 8.0.76 (3141) APK 核验。本轮没有其他版本 APK，也未真机验证。下文 Java、Smali 和 JSON 文件名为本地证据导出名称，对应方法描述符列于各条；原始导出不随源码分发。

## 覆盖与边界

- `app-type-map.java`：`Lcom/tencent/mm/pluginsdk/model/app/k0;->p(Lpt0/r;)I` 给出 appmsg subtype 到数据库 raw type 的映射；**高位不是 appmsg subtype**。负数 -2130706383、-2113929167 是实际送礼物类型，公共 normalize 故意不处理负数，展示层须显式匹配。
- `chat-item-factory.java` / `.smali`：`Lcom/tencent/mm/ui/chatting/viewitems/xs;-><init>(Lfd5/d;)V` 的当前工厂注册全集。Java import 存在同名混淆类冲突，确认类归属时使用 MCP Smali `const-class`，不能一律按 imports 猜归属。
- `classification-method-strings.json`：注册类及委托实际方法的 strings/invokes 证据。类名混淆不能做语义依据；日志、实际 XML 字段和 instrumentation 原始业务名用于分类。
- 所有 raw type 和 record datatype 均保证有标签；尚未证实具体业务名的 app subtype 显示 `卡片（子类型 N，类型 R）`，其它 raw type 显示 `消息（类型 R）`，record 显示 `记录消息（类型 N）`，不冒充已识别业务。

## 新增已确认语义

| appmsg subtype | raw type | 语义与依据 |
| --- | --- | --- |
| 115/124 | -2130706383/-2113929167 | 送礼物；factory f6/g6，f6.m -> h6.a，实际业务日志 ChattingItemAppMsgEcsGiftChatroom / EcsGiftMsgService，gift_msg_id / take_method / with_gift_cover |
| 128 | -2080374735 | 直播抽奖礼物；ml.m 委托包含 ChattingItemFinderLiveLotteryGift / .msg.appmsg.giftinfo.* |
| 89 | 1078984753 | 直播抽奖；jl.m 委托 ChattingItemFinderLiveLottery |
| 126 | -2097151951 | 小店客服动态卡片；m6.m instrumentation ChattingItemAppMsgEcsKFDynamicCard、ecs_kf_card_dynamic |
| 116 | 1191182385 | 小店客服商品卡片；工厂同类型 cl，实际日志 ChattingItemEcsKfProductCard |
| 82 | 974127153 | 小店商品；gg5.o0 继承 gg5.n0，ecs_kf_card_product / ChattingItemAppMsgEcsProductCardMvvm |
| 44 | 49 | 小店店铺卡片；gg5.e0 继承 gg5.c0，ecs_kf_card_shop / ecs_kf_card_wa_native_app |
| 96 | 977272881 | 小店订单；n8.m 委托原始业务名 ChattingItemAppMsgFinderOrder |
| 46 | 687865905 | 微视视频；af.m 委托 MicroMsg.WeishiVideoItemViewHolder；旧代码将 subtype 46 标成视频号名片不正确 |
| 50 | 771751985 | 视频号名片；factory 修复开关 RepairerConfigMvvmItem_FinderNameCard + app-type-map |
| 51/106/129 | 754974769/1174405169/1241514033 | 视频号视频；同工厂 o7，o7.m 原始业务名 ChattingItemAppMsgFinderFeed；75 的 1057030193 分支也复用它 |
| 63/88 | 973078577/975175729 | 视频号直播；工厂 n，n.m 委托 BaseChattingItemAppMsgFinderLiveFeed |
| 60 | 855638065 | 直播分享；pd.m 日志 ChattingItemAppMsgShareLiveFrom |
| 65 | 989855793 | 直播邀请；s7.m 日志 ChattingItemAppMsgLiveInviteFrom |
| 80 | 1075839025 | 订阅通知；xl.m 读取 .msg.appmsg.extinfo.notifymsg.templateid |
| 112 | 1081081905 | 视频号直播订阅通知；bm.m instrumentation ChattingItemFinderLiveSubscriptionNotifyTmpl、.service_notify.* |
| 113 | 979370033 | 直播主题；x7.m 委托 ChattingItemAppMsgFinderLiveTheme |
| 118 | 1442840625 | 听一听聊天室；le.m 委托 MicroMsg.TingChatRoomItemHolder |
| 101 | 1140850737 | 游戏分享；gg5.t2 继承 gg5.s2，ChattingItemAppMsgGameShareMvvm |
| 17 | -1879048186 | 位置共享；eb.m 日志 ChattingItemAppMsgLocationShareFrom |

raw 10000/10002/570425393/64/603979825/268445456/268445458/285222674 全部注册系统行 hn，统一系统消息分类；不能把所有 10002 都标“撤回”。撤回以 sysmsg revokemsg 区分。raw 889192497/922746929 均注册 vc 拍一拍；不再根据文本包含“拍一拍”误判普通消息。

raw 318767153 工厂 ChattingItemDyeingTemplate；872415281 的 i1.m 包含 #NotifyMessageStatus 并读取小程序通知服务；285212721 为 hg 的 ChattingItemBizFrom。raw 67 为 zm 的 ChattingItemOpenIMKefuNameCard；85/86 为 md 的 ChattingItemAppMsgRingtone。负数 -1879048189 为 ChattingItemVoiceRemindConfirm，-1879048190 为 ChattingItemVoiceRemindRemind，-1879048185 为 ChattingItemHardDeviceMsg，-1879048183 为 ChattingItemHardDeviceMsgLike。

## 合并转发分类是独立编号

`record-card.java`：`Lcom/tencent/mm/plugin/record/ui/viewWrappers/q;->b(Landroid/view/View;ILhu3/b;Ljava/lang/Object;)V`。
`record-item-parser.java`：`Leu3/h2;->e(Ljava/util/Map;Ljava/lang/String;I)La65/xp0;`。

- 1/2/3 由 h0.getView 分流文字/图片/语音；21 使用收藏笔记 wrapper（record agent 的证据）。
- q.b：4/15 video、6 location、8/10130 file、10 product、11 mall product、14 tv、16 名片解析、31 OpenIMKefuCard。
- 19 的资源 0x7f100273 解码“小程序”。不能按 appmsg 19 标“聊天记录”。
- 22 的资源 0x7f101859 解码“的视频”；23 的 0x7f101857/0x7f101856 解码“%s的直播”/“%s的直播回放”。
- 26/41 -> q.B -> yp0.A；eu3.h2.e 明确该字段来自 finderShareNameCard。
- 28 -> yp0.B；eu3.h2.e 明确 finderContentColumnSharedItem，分类视频号专栏。
- 27/30 -> yp0.D/E；eu3.h2.e -> tz2.i5.p，实际字符串 finderTopicShareItem、topicType、topic，分类视频号话题。
- 34/39 -> q.C，资源 0x7f101858 解码“%s的橱窗”。
- 40 -> q.A -> yp0.I；eu3.h2.e -> tz2.i5.r，实际字符串 finderThemeLiveStream / parseLiveTheme，分类直播主题。
- 10132 -> q.D，实际日志 parse photo account msg failed；eu3.h2.d 明确 photoAccountShareNameCard。
- 5/36 同 q.E 网页内容；7/29/32 使用音频播放控件，分类音乐。未知 record 类型保持编号，尤其不能仅凭 title/datadesc 内容猜类型。

## 验证

脚本 `scripts/run_message_type_labels_tests.cjs` 直接使用 Kotlin 2.4.0 compiler 与真实 kxml2 解析器运行生产 MessageTypeLabels；无 Gradle、无伪造 parser。覆盖负数礼物、raw/subtype 错位、系统事件、未知编号、嵌套引用、XML 安全边界、独立 record namespace。真机布局和消息类型实际展示待安装验证。

工厂计数：119 个去重 raw type，raw=49 明确注册 17 个 subtype 条目（含 subtype=0 的通用入口）；展示层补充 73 个 raw→appmsg subtype 映射。完整表保存于本地 classification-coverage.json。分类回归含 datatype 17 补充，共 59 项断言通过。

合并转发 datatype 17 的点击进入嵌套聊天记录或带笔记元数据的笔记，标记“聊天记录/笔记”，见 FORWARDED_RECORD_TYPE_EVIDENCE.md。
