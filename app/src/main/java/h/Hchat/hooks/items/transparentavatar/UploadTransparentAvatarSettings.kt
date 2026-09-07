package h.Hchat.hooks.items.transparentavatar

import android.content.Context
import h.Hchat.preferences.HchatStorage

object UploadTransparentAvatarSettings {
    const val PREFS_NAME = "Hchat_upload_transparent_avatar_config"
    const val KEY_ENABLE = "upload_transparent_avatar_enable"
    const val DEFAULT_ENABLE = false

    fun preferences(context: Context) = HchatStorage.preferences(context, PREFS_NAME)
}
