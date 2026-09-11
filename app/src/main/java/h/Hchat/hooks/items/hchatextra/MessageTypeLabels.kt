package h.Hchat.hooks.items.hchatextra

import h.Hchat.hooks.api.model.WeChatMessageTypes
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/** 仅用于详情标签；不修改发送类型、消息过滤或数据库。 */
internal object MessageTypeLabels {
    // QEchat HchatExtraHooker.detailedMessageTypeLabel 的 appmsg 一级 type 分类。
    fun appType(content: String): Int? = runCatching {
        if (content.length > 512 * 1024 || content.contains("<!DOCTYPE", true)) return null
        val start = content.indexOf('<')
        if (start < 0) return null
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(content.substring(start)))
        var appDepth = -1
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (appDepth < 0) {
                    if (parser.depth == 1 && parser.name == "appmsg" ||
                        parser.depth == 2 && parser.name == "appmsg") appDepth = parser.depth
                } else if (parser.depth == appDepth + 1 && parser.name == "type") {
                    return parser.nextText().trim().toIntOrNull()
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.depth == appDepth) {
                return null
            }
        }
        null
    }.getOrNull()

    fun subtype(type: Int, content: String, body: String): Int? {
        if (WeChatMessageTypes.normalize(type) != WeChatMessageTypes.APP) return null
        return appType(body) ?: if (body != content) appType(content) else null
    }

    fun label(type: Int, content: String, body: String = content): String {
        val base = WeChatMessageTypes.normalize(type)
        if (type == 922746929 || base == WeChatMessageTypes.SYSTEM &&
            listOf(content, body).any { it.contains("拍了拍") || it.contains("拍一拍") ||
                it.contains("patmsg", true) || it.contains("patteduser", true) || it.contains("patuser", true) }) return "拍一拍"
        if (base == WeChatMessageTypes.APP) {
            return when (val sub = subtype(type, content, body)) {
                1 -> "文字卡片"
                2 -> "图片卡片"
                3, 76 -> "音乐"
                4, 5 -> "链接"
                6, 74, 130, 131 -> "文件"
                8 -> "表情卡片"
                19 -> "聊天记录"
                24 -> "收藏笔记"
                33, 36 -> "小程序"
                45 -> "视频号视频"
                46 -> "视频号名片"
                53 -> "接龙"
                57 -> "引用回复"
                58 -> "视频号直播"
                62 -> "拍一拍"
                2000, 2011 -> "转账"
                2001 -> "红包"
                null -> when (type) {
                    WeChatMessageTypes.VIDEO_ACCOUNT -> "视频号视频"
                    WeChatMessageTypes.VIDEO_ACCOUNT_CARD -> "视频号名片"
                    WeChatMessageTypes.VIDEO_ACCOUNT_LIVE -> "视频号直播"
                    WeChatMessageTypes.APP -> "链接/卡片"
                    else -> "卡片（类型 $type）"
                }
                else -> "卡片（子类型 $sub）"
            }
        }
        return when (base) {
            1 -> "文字"
            3 -> "图片"
            34 -> "语音"
            37 -> "好友申请"
            40 -> "好友推荐"
            42, 66 -> "名片"
            43 -> "视频"
            47 -> "表情"
            48 -> "位置"
            50, 51, 52, 53 -> "通话消息"
            62 -> "小视频"
            82 -> "商品"
            10000 -> "系统消息"
            10002 -> "撤回消息"
            else -> "消息（类型 $type）"
        }
    }
}
