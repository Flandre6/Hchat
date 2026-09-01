package h.Hchat.hooks.items.homesidepanel

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.ui.miuix.MiuixSettingsPage

/** OKK-style native drawer. Keeps the view tree cached to avoid reopen jank. */
internal class HomeSidePanelDrawer(
    private val activity: Activity,
    private val onNavigate: () -> Unit
) {
    private val density = activity.resources.displayMetrics.density
    private val panelWidth = minOf((activity.resources.displayMetrics.widthPixels * .78f).toInt(), dp(300))
    private val root = FrameLayout(activity)
    private val scrim = View(activity)
    private val panel = LinearLayout(activity)
    private val edge = View(activity)
    private var host: ViewGroup? = null
    private var enabled = false
    private var opened = false
    private var dragging = false
    private var downX = 0f
    private var lastX = 0f
    private var profileAvatar: ImageView? = null
    private var avatarBitmap: Bitmap? = null

    fun attachTo(parent: ViewGroup) {
        if (host === parent && root.parent === parent) return
        host = parent
        buildPanel()
        root.visibility = View.GONE
        parent.addView(root, ViewGroup.LayoutParams(-1, -1))
    }

    fun detach() {
        (root.parent as? ViewGroup)?.removeView(root)
        host = null
    }

    fun setAvatar(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        avatarBitmap = bitmap
        profileAvatar?.setImageBitmap(bitmap)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        root.visibility = if (value || opened) View.VISIBLE else View.GONE
        edge.visibility = if (value || opened) View.VISIBLE else View.GONE
        if (!value && opened) close(false)
    }

    fun toggle() { if (opened) close(true) else show(true) }

    fun show(animated: Boolean) {
        if (!enabled || opened) return
        opened = true
        root.visibility = View.VISIBLE
        edge.visibility = View.GONE
        scrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        if (!animated) {
            panel.translationX = 0f
            scrim.alpha = .42f
            return
        }
        panel.translationX = -panelWidth.toFloat()
        scrim.alpha = 0f
        panel.animate().translationX(0f).setDuration(280L).setInterpolator(PathInterpolator(.05f, .7f, .1f, 1f)).start()
        scrim.animate().alpha(.42f).setDuration(280L).start()
    }

    fun close(animated: Boolean) {
        if (!opened && !animated) return
        opened = false
        if (!animated) {
            panel.animate().cancel(); scrim.animate().cancel()
            panel.translationX = -panelWidth.toFloat(); scrim.alpha = 0f; scrim.visibility = View.GONE
            edge.visibility = if (enabled) View.VISIBLE else View.GONE
            root.visibility = if (enabled) View.VISIBLE else View.GONE
            return
        }
        panel.animate().translationX(-panelWidth.toFloat()).setDuration(180L).setInterpolator(PathInterpolator(.3f, 0f, .8f, .15f)).withEndAction {
            scrim.visibility = View.GONE
            edge.visibility = if (enabled) View.VISIBLE else View.GONE
            if (!enabled) root.visibility = View.GONE
        }.start()
        scrim.animate().alpha(0f).setDuration(180L).start()
    }

    private fun buildPanel() {
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(color(if (night()) "#1A1C1A" else "#F7F8F6"))
        panel.clipChildren = true
        panel.setPadding(0, 0, 0, dp(3))
        panel.addView(View(activity), LinearLayout.LayoutParams(-1, statusBarHeight()))
        panel.addView(buildProfile(), LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(8)
        })

        val scroll = ScrollView(activity).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(22)) }
        section(body, "快捷", true) { showShortcutPicker() }
        shortcutCard(body)
        section(body, "Hchat", false)
        menuCard(body, listOf(
            Row("⚙", "模块设置", "全部功能开关 · 配置") { MiuixSettingsPage.show(activity); onNavigate() },
            Row("🎨", "主题", "主界面背景与透明度") { MiuixSettingsPage.showFeature(activity, "background_beauty"); onNavigate() },
            Row("📍", "虚拟定位", "设置微信定位位置") { MiuixSettingsPage.showFeature(activity, "fake_location"); onNavigate() },
            Row("▢", "悬浮底栏", "底部导航栏样式") { MiuixSettingsPage.showFeature(activity, "custom_bottom_bar"); onNavigate() }
        ))
        section(body, "其它", false)
        menuCard(body, listOf(
            Row("💬", "帮助与反馈", "Telegram 群") { openUrl("https://t.me/Hchat_Group") },
            Row("ℹ", "关于", "Hchat 版本与开源信息") { MiuixSettingsPage.show(activity); onNavigate() }
        ))
        body.addView(TextView(activity).apply {
            text = "长按无 · 左滑关闭侧栏"; gravity = Gravity.CENTER; textSize = 11f
            setTextColor(themeSub()); setPadding(0, dp(14), 0, dp(6))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        })
        scroll.addView(body)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(View(activity).apply { setBackgroundColor(themeAccent()); alpha = .85f }, LinearLayout.LayoutParams(-1, dp(3)))

        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        root.addView(panel, FrameLayout.LayoutParams(panelWidth, -1, Gravity.START))
        root.addView(edge, FrameLayout.LayoutParams(dp(72), -1, Gravity.START))
        panel.translationX = -panelWidth.toFloat()
        scrim.setBackgroundColor(Color.BLACK)
        scrim.alpha = 0f; scrim.visibility = View.GONE
        scrim.setOnClickListener { close(true) }
        edge.setOnTouchListener { _, event -> onEdgeTouch(event) }
        panel.setOnTouchListener { _, event -> onPanelTouch(event) }
        root.isFocusable = true; root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, key, ev ->
            if (key == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP && opened) { close(true); true } else false
        }
    }

    private fun buildProfile(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(16), dp(18))
            background = rounded(if (night()) "#222822" else "#E5EFE6", 20, if (night()) "#2AFFFFFF" else "#1F000000")
            elevation = dp(2).toFloat()
        }
        val avatarWrap = FrameLayout(activity).apply { setPadding(dp(2), dp(2), dp(2), dp(2)); background = oval(themeAccent()) }
        val avatar = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = oval("#D0D0D0") }
        profileAvatar = avatar
        avatarBitmap?.let { avatar.setImageBitmap(it) }
        avatarWrap.addView(avatar, FrameLayout.LayoutParams(-1, -1)); card.addView(avatarWrap, LinearLayout.LayoutParams(dp(54), dp(54)))
        val text = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        text.addView(TextView(activity).apply { this.text = accountName(); textSize = 18f; setTextColor(themeTitle()); typeface = Typeface.DEFAULT_BOLD; maxLines = 1 })
        val signature = TextView(activity).apply {
            val shown = HomeSidePanelSettings.signatureTipShown(activity)
            this.text = if (shown) HomeSidePanelSettings.signature(activity) else "✎ 编辑签名 · ${HomeSidePanelSettings.signature(activity)}"
            if (!shown) HomeSidePanelSettings.markSignatureTipShown(activity)
            textSize = 12f; setTextColor(themeSub()); setPadding(dp(10), dp(4), dp(10), dp(4)); background = rounded(if (night()) "#1E241E" else "#D6E4D8", 12)
            setOnClickListener { showSignatureEditor(this) }
        }
        text.addView(signature, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        card.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        card.setOnClickListener { toggle() }
        return card
    }

    private fun shortcutCard(body: LinearLayout) {
        menuCard(body, HomeSidePanelSettings.shortcuts(activity).map { shortcut ->
            Row(shortcut.emoji, shortcut.title, shortcut.subtitle) {
                if (!HomeSidePanelPageLauncher.openShortcut(activity, shortcut)) toast("无法打开「${shortcut.title}」") else onNavigate()
                close(false)
            }
        })
    }

    private fun section(parent: LinearLayout, title: String, editable: Boolean, action: (() -> Unit)? = null) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), dp(2), dp(8)) }
        row.addView(View(activity).apply { background = rounded(themeAccent(), 2) }, LinearLayout.LayoutParams(dp(3), dp(12)).apply { rightMargin = dp(8) })
        row.addView(TextView(activity).apply { text = title; textSize = 12.5f; setTextColor(themeSub()); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = .04f }, LinearLayout.LayoutParams(0, -2, 1f))
        if (editable && action != null) row.addView(TextView(activity).apply { text = "✎"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(themeAccent()); background = oval(if (night()) "#227FBF90" else "#182F8A4E"); setOnClickListener { action() } }, LinearLayout.LayoutParams(dp(28), dp(28)))
        parent.addView(row)
    }

    private data class Row(val icon: String, val title: String, val subtitle: String, val click: () -> Unit)

    private fun menuCard(parent: LinearLayout, rows: List<Row>) {
        val card = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; background = rounded(if (night()) "#242724" else "#FFFFFF", 16, if (night()) "#1AFFFFFF" else "#12000000"); elevation = dp(2).toFloat() }
        rows.forEachIndexed { index, rowData ->
            if (index > 0) card.addView(View(activity).apply { setBackgroundColor(if (night()) color("#14FFFFFF") else color("#08000000")) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(62) })
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); isClickable = true; isFocusable = true; background = ripple(if (night()) "#242724" else "#FFFFFF"); setOnClickListener { rowData.click() } }
            row.addView(TextView(activity).apply { text = rowData.icon; gravity = Gravity.CENTER; textSize = 17f; background = rounded(if (night()) "#227FBF90" else "#182F8A4E", 11) }, LinearLayout.LayoutParams(dp(38), dp(38)))
            val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0) }
            labels.addView(TextView(activity).apply { text = rowData.title; textSize = 14.5f; setTextColor(themeTitle()); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
            labels.addView(TextView(activity).apply { text = rowData.subtitle; textSize = 11.5f; setTextColor(themeSub()); setPadding(0, dp(2), 0, 0) })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(activity).apply { text = "›"; textSize = 18f; setTextColor(themeSub()) })
            card.addView(row)
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun showSignatureEditor(target: TextView) {
        val input = EditText(activity).apply { setText(HomeSidePanelSettings.signature(activity)); setSingleLine(true); textSize = 15f; setTextColor(themeTitle()); background = rounded(if (night()) "#242724" else "#FFFFFF", 10); setPadding(dp(12), 0, dp(12), 0) }
        val box = dialogBox("侧栏签名", "显示在昵称下方，点击签名可随时修改", input)
        addDialogActions(box.first, box.second) { HomeSidePanelSettings.saveSignature(activity, input.text.toString()); target.text = input.text.toString().trim().ifEmpty { HomeSidePanelSettings.DEFAULT_SIGNATURE }; toast("签名已保存") }
    }

    private fun showShortcutPicker() {
        val selected = HomeSidePanelSettings.shortcuts(activity).toMutableList()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(16)); background = rounded(if (night()) "#1A1C1A" else "#F7F8F6", 20) }
        root.addView(TextView(activity).apply { text = "编辑快捷"; textSize = 18f; setTextColor(themeTitle()); typeface = Typeface.DEFAULT_BOLD })
        root.addView(TextView(activity).apply { text = "最多选择 ${HomeSidePanelSettings.MAX_SHORTCUTS} 项"; textSize = 12f; setTextColor(themeSub()); setPadding(0, dp(4), 0, dp(12)) })
        val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(root); setCanceledOnTouchOutside(true) }
        HomeSidePanelSettings.Shortcut.entries.forEach { shortcut ->
            val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(8), dp(10)); isClickable = true; background = ripple(if (night()) "#242724" else "#FFFFFF") }
            line.addView(TextView(activity).apply { text = shortcut.emoji; textSize = 18f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(34), dp(34)))
            line.addView(TextView(activity).apply { text = "${shortcut.title}\n${shortcut.subtitle}"; textSize = 14f; setTextColor(themeTitle()) }, LinearLayout.LayoutParams(0, -2, 1f))
            val check = TextView(activity).apply { textSize = 18f; setTextColor(themeAccent()) }
            line.addView(check, LinearLayout.LayoutParams(dp(28), dp(28)))
            fun update() { check.text = if (shortcut in selected) "✓" else "" }
            update()
            line.setOnClickListener {
                if (shortcut in selected) { if (selected.size > 1) selected.remove(shortcut) else toast("至少保留 1 个快捷") }
                else if (selected.size < HomeSidePanelSettings.MAX_SHORTCUTS) selected.add(shortcut) else toast("最多选择 ${HomeSidePanelSettings.MAX_SHORTCUTS} 项")
                update()
            }
            root.addView(line)
        }
        val actions = LinearLayout(activity).apply { gravity = Gravity.END; setPadding(0, dp(12), 0, 0) }
        actions.addView(TextView(activity).apply { text = "取消"; gravity = Gravity.CENTER; setPadding(dp(18), dp(10), dp(18), dp(10)); setOnClickListener { dialog.dismiss() } })
        actions.addView(TextView(activity).apply { text = "保存"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = rounded(themeAccent(), 10); setPadding(dp(18), dp(10), dp(18), dp(10)); setOnClickListener { HomeSidePanelSettings.saveShortcuts(activity, selected); dialog.dismiss(); rebuild() } }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        root.addView(actions)
        dialog.show(); dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * .86f).toInt(), -2)
    }

    private fun dialogBox(title: String, subtitle: String, input: View): Pair<LinearLayout, Dialog> {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(16)); background = rounded(if (night()) "#1A1C1A" else "#F7F8F6", 18) }
        root.addView(TextView(activity).apply { text = title; textSize = 18f; setTextColor(themeTitle()); typeface = Typeface.DEFAULT_BOLD })
        root.addView(TextView(activity).apply { text = subtitle; textSize = 12f; setTextColor(themeSub()); setPadding(0, dp(5), 0, dp(14)) })
        root.addView(input, LinearLayout.LayoutParams(-1, dp(48)))
        val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(root); setCanceledOnTouchOutside(true) }
        dialog.show(); dialog.window?.setBackgroundDrawableResource(android.R.color.transparent); dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * .82f).toInt(), -2)
        return root to dialog
    }

    private fun addDialogActions(root: LinearLayout, dialog: Dialog, save: () -> Unit) {
        val actions = LinearLayout(activity).apply { gravity = Gravity.END; setPadding(0, dp(14), 0, 0) }
        actions.addView(TextView(activity).apply { text = "取消"; setPadding(dp(18), dp(10), dp(18), dp(10)); setOnClickListener { dialog.dismiss() } })
        actions.addView(TextView(activity).apply { text = "保存"; setTextColor(Color.WHITE); background = rounded(themeAccent(), 10); setPadding(dp(18), dp(10), dp(18), dp(10)); setOnClickListener { save(); dialog.dismiss() } }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        root.addView(actions)
    }

    private fun rebuild() {
        (root.parent as? ViewGroup)?.removeView(root)
        root.removeAllViews()
        panel.removeAllViews()
        host?.let { attachTo(it); setEnabled(enabled) }
    }

    private fun onEdgeTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.rawX; lastX = downX; dragging = false; return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                if (!opened && dx > dp(8)) { opened = true; root.visibility = View.VISIBLE; panel.translationX = -panelWidth.toFloat(); scrim.visibility = View.VISIBLE; dragging = true }
                if (opened && (dragging || dx < -dp(8))) {
                    dragging = true
                    edge.visibility = View.GONE
                    panel.translationX = (panel.translationX + event.rawX - lastX).coerceIn(-panelWidth.toFloat(), 0f)
                    scrim.alpha = .42f * (1f + panel.translationX / panelWidth)
                    lastX = event.rawX
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (opened && dragging) { if (panel.translationX > -panelWidth * .55f) settleOpen() else close(true); dragging = false; return true }
            }
        }
        return true
    }

    private fun onPanelTouch(event: MotionEvent): Boolean {
        if (!opened) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.rawX; lastX = downX; dragging = false; return false }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                if (!dragging && dx < -dp(8)) dragging = true
                if (dragging) {
                    panel.translationX = (panel.translationX + event.rawX - lastX).coerceIn(-panelWidth.toFloat(), 0f)
                    scrim.alpha = .42f * (1f + panel.translationX / panelWidth)
                    lastX = event.rawX
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) { if (panel.translationX > -panelWidth * .55f) settleOpen() else close(true); dragging = false; return true }
            }
        }
        return false
    }

    private fun accountName(): String = WeChatApis.account()?.selfName()?.takeIf { it.isNotBlank() } ?: "我"

    private fun settleOpen() {
        opened = true
        edge.visibility = View.GONE
        scrim.visibility = View.VISIBLE
        panel.animate().translationX(0f).setDuration(200L).setInterpolator(PathInterpolator(.4f, 0f, .2f, 1f)).start()
        scrim.animate().alpha(.42f).setDuration(200L).start()
    }
    private fun openUrl(url: String) { runCatching { activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("无法打开链接") } }
    private fun toast(message: String) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
    private fun dp(value: Int): Int = (value * density + .5f).toInt()
    private fun statusBarHeight(): Int { val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android"); return if (id > 0) activity.resources.getDimensionPixelSize(id) else dp(28) }
    private fun night(): Boolean = (activity.resources.configuration.uiMode and 0x30) == 0x20
    private fun color(value: String): Int = Color.parseColor(value)
    private fun themeTitle(): Int = color(if (night()) "#F2F4F2" else "#1C1F1C")
    private fun themeSub(): Int = color(if (night()) "#9AA39A" else "#6B736C")
    private fun themeAccent(): Int = color(if (night()) "#7FBF90" else "#2F8A4E")
    private fun rounded(fill: String, radius: Int, stroke: String? = null): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(radius).toFloat(); setColor(color(fill)); stroke?.let { setStroke(dp(1), color(it)) } }
    private fun rounded(fill: Int, radius: Int): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(radius).toFloat(); setColor(fill) }
    private fun oval(fill: Int): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
    private fun oval(fill: String): GradientDrawable = oval(color(fill))
    private fun ripple(fill: String): RippleDrawable = RippleDrawable(ColorStateList.valueOf(if (night()) color("#33FFFFFF") else color("#1F000000")), rounded(fill, 16), null)
}
