package h.Hchat.hooks.items.script.agent

import java.io.StringReader
import java.util.Properties

object ScriptPluginAgentValidator {
    private val invalidIdChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    private val unsafeFilePath = Regex(
        "(?:new\\s+File|FileOutputStream|FileWriter|RandomAccessFile|Paths\\.get)\\s*\\(\\s*[\\\"'](?:/|[A-Za-z]:[\\\\/]|[^\\\"']*\\.\\.[\\\\/])"
    )

    fun normalize(draft: ScriptPluginAgentDraft): ScriptPluginAgentDraft {
        val info = cleanFencedText(draft.infoProp)
        val parsed = parseInfo(info)
        val name = draft.pluginName.trim().ifBlank { parsed.getProperty("name").orEmpty() }
        val id = safePluginId(draft.pluginId.ifBlank { name })
        return draft.copy(
            pluginName = name,
            pluginId = id,
            infoProp = info.trim(),
            mainJava = cleanFencedText(draft.mainJava).trim(),
            summary = draft.summary.trim()
        )
    }

    fun safePluginId(value: String): String {
        val cleaned = value.trim()
            .replace(invalidIdChars, "_")
            .replace(Regex("\\s+"), "_")
            .trim('.', ' ')
            .take(64)
        return cleaned.ifBlank { "ai_plugin" }
    }

    fun cleanFencedText(value: String): String {
        var result = value.trim()
        if (result.startsWith("```")) {
            result = result.substringAfter('\n', "")
        }
        if (result.endsWith("```")) {
            result = result.dropLast(3).trimEnd()
        }
        return result
    }

    fun validate(draft: ScriptPluginAgentDraft): ScriptPluginAgentValidation {
        val issues = ArrayList<ScriptPluginAgentIssue>()
        val id = draft.pluginId.trim()
        val code = draft.mainJava
        val info = parseInfo(draft.infoProp)
        if (id.isBlank() || id == "." || id == "..") {
            issues += ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "插件目录名不能为空")
        }
        if (id != safePluginId(id)) {
            issues += ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "插件目录名包含路径或文件名不允许的字符")
        }
        if (id.contains("..")) {
            issues += ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "插件目录名不能包含 ..")
        }
        if (code.isBlank()) {
            issues += ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "main.java 不能为空")
        }
        listOf("name", "version", "author").forEach { key ->
            if (info.getProperty(key).orEmpty().trim().isBlank()) {
                issues += ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "info.prop 缺少 $key")
            }
        }
        val processValues = info.getProperty("process").orEmpty()
            .lowercase()
            .split(Regex("[,;|\\s]+"))
            .filter { it.isNotBlank() }
        val invalidProcesses = processValues.filterNot { it == "main" || it == "appbrand" || it == "all" }
        if (invalidProcesses.isNotEmpty()) {
            issues += ScriptPluginAgentIssue(
                ScriptPluginAgentIssueLevel.ERROR,
                "info.prop 的 process 只支持 main、appbrand 或 all"
            )
        }
        if (code.contains("```")) {
            issues += ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "main.java 仍包含 Markdown 代码围栏")
        }
        if (!balanced(code, '{', '}')) {
            issues += ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "main.java 的大括号不平衡")
        }
        if (containsTopLevelNativeDeclaration(code)) {
            issues += ScriptPluginAgentIssue(
                ScriptPluginAgentIssueLevel.ERROR,
                "BeanShell 顶层 native 方法无法绑定 JNI，请把 native 声明放进类并将该类的 ClassLoader 传给 loadSo"
            )
        }
        if (unsafeFilePath.containsMatchIn(code)) {
            issues += ScriptPluginAgentIssue(
                ScriptPluginAgentIssueLevel.ERROR,
                "代码包含绝对路径或 .. 路径，请改用 pluginDir、pluginDirFile 或 cacheDir"
            )
        }
        val callbackNames = listOf(
            "onLoad", "onUnload", "openSettings", "onClickSendBtn", "onLongClickSendBtn",
            "onHandleMsg", "onImageDownload", "onVideoDownload", "onFinderMediaDownload",
            "onMemberChange", "onNewFriend", "onProtobufPacket"
        )
        callbackNames.forEach { callback ->
            if (Regex("\\b$callback\\s*\\(").containsMatchIn(code) && !Regex("\\b$callback\\s*\\([^)]*\\)\\s*\\{").containsMatchIn(code)) {
                issues += ScriptPluginAgentIssue(
                    ScriptPluginAgentIssueLevel.WARNING,
                    "$callback 的定义看起来不完整，请确认回调签名和大括号"
                )
            }
        }
        issues += riskIssues(code)
        return ScriptPluginAgentValidation(issues.distinctBy { it.level to it.message })
    }

    fun validateAdditionalCode(path: String, code: String): List<ScriptPluginAgentIssue> {
        val issues = ArrayList<ScriptPluginAgentIssue>()
        if (code.contains("```")) {
            issues += ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "$path 仍包含 Markdown 代码围栏")
        }
        if (unsafeFilePath.containsMatchIn(code)) {
            issues += ScriptPluginAgentIssue(
                ScriptPluginAgentIssueLevel.ERROR,
                "$path 包含绝对路径或 .. 路径，请改用 pluginDir、pluginDirFile 或 cacheDir"
            )
        }
        if (containsTopLevelNativeDeclaration(code)) {
            issues += ScriptPluginAgentIssue(
                ScriptPluginAgentIssueLevel.ERROR,
                "$path: BeanShell 顶层 native 方法无法绑定 JNI，请把 native 声明放进类并将该类的 ClassLoader 传给 loadSo"
            )
        }
        issues += riskIssues(code).map { issue -> issue.copy(message = "$path: ${issue.message}") }
        return issues
    }

    private fun riskIssues(code: String): List<ScriptPluginAgentIssue> {
        return highRiskPatterns.mapNotNull { (pattern, message) ->
            if (pattern.containsMatchIn(code)) {
                ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.WARNING, message, risky = true)
            } else {
                null
            }
        }
    }

    private val highRiskPatterns = listOf(
        Regex("Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder") to "包含执行系统进程的代码，保存前请确认来源和用途",
        Regex("ClassLoader|DexClassLoader|createPackageContext") to "包含 ClassLoader 或跨包加载代码，保存前请确认来源和用途",
        Regex("System\\.load(?:Library)?|\\bloadSo\\s*\\(") to "包含Native库加载代码，保存前请确认来源和用途",
        Regex("java\\.lang\\.reflect|XposedBridge|XposedHelpers|hookBefore|hookAfter|hookReplace") to "包含反射或 Hook 代码，保存前请确认来源和用途",
        Regex("\\.delete\\s*\\(") to "包含删除文件的代码，保存前请确认来源和用途",
        Regex("OkHttpClient|new\\s+URL\\s*\\(|Socket|https?://|\\b(?:get|post|download)\\s*\\(\\s*\"https?://") to "包含网络访问代码，保存前请确认请求目标和数据范围"
    )

    private fun parseInfo(value: String): Properties {
        return Properties().also { properties ->
            runCatching { properties.load(StringReader(value)) }
        }
    }

    private fun balanced(code: String, open: Char, close: Char): Boolean {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var lineComment = false
        var blockComment = false
        var index = 0
        while (index < code.length) {
            val current = code[index]
            val next = code.getOrNull(index + 1)
            if (lineComment) {
                if (current == '\n') lineComment = false
                index++
                continue
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false
                    index += 2
                } else index++
                continue
            }
            if (quote != null) {
                if (escaped) escaped = false
                else if (current == '\\') escaped = true
                else if (current == quote) quote = null
                index++
                continue
            }
            if (current == '/' && next == '/') {
                lineComment = true
                index += 2
                continue
            }
            if (current == '/' && next == '*') {
                blockComment = true
                index += 2
                continue
            }
            if (current == '\"' || current == '\'') quote = current
            else if (current == open) depth++
            else if (current == close && --depth < 0) return false
            index++
        }
        return quote == null && !blockComment && depth == 0
    }

    private fun containsTopLevelNativeDeclaration(code: String): Boolean {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var lineComment = false
        var blockComment = false
        var index = 0
        while (index < code.length) {
            val current = code[index]
            val next = code.getOrNull(index + 1)
            if (lineComment) {
                if (current == '\n') lineComment = false
                index++
                continue
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false
                    index += 2
                } else index++
                continue
            }
            if (quote != null) {
                if (escaped) escaped = false
                else if (current == '\\') escaped = true
                else if (current == quote) quote = null
                index++
                continue
            }
            if (current == '/' && next == '/') {
                lineComment = true
                index += 2
                continue
            }
            if (current == '/' && next == '*') {
                blockComment = true
                index += 2
                continue
            }
            if (current == '\"' || current == '\'') {
                quote = current
                index++
                continue
            }
            if (current == '{') depth++
            else if (current == '}') depth--
            else if (depth == 0 && code.regionMatches(index, "native", 0, 6)) {
                val before = code.getOrNull(index - 1)
                val after = code.getOrNull(index + 6)
                val beforeIsIdentifier = before?.let { Character.isJavaIdentifierPart(it) } == true
                val afterIsIdentifier = after?.let { Character.isJavaIdentifierPart(it) } == true
                if (!beforeIsIdentifier && !afterIsIdentifier) return true
            }
            index++
        }
        return false
    }
}
