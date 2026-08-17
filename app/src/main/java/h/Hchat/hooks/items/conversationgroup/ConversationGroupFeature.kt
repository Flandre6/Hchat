package h.Hchat.hooks.items.conversationgroup

import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext

class ConversationGroupFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "聊天分组"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ConversationGroupSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.BRIDGE) {
            ConversationGroupRuntime.install(context)
        }
    }

    companion object {
        const val ID = "conversation_group"
    }
}
