package h.Hchat.hooks.items.virtualcamera

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import h.Hchat.utils.HLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class VirtualCameraSurfaceOutput(
    private val target: Surface,
    private val mediaPath: () -> String?,
    private val logTag: String,
    private val ownsTarget: Boolean = false
) {
    private var player: MediaPlayer? = null
    private var imageRenderer: StaticImageRenderer? = null
    @Volatile private var released = false

    fun start() {
        if (released || !target.isValid) return
        val path = mediaPath() ?: return
        if (!File(path).isFile) return
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            imageRenderer = StaticImageRenderer(target, path, logTag).also { it.start() }
        } else {
            startVideo(path)
        }
    }

    private fun startVideo(path: String) {
        runCatching {
            MediaPlayer().also { mediaPlayer ->
                player = mediaPlayer
                mediaPlayer.setSurface(target)
                mediaPlayer.setDataSource(path)
                mediaPlayer.isLooping = true
                mediaPlayer.setVolume(0f, 0f)
                mediaPlayer.setOnPreparedListener { prepared ->
                    if (!released && target.isValid) prepared.start()
                }
                mediaPlayer.setOnErrorListener { _, what, extra ->
                    HLog.e("$logTag 视频输出失败: what=$what extra=$extra")
                    true
                }
                mediaPlayer.prepareAsync()
            }
        }.onFailure { HLog.e("$logTag 无法启动视频输出: $path", it) }
    }

    fun release() {
        if (released) return
        released = true
        imageRenderer?.release()
        imageRenderer = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        if (ownsTarget) runCatching { target.release() }
    }
}

private class StaticImageRenderer(
    private val target: Surface,
    private val path: String,
    private val logTag: String
) {
    private val thread = HandlerThread("Hchat-VirtualImage").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var bitmap: Bitmap? = null
    @Volatile private var released = false

    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }
    private val textureCoords: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)); position(0)
        }

    fun start() {
        handler.post {
            runCatching {
                initialize()
                draw()
            }.onFailure {
                HLog.e("$logTag 图片输出初始化失败", it)
                release()
            }
        }
    }

    private fun initialize() {
        bitmap = VirtualCameraMedia.decodeFrame(path, 1920, 1080) ?: error("decode bitmap failed")
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0)
        val config = configs[0] ?: error("EGLConfig unavailable")
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        window = EGL14.eglCreateWindowSurface(display, config, target, intArrayOf(EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, window, window, context))
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun draw() {
        if (released || !target.isValid || program == 0) return
        GLES20.glViewport(0, 0, querySurface(EGL14.EGL_WIDTH), querySurface(EGL14.EGL_HEIGHT))
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, textureCoords)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, window, System.nanoTime())
        EGL14.eglSwapBuffers(display, window)
        if (!released) handler.postDelayed(::draw, 33L)
    }

    private fun querySurface(attribute: Int): Int {
        val value = IntArray(1)
        EGL14.eglQuerySurface(display, window, attribute, value, 0)
        return value[0].coerceAtLeast(1)
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertexShader)
        GLES20.glAttachShader(result, fragmentShader)
        GLES20.glLinkProgram(result)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { GLES20.glGetProgramInfoLog(result) }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    fun release() {
        if (released) return
        released = true
        handler.post {
            runCatching {
                bitmap?.recycle()
                bitmap = null
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (program != 0) GLES20.glDeleteProgram(program)
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }.onFailure { HLog.e("$logTag 释放图片输出失败", it) }
            thread.quitSafely()
        }
    }

    companion object {
        private const val VERTEX_SHADER = "attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main(){ gl_Position=aPosition; vTexCoord=aTexCoord; }"
        private const val FRAGMENT_SHADER = "precision mediump float; varying vec2 vTexCoord; uniform sampler2D uTexture; void main(){ gl_FragColor=texture2D(uTexture,vTexCoord); }"
    }
}
