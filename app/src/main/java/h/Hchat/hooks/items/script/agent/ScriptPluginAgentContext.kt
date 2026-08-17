package h.Hchat.hooks.items.script.agent

object ScriptPluginAgentContext {
    fun modelMessagesForTurn(
        messages: List<ScriptPluginAgentChatMessage>,
        currentTurnId: String
    ): List<ScriptPluginAgentChatMessage> {
        return messages.mapNotNull { message ->
            if (message.role != "tool" || message.turnId == currentTurnId) {
                return@mapNotNull message
            }
            val retainedEvents = message.toolEvents.filterNot { event ->
                event.kind == "workspace" || event.protocolName.startsWith("hchat_workspace_")
            }
            when {
                retainedEvents.isEmpty() -> null
                retainedEvents.size == message.toolEvents.size -> message
                else -> message.copy(toolEvents = retainedEvents)
            }
        }
    }

    fun estimateTokens(
        summary: String,
        messages: List<ScriptPluginAgentChatMessage>,
        draft: ScriptPluginAgentDraft?,
        nativeToolHistory: String = "",
        protocolTranscript: String = ""
    ): Int {
        val hasProtocolTranscript = protocolTranscript.isNotBlank() &&
            ScriptPluginAgentProtocolTranscript.isValid(protocolTranscript)
        var characters = if (hasProtocolTranscript) {
            protocolTranscript.length
        } else {
            summary.length + nativeToolHistory.length
        }
        val pendingMessages = if (hasProtocolTranscript) {
            val pendingUserIndex = messages.indexOfLast { message ->
                message.role == "user" &&
                    !ScriptPluginAgentProtocolTranscript.containsMessage(protocolTranscript, message.id)
            }
            if (pendingUserIndex >= 0) messages.drop(pendingUserIndex) else emptyList()
        } else {
            messages
        }
        pendingMessages.forEach { message ->
            characters += message.content.length + message.reasoning.length + message.diff.length
            characters += message.quotedMessage?.content?.length ?: 0
            if (!hasProtocolTranscript && nativeToolHistory.isBlank()) message.toolEvents.forEach { event ->
                characters += event.name.length + event.arguments.length + event.result.length + event.diff.length
            }
            message.attachments.forEach { attachment ->
                characters += attachment.name.length + if (attachment.mimeType.startsWith("image/")) {
                    4_000
                } else {
                    attachment.size.coerceIn(1_000L, 512L * 1024L).toInt()
                }
            }
        }
        if (draft != null) {
            characters += draft.pluginId.length + draft.pluginName.length + draft.summary.length + 256
        }
        return (characters / 4).coerceAtLeast(1)
    }
}
