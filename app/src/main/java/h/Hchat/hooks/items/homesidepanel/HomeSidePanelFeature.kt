package h.Hchat.hooks.items.homesidepanel

import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class HomeSidePanelFeature : BaseFeature() {
    private var runtime: HomeSidePanelRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "首页侧边栏"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(
            HomeSidePanelSettingsProvider()
        )
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val installed = HomeSidePanelRuntime(context).also { it.install() }
        runtime = installed
        h.Hchat.hooks.api.core.WeChatApis.lifecycle()?.let { lifecycle ->
            trackSubscription(lifecycle.subscribe { event -> installed.onActivityEvent(event) })
        }
        h.Hchat.hooks.api.core.WeChatApis.currentActivity()?.currentActivity()
            ?.let(installed::attachIfSupported)
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    companion object {
        const val ID = "home_side_panel"
    }
}

private class HomeSidePanelSettingsProvider : SimpleFeatureSettingsProvider(
    HomeSidePanelFeature.ID,
    "首页侧边栏",
    "从微信首页左缘右滑，打开快捷面板",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
