# 合并转发详情类型标签逆向依据（2026-09-11）

目标为本地微信 8.0.76 (3141)。本轮只有该 APK，8.0.49 / 8.0.58 / 8.0.66 / 8.0.68 / 8.0.72 / 8.0.74 / 8.0.77 未横向验证，也未做真机 UI 验证。

## 绑定入口和作用范围

- RecordMsgDetailUI.a7() 同时使用 record_nest / record_show_share / record_xml 等协议键，用其唯一无参 void 方法的 declaringClass 限定详情 Activity。收藏详情、RecordMsgImageUI、RecordMsgFileUI 不符合该类，不能注入。
- RecordMsgDetailUI.W6() 的 from_scene == 3 返回 com.tencent.mm.plugin.record.ui.q，其余返回 x；q 的父类是 h0，且不覆写 getView。父类 h0.getView(int, View, ViewGroup) 含埋点稳定串 com/tencent/mm/plugin/record/ui/RecordMsgBaseAdapter，是真实列表行绑定入口。
- h0.getView 调用 d(position) 取得 a65.xp0，构造 hu3.b 并令其 a 字段指向同一对象，最终调用 g0.b(row, position, recordData, state)。h0 的 Android getItem 桥委派给 d，直接复用 adapter.getItem(position)，无需查询微信数据库。
- h0.getItemViewType(int) 的 using-fields 只有 La65/xp0;->I:I，getView 同样读取该字段。动态取得 getItemViewType 的唯一外部 int 字段，并与 getView 字段集合交叉确认。它是 dataitem datatype，不是 rawType。
- 数据序列化 f82.a.f(List) 将 xp0.I 写到 dataitem 的 datatype 属性；eu3.h2.e(Map,String,int) 从 .dataitem.$datatype 读取经 h0(int) setter 写回 xp0.I。该解析器的 datadesc 对应 xp0.f，recordxml 对应 xp0.j2；没有因此推断为原始微信消息 content 或 rawType。
- 所有定位使用 DexMethodCache 的实时 runtimeKey；缓存绑定 Method、详情范围 Method 和 datatype Field descriptor；缓存有效时不再查询 DexKit。所有查询与 Hook 安装由调用方的 BRIDGE 队列串行执行。

## 布局证据和复用规则

逐一导出 record/ui/viewWrappers 下 a / m / q / s / y / z 的 a(Context)，取得实际 inflate 布局，再通过 get_resource_value + decode_xml 核对六种完整 XML：dvx、ccz、cd0、cd1、cd2、cd3。RecordMsgBaseUI.a7() 已确认 SparseArray 将 0/1/2/3/4/5 分别注册为 y/m/z/q/s/a；datatype 21 进入 index 5。六种布局均为横向 LinearLayout 根节点，含头像与唯一直接垂直 LinearLayout；垂直列依次含头部 include、消息内容、1 px ImageView 分隔线。ccz 的根节点还包含 ProgressBar，因此不能假设根仅有两个子节点。

实现仅在经结构验证的该垂直列底部、分隔线上方插入模块 TextView。没有对宿主根 setTag、替换 click listener、改写 layoutParams、重挂宿主内容或添加布局监听。宿主 convertView 每次绑定前清理已有文字/可见状态，绑定后写入当前类型；关闭配置、刷新与 destroy 清理模块标签，WeakHashMap 的 value 通过 WeakReference 保存 label，避免 value 经子 View 反向保活根 row / Activity。

## datatype 语义

- h0 的 type 1 / 2 / 3 对应文字 / 图片 / 语音；21 使用单独笔记 wrapper（a），其解析 recordxml、WeNoteHtmlFile 且 UI 为笔记卡片。
- OtherViewWrapper（q）fillView 明确分支：4 / 15 视频；6 位置（location 日志）；7 / 29 / 32 音乐播放控件；8 / 10130 文件后缀图标；10 商品（product 日志）；11 商城商品（mall product 日志）；14 电视（tv 日志）；16 名片；19 小程序（资源 0x7f100273 解码为“小程序”）；31 OpenIM 客服名片（fillOpenImKefuCardContent 日志）。
- 22 读取 finderItem，23 读取 finderLiveItem 且资源 0x7f101857 为“%s的直播”；26 / 41 调用 B，读取 yp0.A，该字段由 eu3.h2.e 的 finderShareNameCard 节点构造。
- datatype 17 的 n.onClick 明确调用 q.o：消息路径将 recordxml 传给 RecordMsgDetailUI 并设置 record_nest=true；记录具有笔记元数据时转到 OpenNoteFromSessionEvent。因此该数值单独只能标为“聊天记录/笔记”，不能只按数字保证是纯聊天记录。
- 其余 datatype 未在此文件凭数字套用 rawType；共享 MessageTypeLabels.recordLabel 只按已确认分类命名，未知值保留“记录消息（类型 N）”。

## 验证边界

本轮证据验证了 Hook、桥委派、datatype 与 XML 元数据、六种行结构。尚未证实发送方原始消息类型可以从接收方 dataitem 完整还原，因此不会按文字描述推断拍一拍、系统消息或礼物；微信若在转发中转换为文字，标签按收到的 dataitem 类型显示。最终安装包仍需测试长记录滚动、图片加载、语音转文字展开、嵌套聊天记录、配置开关和收藏页面隔离。

独立 Kotlin 编译 ForwardedRecordTypeLabels.kt + MessageTypeLabels.kt 成功；未执行 Gradle。已有消息布局回调的 10 项回归全部通过（不代表新详情 Hook 已经真机验证）。

## 实际设备 3140 复核（用户反馈后的排查）

用户实际安装的是微信 8.0.76 (3140)，而初次参考 APK 为 3141。设备运行缓存已经保存 h0.getView / RecordMsgDetailUI.a7 / xp0.I，配置包含 type；本次读取的模块错误日志未出现聊天记录详情类型绑定异常，已出现的错误是主聊天列表外层合并转发卡片 gg5.t4 的 clickArea 锚点未找到，不能将外层错误当作详情 adapter 未安装。

实际安装 APK 的 MCP 复核确认 h0.getView 仍读取 xp0.I 并调用相同 wrapper；RecordMsgDetailUI.W6 仍用 this 创建 x 或 from_scene=3 的 q。真实 DexKit 2.0.1 字节码显示 usingStrings(Collection) 默认 Contains，按相同 Contains 条件复核两条字符串查询均只有一个候选；因此没有把该反馈归因于字符串匹配。3140 的 cd2 完整 XML 已解码，六份 wrapper 布局 dvx/ccz/cd0/cd1/cd2/cd3 的打包 XML SHA-256 均与此前逐一解码验证的 3141 完全一致。classes12.dex 整体哈希不同，因此方法行为仍以本轮针对 3140 的 MCP 结果为准，不按相同类名推断整个 DEX 相同。

该轮没有发现足以证明详情 Hook 失败的证据，没有新增未验证的 Context 或布局兜底。当前设备前台为 Termux，尚不能声称已实测详情页面中的标签显示；主聊天外层转发卡锚点问题由主列表实现另行修复。
