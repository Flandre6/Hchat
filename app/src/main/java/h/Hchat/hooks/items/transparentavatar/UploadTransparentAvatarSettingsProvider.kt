package h.Hchat.hooks.items.transparentavatar

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class UploadTransparentAvatarSettingsProvider : SimpleFeatureSettingsProvider(
    UploadTransparentAvatarFeature.ID,
    "上传透明头像",
    "上传个人头像时保留透明背景",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
