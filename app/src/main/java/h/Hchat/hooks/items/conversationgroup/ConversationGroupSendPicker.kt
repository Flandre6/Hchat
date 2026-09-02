package h.Hchat.hooks.items.conversationgroup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.utils.HLog
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object ConversationGroupSendPicker {
    private const val TAG = "[Hchat:ConversationGroup]"
    private const val REQUEST_CODE_START = 0x7610
    private const val REQUEST_CODE_END = 0x76ff
    private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private val nextRequestCode = AtomicInteger(REQUEST_CODE_START)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val hookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val destroyHookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    fun launch(
        activity: Activity,
        mimeType: String,
        chooserTitle: String,
        callback: (PickedFile?) -> Unit
    ) {
        hookActivityHierarchy(activity.javaClass)
        val requestCode = allocateRequestCode()
        pending[requestCode] = Pending(WeakReference(activity), callback)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        runCatching { activity.startActivityForResult(intent, requestCode) }
            .onFailure { firstError ->
                val fallback = Intent.createChooser(
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = mimeType
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    chooserTitle
                )
                runCatching { activity.startActivityForResult(fallback, requestCode) }
                    .onFailure { secondError ->
                        pending.remove(requestCode)?.deliver(null)
                        HLog.e("$TAG 启动发送文件选择器失败: ${secondError.message}", secondError)
                        HLog.e("$TAG 系统文档选择器错误: ${firstError.message}", firstError)
                    }
            }
    }

    private fun hookActivityHierarchy(activityClass: Class<*>) {
        var current: Class<*>? = activityClass
        while (current != null && Activity::class.java.isAssignableFrom(current)) {
            hookActivityResult(current)
            hookActivityDestroy(current)
            current = current.superclass
        }
    }

    private fun hookActivityResult(clazz: Class<*>) {
        if (!hookedClasses.add(clazz)) return
        runCatching {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    val request = pending[requestCode] ?: return
                    val activity = request.activity.get()
                    if (activity == null) {
                        pending.remove(requestCode, request)
                        return
                    }
                    if (param.thisObject !== activity || !pending.remove(requestCode, request)) return
                    val resultCode = param.args.getOrNull(1) as? Int ?: Activity.RESULT_CANCELED
                    val data = param.args.getOrNull(2) as? Intent
                    val uri = data?.data
                    if (resultCode != Activity.RESULT_OK || uri == null) {
                        request.deliver(null)
                        return
                    }
                    takeReadPermission(activity, data, uri)
                    request.deliver(PickedFile(uri, displayName(activity, uri)))
                }
            })
        }.onFailure { hookedClasses.remove(clazz) }
    }

    fun materialize(context: Context, picked: PickedFile): MaterializedFile {
        val root = File(context.cacheDir, "hchat_conversation_group_send").apply { mkdirs() }
        cleanup(root)
        val target = uniqueFile(root, picked.displayName)
        context.contentResolver.openInputStream(picked.uri)?.use { input ->
            FileOutputStream(target, false).use { output -> input.copyTo(output) }
        } ?: error("无法读取所选文件")
        require(target.isFile && target.length() > 0L) { "所选文件内容为空" }
        return MaterializedFile(target.absolutePath, picked.displayName)
    }

    private fun takeReadPermission(activity: Activity, data: Intent, uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if ((flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
        }.getOrNull().orEmpty()
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
        return sanitizeName(queried.ifBlank { fallback }.ifBlank { "file_${System.currentTimeMillis()}" })
    }

    private fun sanitizeName(value: String): String {
        return value.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim()
            .take(180)
            .ifBlank { "file_${System.currentTimeMillis()}" }
    }

    private fun uniqueFile(root: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").takeIf(String::isNotBlank)
        repeat(1000) { index ->
            val suffix = if (index == 0) "" else "_$index"
            val candidateName = buildString {
                append(base)
                append(suffix)
                if (extension != null) append('.').append(extension)
            }
            val candidate = File(root, candidateName)
            if (!candidate.exists()) return candidate
        }
        return File(root, "${System.currentTimeMillis()}_$name")
    }

    private fun cleanup(root: File) {
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        root.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }

    private fun hookActivityDestroy(clazz: Class<*>) {
        if (!destroyHookedClasses.add(clazz)) return
        runCatching {
            XposedBridge.hookAllMethods(clazz, "onDestroy", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    pending.entries.forEach { entry ->
                        val owner = entry.value.activity.get()
                        if (owner == null || owner === activity) pending.remove(entry.key, entry.value)
                    }
                }
            })
        }.onFailure { destroyHookedClasses.remove(clazz) }
    }

    private fun allocateRequestCode(): Int {
        repeat(REQUEST_CODE_END - REQUEST_CODE_START + 1) {
            val candidate = nextRequestCode.updateAndGet { current ->
                if (current >= REQUEST_CODE_END) REQUEST_CODE_START else current + 1
            }
            if (!pending.containsKey(candidate)) return candidate
        }
        val oldest = pending.keys.minOrNull() ?: REQUEST_CODE_START
        pending.remove(oldest)?.deliver(null)
        return oldest
    }

    private fun Intent.preferSystemDocumentsUi(context: Context): Intent {
        for (packageName in listOf("com.google.android.documentsui", "com.android.documentsui")) {
            val copy = Intent(this).setPackage(packageName)
            if (runCatching { context.packageManager.queryIntentActivities(copy, 0) }
                    .getOrDefault(emptyList()).isNotEmpty()
            ) {
                setPackage(packageName)
                break
            }
        }
        return this
    }

    private data class Pending(
        val activity: WeakReference<Activity>,
        val callback: (PickedFile?) -> Unit
    ) {
        fun deliver(result: PickedFile?) {
            val owner = activity.get() ?: return
            owner.runOnUiThread {
                if (!owner.isFinishing && !owner.isDestroyed) callback(result)
            }
        }
    }

    data class PickedFile(val uri: Uri, val displayName: String)

    data class MaterializedFile(val path: String, val displayName: String)
}
