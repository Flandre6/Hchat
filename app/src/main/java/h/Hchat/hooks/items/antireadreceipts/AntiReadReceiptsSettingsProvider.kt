package h.Hchat.hooks.items.antireadreceipts

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class AntiReadReceiptsSettingsProvider : SimpleFeatureSettingsProvider(
    AntiReadReceiptsFeature.ID,
    "反已读追踪",
    "拦截视频号缩略图中的追踪像素请求",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
