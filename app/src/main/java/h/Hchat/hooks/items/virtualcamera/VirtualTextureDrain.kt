package h.Hchat.hooks.items.virtualcamera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import h.Hchat.utils.HLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Camera output sink backed by an EGL texture. Frames are consumed and discarded. */
internal class VirtualTextureDrain(private val threadName: String) {
    private val thread = HandlerThread(threadName).apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    @Volatile private var released = false

    init {
        val ready = CountDownLatch(1)
        handler.post {
            runCatching { initialize() }
                .onFailure { HLog.e("[Hchat:VirtualCamera] 创建虚拟输出 Surface 失败", it) }
            ready.countDown()
        }
        ready.await(500, TimeUnit.MILLISECONDS)
    }

    fun surfaceTexture(): SurfaceTexture {
        return texture ?: throw IllegalStateException("virtual SurfaceTexture is unavailable")
    }

    fun surface(): Surface {
        return surface ?: throw IllegalStateException("virtual Surface is unavailable")
    }

    fun isValid(): Boolean = !released && surface?.isValid == true

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        )
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "eglChooseConfig failed"
        }
        val config = configs[0] ?: error("EGLConfig unavailable")
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        pbuffer = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent failed" }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        val localTexture = SurfaceTexture(textureId)
        localTexture.setDefaultBufferSize(1280, 720)
        localTexture.setOnFrameAvailableListener({ incoming ->
            if (!released) runCatching { incoming.updateTexImage() }
        }, handler)
        texture = localTexture
        surface = Surface(localTexture)
    }

    fun release() {
        if (released) return
        released = true
        handler.post {
            runCatching {
                surface?.release()
                surface = null
                texture?.release()
                texture = null
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }.onFailure { HLog.e("[Hchat:VirtualCamera] 释放虚拟输出失败", it) }
            thread.quitSafely()
        }
    }
}
