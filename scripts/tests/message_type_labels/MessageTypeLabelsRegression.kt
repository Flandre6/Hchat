package h.Hchat.hooks.items.hchatextra

private var checks = 0
private fun same(expected: Any?, actual: Any?) {
    check(expected == actual) { "Expected <$expected>, got <$actual>" }
    checks++
}
private fun card(type: Int) = "<msg><appmsg><type>$type</type></appmsg></msg>"
fun main() {
    // Signed gift encodings must not be discarded by positive-only normalization.
    for (type in listOf(-2130706383, -2113929167)) same("送礼物", MessageTypeLabels.label(type, ""))
    for (sub in listOf(115, 124)) same("送礼物", MessageTypeLabels.label(49, card(sub)))
    same("直播抽奖礼物", MessageTypeLabels.label(-2080374735, ""))
    same("小店客服动态卡片", MessageTypeLabels.label(-2097151951, ""))
    same("微视视频", MessageTypeLabels.label(687865905, ""))
    same("视频号名片", MessageTypeLabels.label(771751985, ""))
    same("视频号视频", MessageTypeLabels.label(754974769, ""))
    same("视频号直播", MessageTypeLabels.label(973078577, ""))
    same("文件", MessageTypeLabels.label(1090519089, ""))
    same("红包", MessageTypeLabels.label(503316529, ""))
    same("拍一拍", MessageTypeLabels.label(889192497, ""))
    same("拍一拍", MessageTypeLabels.label(922746929, ""))
    same("模板通知", MessageTypeLabels.label(318767153, ""))
    same("小程序通知", MessageTypeLabels.label(872415281, ""))
    for (type in listOf(10000, 10002, 570425393, 64, 603979825, 268445456, 268445458, 285222674)) {
        same("系统消息", MessageTypeLabels.label(type, "群主已开启入群验证"))
    }
    same("撤回消息", MessageTypeLabels.label(10002, "<sysmsg type=\"revokemsg\"><revokemsg/></sysmsg>"))
    same("系统消息", MessageTypeLabels.label(10000, "请勿使用拍一拍"))
    same("文字", MessageTypeLabels.label(1, card(115)))
    same("图片", MessageTypeLabels.label(33, card(115)))
    same("系统消息（future_notice）", MessageTypeLabels.label(10002, "<sysmsg type=\"future_notice\"/>"))

    // Outer type wins over embedded record, reply and CDATA payloads.
    val nested = "<msg><appmsg><recorditem><appmsg><type>115</type></appmsg></recorditem><type>19</type></appmsg></msg>"
    same(19, MessageTypeLabels.appType(nested))
    same("聊天记录", MessageTypeLabels.label(49, nested))
    same(57, MessageTypeLabels.appType("<appmsg><type>57</type><refermsg>${card(115)}</refermsg></appmsg>"))
    same(null, MessageTypeLabels.appType("<msg><appmsg><refermsg><appmsg><type>115</type></appmsg></refermsg></appmsg></msg>"))
    same(19, MessageTypeLabels.appType("<appmsg><recorditem><![CDATA[${card(115)}]]></recorditem><type>19</type></appmsg>"))
    same(115, MessageTypeLabels.appType("wxid_example:\n${card(115)}"))
    same(null, MessageTypeLabels.appType("<appmsg><type>not-a-number</type></appmsg>"))
    same(null, MessageTypeLabels.appType("<!DOCTYPE appmsg [<!ENTITY x '115'>]><appmsg><type>&x;</type></appmsg>"))
    same(null, MessageTypeLabels.appType(" ".repeat(512 * 1024) + card(115)))
    same(null, MessageTypeLabels.appType("<msg>" + "<nested>".repeat(65) + "<appmsg><type>115</type></appmsg>"))
    same(null, MessageTypeLabels.appType("<msg><appmsg><type>"))
    same("送礼物", MessageTypeLabels.label(49, card(115), "not xml"))
    same("卡片（子类型 9001，类型 49）", MessageTypeLabels.label(49, card(9001)))
    same("消息（类型 -77）", MessageTypeLabels.label(-77, card(115)))
    same("卡片（子类型 2003，类型 536936497）", MessageTypeLabels.label(536936497, ""))
    same("卡片（子类型 48，类型 738197553）", MessageTypeLabels.label(738197553, ""))

    // Identical integers in record and normal messages must use their own namespaces.
    same("语音", MessageTypeLabels.recordLabel(3))
    same("图片", MessageTypeLabels.label(3, ""))
    same("聊天记录/笔记", MessageTypeLabels.recordLabel(17))
    same("小程序", MessageTypeLabels.recordLabel(19))
    same("聊天记录", MessageTypeLabels.label(49, card(19)))
    same("名片", MessageTypeLabels.recordLabel(16))
    same("文件", MessageTypeLabels.recordLabel(10130))
    same("图文号名片", MessageTypeLabels.recordLabel(10132))
    same("小店橱窗", MessageTypeLabels.recordLabel(34))
    same("直播主题", MessageTypeLabels.recordLabel(40))
    same("视频号话题", MessageTypeLabels.recordLabel(27))
    same("记录消息（类型 115）", MessageTypeLabels.recordLabel(115))
    same("记录消息（类型 -1）", MessageTypeLabels.recordLabel(-1))
    same("记录消息（类型 9999）", MessageTypeLabels.recordLabel(9999))
    println("MessageTypeLabels: $checks assertions passed")
}
