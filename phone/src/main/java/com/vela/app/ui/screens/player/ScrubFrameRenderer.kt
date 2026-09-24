package com.vela.app.ui.screens.player

import android.graphics.Bitmap
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * 在 GPU 上把解码器输出的整帧缩成预览位图。
 *
 * 解码器直接渲染到这里的 SurfaceTexture，硬件保持自己的原生输出格式（高通为 UBWC），
 * 不再把整帧映射到 CPU 逐点采样。缩放、YUV 转 RGB、旋转和裁切由外部纹理采样器在一次
 * 绘制里完成，开销只和预览尺寸有关，与片源分辨率无关；10 bit 与 HDR 片源也能正常出图。
 *
 * 所有方法都必须在创建它的线程上调用，EGL 上下文一直挂在这条线程上。
 * 构造失败会自行清理并抛出异常。
 */
internal class ScrubFrameRenderer {
    /** 接收 SurfaceTexture 帧到达回调的线程。抽帧线程自己在阻塞等待，不能兼任。 */
    private val callbackThread = HandlerThread("vela-scrub-frame-cb").apply { start() }
    /** 解码器每送到 Surface 一帧就放一个许可。等待方按许可数去取纹理。 */
    private val framePermits = Semaphore(0)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    /** 当前离屏表面的边长，单位像素。预览长边超过它时重建。 */
    private var pbufferEdge = 0
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var texMatrixHandle = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var outputSurface: Surface? = null
    /** SurfaceTexture 给出的纹理变换，包含裁切和解码器设置的旋转。 */
    private val texMatrix = FloatArray(16)
    /** glReadPixels 的回读缓冲，按离屏表面大小分配一次后复用。 */
    private var pixels: ByteBuffer? = null
    private val positions = floatBuffer(QUAD_POSITIONS)
    private val texCoords = floatBuffer(QUAD_TEX_COORDS)

    /** 交给解码器 configure 的输出表面。 */
    val surface: Surface
        get() = requireNotNull(outputSurface) { "renderer released" }

    init {
        try {
            initEgl()
            ensurePbuffer(1)
            program = buildProgram()
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
            textureId = createExternalTexture()
            val texture = SurfaceTexture(textureId)
            texture.setOnFrameAvailableListener({ framePermits.release() }, Handler(callbackThread.looper))
            surfaceTexture = texture
            outputSurface = Surface(texture)
        } catch (error: Exception) {
            release()
            throw error
        }
    }

    /**
     * 在把一帧交给 Surface 之前调用，丢掉之前遗留的到达通知。
     */
    fun beginFrame() {
        framePermits.drainPermits()
    }

    /**
     * 等解码器把帧送到 Surface，然后缩放成预览位图。
     *
     * @param expectedPtsUs 这一帧的显示时间，单位微秒；用来跳过队列里遗留的旧帧
     * @param deadlineNs 放弃等待的 System.nanoTime 时刻
     * @param width 预览宽度，单位像素
     * @param height 预览高度，单位像素
     * @return 预览位图；超时或 GL 出错时为 null
     */
    fun render(expectedPtsUs: Long, deadlineNs: Long, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        ensurePbuffer(max(width, height))
        if (!awaitFrame(expectedPtsUs * 1_000L, deadlineNs)) return null
        val texture = surfaceTexture ?: return null
        texture.getTransformMatrix(texMatrix)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)
        positions.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(positionHandle)
        texCoords.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        val buffer = pixels ?: return null
        buffer.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return null
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    /**
     * 释放 Surface、EGL 资源和回调线程。之后不能再使用。
     */
    fun release() {
        outputSurface?.release()
        outputSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        pixels = null
        callbackThread.quitSafely()
    }

    /**
     * 等待目标帧到达并更新到纹理。
     *
     * 每拿到一个许可就取一次纹理；时间戳不符时继续把队列里的旧帧吃掉。
     * 解码器没有按预期回传时间戳时，超时前到达的最后一帧也算成功。
     *
     * @param expectedNs 目标帧时间戳，单位纳秒
     * @param deadlineNs 放弃等待的 System.nanoTime 时刻
     * @return 纹理里已经是可用画面时为 true
     */
    private fun awaitFrame(expectedNs: Long, deadlineNs: Long): Boolean {
        val texture = surfaceTexture ?: return false
        var consumed = false
        while (true) {
            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0L) break
            if (!framePermits.tryAcquire(remainingMs, TimeUnit.MILLISECONDS)) break
            texture.updateTexImage()
            consumed = true
            var timestamp = texture.timestamp
            while (timestamp != expectedNs) {
                texture.updateTexImage()
                val next = texture.timestamp
                if (next == timestamp) break
                timestamp = next
            }
            if (timestamp == expectedNs) return true
        }
        return consumed
    }

    /** 建立 EGL display 和 GLES 2 上下文。 */
    private fun initEgl() {
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize failed")
        }
        display = eglDisplay
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw IllegalStateException("no pbuffer EGL config")
        }
        val chosen = configs[0] ?: throw IllegalStateException("null EGL config")
        config = chosen
        val created = EGL14.eglCreateContext(
            eglDisplay,
            chosen,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        if (created == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed")
        context = created
    }

    /**
     * 保证离屏表面至少有 [edge] 边长，并让上下文在当前线程生效。
     *
     * @param edge 需要的最小边长，单位像素
     */
    private fun ensurePbuffer(edge: Int) {
        val needed = edge.coerceAtLeast(1)
        if (pbuffer != EGL14.EGL_NO_SURFACE && pbufferEdge >= needed) return
        val chosen = config ?: throw IllegalStateException("EGL not initialised")
        if (pbuffer != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer)
            pbuffer = EGL14.EGL_NO_SURFACE
        }
        val created = EGL14.eglCreatePbufferSurface(
            display,
            chosen,
            intArrayOf(EGL14.EGL_WIDTH, needed, EGL14.EGL_HEIGHT, needed, EGL14.EGL_NONE),
            0
        )
        if (created == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreatePbufferSurface failed")
        if (!EGL14.eglMakeCurrent(display, created, created, context)) {
            EGL14.eglDestroySurface(display, created)
            throw IllegalStateException("eglMakeCurrent failed")
        }
        pbuffer = created
        pbufferEdge = needed
        pixels = ByteBuffer.allocateDirect(needed * needed * 4).order(ByteOrder.nativeOrder())
    }

    /**
     * 编译并链接外部纹理着色器。
     *
     * @return GL program 句柄
     */
    private fun buildProgram(): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val created = GLES20.glCreateProgram()
        GLES20.glAttachShader(created, vertex)
        GLES20.glAttachShader(created, fragment)
        GLES20.glLinkProgram(created)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(created, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(created)
            GLES20.glDeleteProgram(created)
            throw IllegalStateException("program link failed: $log")
        }
        return created
    }

    /**
     * 生成外部纹理并设置线性采样。
     *
     * @return 纹理句柄
     */
    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private companion object {
        /** 覆盖整个视口的三角带顶点。 */
        private val QUAD_POSITIONS = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )

        /**
         * 顶点对应的纹理坐标。
         *
         * 故意上下颠倒：glReadPixels 从底行开始回读，这样读出来的字节顺序正好是自上而下的位图。
         */
        private val QUAD_TEX_COORDS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        /**
         * 编译一个着色器。
         *
         * @param type GL_VERTEX_SHADER 或 GL_FRAGMENT_SHADER
         * @param source GLSL 源码
         * @return 着色器句柄
         */
        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source.trimIndent())
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("shader compile failed: $log")
            }
            return shader
        }

        /** 把浮点数组放进本机字节序的直接缓冲。 */
        private fun floatBuffer(values: FloatArray): FloatBuffer {
            return ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(values)
                .apply { position(0) }
        }
    }
}
