package h.Hchat.hooks.items.virtualcamera

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class VirtualCameraSettingsProvider : SimpleFeatureSettingsProvider(
    VirtualCameraFeature.ID,
    "虚拟摄像头（实验）",
    "用本地图片或视频替换普通微信相机画面，自动绕开身份认证场景",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
