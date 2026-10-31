package com.vela.app.player.mpv

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import com.vela.app.player.vr.VrFlattenFilter
import com.vela.app.player.vr.VrLayout
import com.vela.data.model.MediaStream
import com.vela.player.core.PlayerUtils
import com.vela.player.preferences.PlayerPreferences
import com.vela.player.video.HdrCapabilityManager
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVLib.MpvEvent
import `is`.xyz.mpv.MPVLib.MpvFormat
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.Locale

class MpvPlayerController(
    context: Context,
    private val hardwareDecoding: String,
    private val videoOutput: String,
    private val audioOutput: String,
    listener: Listener
) : MPVLib.EventObserver {

    companion object {
        private const val SUBTITLE_LOG_TAG = "JellyCine-Sub"
        private const val DOLBY_LOG_TAG = "MpvDolby"
        private const val COLOR_LOG_TAG = "JellyCine-Color"
        private const val VR_LOG_TAG = "JellyCine-VR"
        private const val VR_LOOK_RELOAD_MS = 32L
        private const val PERFORMANCE_LOG_TAG = "Vela-Playback"
        private const val PERFORMANCE_LOG_INTERVAL_MS = 15_000L

        /**
         * mpv 是进程级单例（[MPVLib] 持有唯一句柄）。创建要初始化 GPU 上下文，销毁要等解码/网络线程退出，
         * 都可能阻塞上百毫秒，因此统一放到这条单线程上串行执行：既不卡主线程（转场、旋转保持流畅），
         * 又保证新实例一定在上一个实例销毁完成之后才创建。
         *
         * 构造 [MpvPlayerController] 必须在此 dispatcher 上进行。
         */
        private val lifecycleExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mpv-lifecycle")
        }
        val LifecycleDispatcher: CoroutineDispatcher = lifecycleExecutor.asCoroutineDispatcher()
    }

    interface Listener {
        fun onBuffering()
        fun onReady()
        fun onEnded()
    }

    private val appContext = context.applicationContext
    @Volatile
    private var released = false
    @Volatile
    private var ready = false
    @Volatile
    private var pausedForCache = false
    private var restartStartedAtMs = 0L
    // 只在 mpv 事件线程读写；按单调时钟限流，seek 不会触发日志风暴。
    private var lastPerformanceLogMs = 0L
    // 以下三项在 mpv 事件线程更新，主线程和预览预生成的 IO 协程都会读取。
    @Volatile
    private var durationMs: Long = 0L
    @Volatile
    private var positionMs: Long = 0L
    @Volatile
    private var cacheAheadMs: Long = 0L
    @Volatile
    private var sourceBitrate: Int? = null
    private var playWhenReady = true
    private var pendingSubtitleUrls: List<String> = emptyList()
    private var pendingSelectedSubtitleUrl: String? = null
    private var pendingSubtitleTrackId: String? = null
    private var preferFastSeek = false
    private var pendingRemoteHttpPlayback = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var subtitleFontFamily: String = "sans-serif"
    private val playerPreferences = PlayerPreferences(context.applicationContext)
    private var lastDolbyRuntimePath: String? = null
    private var colorPolicyStreams: List<MediaStream>? = null
    private var requestedHwdec: String = hardwareDecoding
    /** 当前片源是否超高像素；见 [MpvRenderLoad]。 */
    @Volatile
    private var heavySource = false
    /** 保护 [fastRenderApplied] 及随之写入的缩放/去色带选项。 */
    private val renderQualityLock = Any()
    /** 最近一次写给 mpv 的是否为轻量渲染；尚未写过时为 null。 */
    private var fastRenderApplied: Boolean? = null
    @Volatile
    private var currentSpeed = 1.0
    private var vrShaderActive = false
    /** gpu-next 上视角走动态 uniform；gpu 上视角烘焙进 #define，需换文件重载。 */
    private var vrDynamicLook = false
    /**
     * VR 转平面期间临时改用 gpu-next：vo=gpu 不支持动态参数，每次视角变化都要写新着色器并整段重编译，
     * 拖动时约 30 次/秒，渲染线程编译导致掉帧，松手后还有缓存写盘的卡顿。关闭转平面后恢复用户设置的 vo。
     */
    private var vrForcedGpuNext = false
    /** Surface 已挂上；未挂时只记下要用的 vo，由 [attachSurface] 生效，避免 mpv 在无窗口时建 vo。 */
    private var surfaceAttached = false
    /** 当前挂载的 Surface，用于向系统声明视频帧率；见 [applySurfaceFrameRate]。 */
    @Volatile
    private var attachedSurface: Surface? = null
    /** 片源帧率（mpv `container-fps`），未知时为 0。 */
    @Volatile
    private var videoFps = 0.0
    /** 识别容器 pts 损坏的片源；访问须持有自身锁（load 在调用线程，日志在 mpv 事件线程）。 */
    private val brokenPtsDetector = MpvBrokenPtsDetector()
    private val ptsLogObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            onMpvLog(prefix, text)
        }
    }

    /** 当前应使用的 vo：转平面期间为 gpu-next，否则为用户设置。 */
    private val effectiveVideoOutput: String
        get() = if (vrForcedGpuNext) PlayerPreferences.MPV_VIDEO_OUTPUT_GPU_NEXT else videoOutput
    private var lastVrShaderSource: String? = null
    private val vrLookHandler = Handler(Looper.getMainLooper())
    private var vrLookScheduled = false
    private val applyPendingVrLook = Runnable {
        vrLookScheduled = false
        val look = pendingVrLook
        pendingVrLook = null
        look?.let(::applyVrLook)
    }
    private var pendingVrLook: VrLook? = null
    private val vrShaderTemplate: String? by lazy {
        runCatching {
            appContext.assets.open(VrFlattenFilter.SHADER_ASSET).bufferedReader().use { it.readText() }
        }.onFailure { error ->
            Log.e(VR_LOG_TAG, "failed to read VR flatten shader", error)
        }.getOrNull()
    }
    @Volatile
    private var listener: Listener = listener

    val isPlaying: Boolean
        get() = ready && playWhenReady && !pausedForCache

    val currentPosition: Long
        get() = positionMs

    val duration: Long
        get() = durationMs

    val bufferedPosition: Long
        get() = (positionMs + cacheAheadMs).coerceAtLeast(positionMs)

    init {
        MPVLib.create(appContext)
        configureMpv()
        MPVLib.init()
        MPVLib.addObserver(this)
        MPVLib.addLogObserver(ptsLogObserver)
        MPVLib.observeProperty("time-pos", MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("demuxer-cache-duration", MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("eof-reached", MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("container-fps", MpvFormat.MPV_FORMAT_DOUBLE)
    }

    fun load(
        url: String,
        requestHeaders: Map<String, String>,
        subtitleUrls: List<String>,
        audioTrackId: String?,
        subtitleTrackId: String?,
        selectedSubtitleUrl: String?,
        startPositionMs: Long?,
        startPlayback: Boolean,
        opticalDiscPlayback: Boolean,
        remoteHttpPlayback: Boolean = false,
        bitrate: Int? = null
    ) {
        if (released) return
        sourceBitrate = bitrate
        ready = false
        pausedForCache = false
        positionMs = startPositionMs?.coerceAtLeast(0L) ?: 0L
        cacheAheadMs = 0L
        restartStartedAtMs = SystemClock.elapsedRealtime()
        playWhenReady = startPlayback
        preferFastSeek = opticalDiscPlayback
        pendingRemoteHttpPlayback = remoteHttpPlayback
        // 起播和 seek 使用同一个预缓冲门槛；必须在 loadfile 前生效。
        applyCachePolicy(asOptions = false)
        pendingSubtitleUrls = subtitleUrls
        pendingSelectedSubtitleUrl = selectedSubtitleUrl
        pendingSubtitleTrackId = subtitleTrackId?.takeUnless { it == "no" }
        lastDolbyRuntimePath = null
        clearVrFlattenShader()
        resetBrokenPtsRepair()
        MPVLib.setPropertyBoolean("pause", true)
        listener.onBuffering()
        applyRemoteStreamOptions(remoteHttpPlayback)
        applyHttpRequestHeaders(requestHeaders)
        applyColorHwdec()
        applyDolbyDecodeOptions(asOptions = false)
        val needsEmbeddedSubtitleProbe =
            selectedSubtitleUrl == null && pendingSubtitleTrackId != null
        if (needsEmbeddedSubtitleProbe && !remoteHttpPlayback) {
            // 该选项是每个媒体文件的运行时属性；只写初始化 option 不会影响本次 loadfile。
            setMpv("demuxer-lavf-probe-info", "yes")
        }
        val loadOptions = buildList {
            // 直接从续播点加载，避免先缓冲片头，再在 FILE_LOADED 后重新 seek 和回溯字幕。
            add("start=${positionMs / 1000.0}")
            audioTrackId?.let { add("aid=$it") }
            if (selectedSubtitleUrl == null) {
                subtitleTrackId?.let { add("sid=$it") }
            }
            if (opticalDiscPlayback) {
                add("hr-seek=no")
                add("stream-buffer-size=1MiB")
            }
        }
        val loadCommand = arrayOf("loadfile", url, "replace", "-1", loadOptions.joinToString(","))
        MPVLib.command(loadCommand)
        applyCachePolicy(asOptions = false)
        Log.i(
            SUBTITLE_LOG_TAG,
            "load sid=$subtitleTrackId selectedUrl=${MPVPlayer.redactPlaybackSecret(selectedSubtitleUrl)} " +
                "external=${subtitleUrls.size} remote=$remoteHttpPlayback headers=${requestHeaders.keys.sorted()}"
        )
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        if (released) return
        MPVLib.attachSurface(surface)
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.setOptionString("vo", effectiveVideoOutput)
        surfaceAttached = true
        attachedSurface = surface
        applySurfaceFrameRate()
        if (width > 0 && height > 0) {
            surfaceWidth = width
            surfaceHeight = height
            MPVLib.setPropertyString("android-surface-size", "${width}x$height")
            applySubtitleLayout()
            applyVrAspect()
        }
    }

    fun resizeSurface(width: Int, height: Int) {
        if (!released && width > 0 && height > 0) {
            surfaceWidth = width
            surfaceHeight = height
            MPVLib.setPropertyString("android-surface-size", "${width}x$height")
            applySubtitleLayout()
            applyVrAspect()
        }
    }

    /**
     * VR 转平面时让画面铺满播放区域（与 iOS 版一致）：着色器按输出尺寸（`OUTPUT.w/h`）渲染视口，
     * 但 mpv 仍按源视频宽高比摆放画面，竖屏时只剩中间一条。把显示宽高比覆盖为播放区域的宽高比即可铺满；
     * 关闭 VR 时恢复按源视频宽高比（"no"）。
     */
    private fun applyVrAspect() {
        if (released) return
        val aspect = if (vrShaderActive && surfaceWidth > 0 && surfaceHeight > 0) {
            "%.6f".format(Locale.US, surfaceWidth.toDouble() / surfaceHeight)
        } else {
            "no"
        }
        setMpv("video-aspect-override", aspect)
    }

    fun screenshotToFile(path: String) {
        if (released) return
        MPVLib.command(arrayOf("screenshot-to-file", path, "subtitles"))
    }

    /** 画面顺时针旋转角度（0 / 90 / 180 / 270）；mpv 按旋转后的宽高比适配窗口，字幕不随画面旋转。 */
    fun setVideoRotation(degrees: Int) {
        if (released) return
        setMpv("video-rotate", degrees.toString())
    }

    fun setZoomMode(enabled: Boolean) {
        if (released) return
        setMpv("panscan", if (enabled) "1" else "0")
        applySubtitleWindowMargins()
    }

    fun applySubtitlePreferences() {
        if (released) return
        setMpv("sub-visibility", "yes")
        val compatible = playerPreferences.isSubtitleAssCompatible()
        // 非兼容模式剥掉 ASS 样式按纯文本渲染：字幕文件的 WrapStyle=2（不自动换行）无法通过样式覆盖改掉，
        // 长句会冲出屏幕；纯文本渲染使用 mpv 默认的智能换行，并完全套用应用的字体、字号与位置。
        setMpv("sub-ass", if (compatible) "yes" else "no")
        val edgeType = playerPreferences.getSubtitleEdgeType()
        val backgroundColor = playerPreferences.getSubtitleBackgroundColor()
        setMpv("sub-ass-override", PlayerPreferences.mpvAssOverride(compatible))
        applySubtitleWindowMargins()
        setMpv(
            "sub-ass-force-style",
            PlayerPreferences.mpvAssForceStyle(
                fontFamily = subtitleFontFamily,
                edgeType = edgeType,
                backgroundColor = backgroundColor,
                compatible = compatible
            )
        )
        MPVLib.setPropertyDouble(
            "sub-delay",
            playerPreferences.getSubtitleDelayMs() / 1000.0
        )
        applySubtitleLayout()
        if (compatible) {
            return
        }
        setMpv("sub-bold", "no")
        setMpv("sub-italic", "no")
        setMpv("sub-blur", "0")
        setMpv(
            "sub-color",
            mpvColor(
                color = playerPreferences.getSubtitleTextColor(),
                opacityPercent = playerPreferences.getSubtitleTextOpacityPercent()
            )
        )
        setMpv(
            "sub-back-color",
            mpvBackgroundColor(backgroundColor)
        )
        applySubtitleEdge(edgeType)
    }

    fun detachSurface() {
        if (released) return
        surfaceAttached = false
        clearSurfaceFrameRate()
        MPVLib.setOptionString("vo", "null")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.detachSurface()
    }

    fun play() {
        if (released) return
        playWhenReady = true
        MPVLib.setPropertyBoolean("pause", false)
    }

    fun pause() {
        if (released) return
        playWhenReady = false
        MPVLib.setPropertyBoolean("pause", true)
    }

    fun grabThumbnail(dimension: Int): Bitmap? {
        if (released) return null
        return MPVLib.grabThumbnail(dimension)
    }

    fun seekTo(positionMs: Long, exact: Boolean = true) {
        if (released) return
        this.positionMs = positionMs.coerceAtLeast(0L)
        ready = false
        cacheAheadMs = 0L
        restartStartedAtMs = SystemClock.elapsedRealtime()
        listener.onBuffering()
        val flags = MpvPlaybackTuning.seekFlags(exact, preferFastSeek)
        Log.i(PERFORMANCE_LOG_TAG, "seek targetMs=${this.positionMs} flags=$flags")
        MPVLib.command(
            arrayOf("seek", (this.positionMs / 1000.0).toString(), flags)
        )
        if (playWhenReady) {
            MPVLib.setPropertyBoolean("pause", false)
        }
    }

    fun setHardwareDecoding(mode: String) {
        if (released) return
        requestedHwdec = mode
        applyColorHwdec()
    }

    fun applyStreamColorPolicy(mediaStreams: List<MediaStream>?) {
        if (released) return
        colorPolicyStreams = mediaStreams
        applyColorHwdec()
        applyDolbyDecodeOptions(asOptions = false)
        applyRenderLoad(mediaStreams)
    }

    /** 按片源像素量切换缩放/去色带/插帧；超高像素片源改走轻量路径，普通片源恢复用户设置。 */
    private fun applyRenderLoad(mediaStreams: List<MediaStream>?) {
        heavySource = MpvRenderLoad.isHeavySource(mediaStreams)
        applyRenderQuality(force = true)
        setMpv("fbo-format", MpvRenderLoad.fboFormat(mediaStreams))
        applySpeedPerformance(currentSpeed)
        Log.i(COLOR_LOG_TAG, "render heavy=$heavySource fbo=${MpvRenderLoad.fboFormat(mediaStreams)}")
    }

    /**
     * 按当前片源和倍速选择缩放与去色带：超高像素片源、高帧率倍速（见 [MpvRenderLoad.needsFastRender]）
     * 走轻量路径，其余恢复用户设置。
     *
     * 倍速和片源帧率变化时都会调用（分别在主线程和 mpv 事件线程），结果不变时不重复写选项。
     *
     * @param force 用户设置或片源可能已变化时为 true，跳过"结果不变"的判断
     */
    private fun applyRenderQuality(force: Boolean = false) {
        synchronized(renderQualityLock) {
            val fast = MpvRenderLoad.needsFastRender(heavySource, videoFps, currentSpeed)
            if (!force && fast == fastRenderApplied) return
            fastRenderApplied = fast
            if (fast) {
                setMpv("scale", MpvRenderLoad.FAST_SCALER)
                setMpv("dscale", MpvRenderLoad.FAST_SCALER)
                setMpv("deband", "no")
            } else {
                setMpv("scale", playerPreferences.getMpvUpscaleFilter())
                setMpv("dscale", playerPreferences.getMpvDownscaleFilter())
                setMpv("deband", if (playerPreferences.getMpvDeband()) "yes" else "no")
            }
            setMpv("cscale", MpvRenderLoad.FAST_SCALER)
        }
    }

    private fun applyColorHwdec() {
        val resolved = HevcHwdecColor.hardwareDecoding(requestedHwdec, colorPolicyStreams)
        setMpv("hwdec", resolved)
        Log.i(
            COLOR_LOG_TAG,
            "hwdec=$resolved requested=$requestedHwdec streams=${colorPolicyStreams?.size ?: 0} " +
                "colorCopy=${HevcHwdecColor.needsCopyColorPath(colorPolicyStreams)}"
        )
    }

    private fun logHwdecColor(stage: String) {
        Log.i(
            COLOR_LOG_TAG,
            "$stage hwdec=${MPVLib.getPropertyString("hwdec")} " +
                "hwdec-current=${MPVLib.getPropertyString("hwdec-current")} " +
                "colormatrix=${MPVLib.getPropertyString("video-params/colormatrix")} " +
                "primaries=${MPVLib.getPropertyString("video-params/primaries")} " +
                "output-matrix=${MPVLib.getPropertyString("video-out-params/colormatrix")} " +
                "output-primaries=${MPVLib.getPropertyString("video-out-params/primaries")} " +
                "dropped=${MPVLib.getPropertyString("frame-drop-count")} " +
                "cache=${MPVLib.getPropertyString("demuxer-cache-duration")} " +
                "vf=${MPVLib.getPropertyString("vf")}"
        )
    }

    /**
     * 开启/关闭 VR 转平面。hook 读取的是 mpv 的 RGB 中间纹理，零拷贝 mediacodec 也能处理，
     * 因此不切 mediacodec-copy（8K 下每帧约 50MB 回读）。
     */
    fun setVrFlattenShader(
        layout: VrLayout?,
        yaw: Float = 0f,
        pitch: Float = 0f,
        outputFov: Float = VrFlattenFilter.DEFAULT_OUTPUT_FOV
    ) {
        if (released) return
        vrLookHandler.removeCallbacks(applyPendingVrLook)
        vrLookScheduled = false
        pendingVrLook = null
        if (layout == null) {
            clearVrFlattenShader()
            return
        }
        if (!isGpuNextOutput()) {
            // 切换 vo 会重建一次渲染输出（开启时一次性的短暂停顿），换来拖动视角零重编译。
            vrForcedGpuNext = true
            if (surfaceAttached) setMpv("vo", PlayerPreferences.MPV_VIDEO_OUTPUT_GPU_NEXT)
        }
        vrDynamicLook = true
        vrShaderActive = true
        val source = vrShaderSource(VrLook(layout, yaw, pitch, outputFov)) ?: return
        if (vrDynamicLook) {
            // 先写参数再装载，避免首帧用 PARAM 默认值渲染。
            setMpv("glsl-shader-opts", VrFlattenFilter.lookShaderOpts(yaw, pitch, outputFov))
        }
        reloadVrShader(source)
        applyVrAspect()
    }

    fun setVrLook(
        layout: VrLayout,
        yaw: Float,
        pitch: Float,
        outputFov: Float
    ) {
        if (released || !vrShaderActive) return
        if (vrDynamicLook) {
            // 只写 uniform，开销可忽略；直接应用，避免额外一帧延迟。
            applyVrLook(VrLook(layout, yaw, pitch, outputFov))
            return
        }
        pendingVrLook = VrLook(layout, yaw, pitch, outputFov)
        // 重编译路径：合并一帧内的手势；不能每次重置计时，否则连续拖动直到松手才更新。
        if (!vrLookScheduled) {
            vrLookScheduled = true
            vrLookHandler.postDelayed(applyPendingVrLook, VR_LOOK_RELOAD_MS)
        }
    }

    private fun applyVrLook(look: VrLook) {
        if (released || !vrShaderActive) return
        if (vrDynamicLook) {
            // 只更新 uniform，不重编译着色器。
            setMpv("glsl-shader-opts", VrFlattenFilter.lookShaderOpts(look.yaw, look.pitch, look.outputFov))
        } else {
            vrShaderSource(look)?.let(::reloadVrShader)
        }
    }

    private fun clearVrFlattenShader() {
        vrLookHandler.removeCallbacks(applyPendingVrLook)
        vrLookScheduled = false
        pendingVrLook = null
        lastVrShaderSource = null
        vrShaderActive = false
        if (!released) {
            applyVrAspect()
            MPVLib.command(arrayOf("change-list", "glsl-shaders", "clr", ""))
            setMpv("glsl-shader-opts", "")
            if (vrForcedGpuNext) {
                vrForcedGpuNext = false
                if (surfaceAttached) setMpv("vo", videoOutput)
            }
        }
    }

    private fun isGpuNextOutput(): Boolean {
        if (vrForcedGpuNext) return true
        val vo = MPVLib.getPropertyString("current-vo")?.takeIf { it.isNotBlank() } ?: videoOutput
        return vo == PlayerPreferences.MPV_VIDEO_OUTPUT_GPU_NEXT
    }

    private fun vrShaderSource(look: VrLook): String? {
        val template = vrShaderTemplate ?: return null
        return VrFlattenFilter.shaderSource(
            template,
            look.layout,
            look.yaw,
            look.pitch,
            look.outputFov,
            dynamicLook = vrDynamicLook
        )
    }

    private fun reloadVrShader(source: String) {
        if (released || source == lastVrShaderSource) return
        val shader = writeVrShader(source) ?: return
        lastVrShaderSource = source
        MPVLib.command(arrayOf("change-list", "glsl-shaders", "set", shader.absolutePath))
        Log.i(
            VR_LOG_TAG,
            "glsl hook=${shader.name} dynamic=$vrDynamicLook hwdec=${MPVLib.getPropertyString("hwdec")}"
        )
    }

    private fun writeVrShader(source: String): File? {
        val dir = appContext.filesDir.resolve("mpv-shaders").apply { mkdirs() }
        val dest = dir.resolve(VrFlattenFilter.shaderFileName(source))
        return runCatching {
            if (!dest.exists()) {
                // mpv 已缓存过的旧变体不再需要磁盘文件；只保留当前一份。
                dir.listFiles { file -> file.name.startsWith(VrFlattenFilter.SHADER_FILE_PREFIX) }
                    ?.forEach { it.delete() }
                dest.writeText(source)
            }
            dest
        }.onFailure { error ->
            Log.e(VR_LOG_TAG, "failed to write VR flatten shader", error)
        }.getOrNull()
    }

    private data class VrLook(
        val layout: VrLayout,
        val yaw: Float,
        val pitch: Float,
        val outputFov: Float
    )

    fun setVolume(volume: Float) {
        if (!released) {
            MPVLib.setPropertyDouble("volume", (volume.coerceIn(0f, 1f) * 100f).toDouble())
        }
    }

    fun setSpeed(speed: Double, retunePerformance: Boolean = true) {
        if (released) return
        val value = speed.coerceIn(0.25, 4.0)
        currentSpeed = value
        MPVLib.setPropertyDouble("speed", value)
        applySurfaceFrameRate()
        applyRenderQuality()
        // 长按预览只改 speed：切 interpolation / video-sync / framedrop 会清解码缓冲，造成一帧卡顿。
        if (retunePerformance) {
            applySpeedPerformance(value)
        }
    }

    fun selectAudioTrack(trackId: String) {
        if (!released) {
            MPVLib.setPropertyString("aid", trackId)
        }
    }

    fun selectSubtitleTrack(trackId: String, externalUrl: String?) {
        if (released) return
        if (trackId == "no") {
            MPVLib.setPropertyString("sid", "no")
            return
        }
        if (externalUrl != null) {
            addSubtitleTrack(externalUrl, "select")
        } else {
            MPVLib.setPropertyString("sid", trackId)
        }
        applySubtitlePreferences()
    }

    fun release() {
        if (released) return
        released = true
        vrLookHandler.removeCallbacks(applyPendingVrLook)
        vrLookScheduled = false
        MpvWarmPool.notifyReleased(this)
        runCatching { MPVLib.removeObserver(this) }
        runCatching { MPVLib.removeLogObserver(ptsLogObserver) }
        // released 已置位，此后主线程上的所有调用都会提前返回；Surface 由 ANativeWindow 引用计数保活，
        // 即使 SurfaceView 先销毁，后台 detach 也只会得到可忽略的 EGL 错误。
        lifecycleExecutor.execute {
            runCatching { MPVLib.detachSurface() }
            runCatching { MPVLib.destroy() }
        }
    }

    /** 换片时恢复 mpv 默认的 pts 处理；上一片源可能因 pts 损坏关闭过 `correct-pts`。 */
    private fun resetBrokenPtsRepair() {
        synchronized(brokenPtsDetector) { brokenPtsDetector.reset() }
        setMpv("correct-pts", "yes")
    }

    /**
     * 硬解遇到容器 pts 损坏（见 [MpvBrokenPtsDetector]）时关闭 `correct-pts`，保留硬解。
     *
     * mpv 每轮解码都会刷新该选项，运行时修改立即生效：之后按 `container-fps` 从上一帧依次排时间戳，
     * seek 后从第一个包的时间重新起算。代价是可变帧率片源的帧时长会被拉平，因此只在已出现损坏时启用。
     * 软解不处理：mpv 会自动改用重排后的 dts。
     */
    private fun onMpvLog(prefix: String, text: String) {
        if (released) return
        val detected = synchronized(brokenPtsDetector) {
            brokenPtsDetector.onLog(prefix, text, SystemClock.elapsedRealtime())
        }
        if (!detected) return
        val hwdecCurrent = MPVLib.getPropertyString("hwdec-current")
        if (hwdecCurrent.isNullOrEmpty() || hwdecCurrent == "no") {
            Log.i(COLOR_LOG_TAG, "broken video pts on software decode; keep correct-pts")
            return
        }
        setMpv("correct-pts", "no")
        Log.w(COLOR_LOG_TAG, "broken video pts on hwdec=$hwdecCurrent fps=$videoFps; correct-pts=no")
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: String) = Unit

    override fun eventProperty(property: String, value: Long) {
        eventProperty(property, value.toDouble())
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                // 回溯期间会读到目标之前的 PTS，不能把它作为续播进度写回服务器。
                if (ready) positionMs = (value * 1000.0).toLong().coerceAtLeast(0L)
                logPlaybackPerformance()
            }
            "duration" -> durationMs = (value * 1000.0).toLong().coerceAtLeast(0L)
            "demuxer-cache-duration" -> cacheAheadMs = (value * 1000.0).toLong().coerceAtLeast(0L)
            "container-fps" -> if (value != videoFps) {
                videoFps = value
                applySurfaceFrameRate()
                applyRenderQuality()
            }
        }
    }

    /**
     * 低频诊断：缓存耗尽与有缓存却掉帧是不同瓶颈。计数为累计值，应比较相邻采样的增量。
     * 不记录播放地址、headers、文件名或账号；未知属性保留 null，不能当作没有掉帧。
     */
    private fun logPlaybackPerformance() {
        if (released) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPerformanceLogMs < PERFORMANCE_LOG_INTERVAL_MS) return
        lastPerformanceLogMs = now
        Log.i(
            PERFORMANCE_LOG_TAG,
            "hwdec=${MPVLib.getPropertyString("hwdec-current")} " +
                "sourceFps=$videoFps speed=$currentSpeed bufferMs=$cacheAheadMs " +
                "pausedForCache=${MPVLib.getPropertyString("paused-for-cache")} " +
                "inputBps=${MPVLib.getPropertyString("cache-speed")} " +
                "resumeSeconds=${MPVLib.getPropertyString("cache-pause-wait")} " +
                "prebuffer=${MPVLib.getPropertyString("cache-pause-initial")} " +
                "readerIdle=${MPVLib.getPropertyString("demuxer-cache-idle")} " +
                "voDrops=${MPVLib.getPropertyString("frame-drop-count")} " +
                "decoderDrops=${MPVLib.getPropertyString("decoder-frame-drop-count")} " +
                "avsync=${MPVLib.getPropertyString("avsync")}"
        )
    }

    /** mpv 估算的实际输出帧率（`estimated-vf-fps`）；未知时为 null。OSD「实时帧率」使用。 */
    fun estimatedFrameRate(): Double? {
        if (released) return null
        return MPVLib.getPropertyString("estimated-vf-fps")?.toDoubleOrNull()?.takeIf { it > 0 }
    }

    /**
     * 向系统声明视频帧率，让屏幕切到能整除它的刷新率（60fps → 60/120Hz，24fps → 120Hz）。
     *
     * mpv 直接往 SurfaceView 送帧，不像 ExoPlayer 会自动调用 `Surface.setFrameRate`；高刷手机常驻 90Hz 时，
     * 60fps 片源只能按 1、2、1、2 个 vsync 交替上屏（11ms / 22ms），平移镜头明显抖动卡顿。
     * 倍速时按实际上屏帧率声明（见 [MpvPlaybackTuning.surfaceFrameRate]）。
     * 只在无缝切换时生效（同一显示模式组内切换不黑屏）；帧率未知或异常时不声明，交给系统默认策略。
     */
    private fun applySurfaceFrameRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = attachedSurface ?: return
        val fps = MpvPlaybackTuning.surfaceFrameRate(videoFps, currentSpeed) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    fps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
            } else {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }.onSuccess {
            Log.i(COLOR_LOG_TAG, "surface frame rate=$fps")
        }.onFailure { error ->
            Log.w(COLOR_LOG_TAG, "surface setFrameRate($fps) failed", error)
        }
    }

    /** 解除帧率声明，避免 Surface 复用到下一段播放（或画面离开后）仍锁定刷新率。 */
    private fun clearSurfaceFrameRate() {
        val surface = attachedSurface ?: return
        attachedSurface = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !surface.isValid) return
        runCatching { surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "paused-for-cache" -> {
                pausedForCache = value
                Log.i(PERFORMANCE_LOG_TAG, "paused-for-cache=$value bufferMs=$cacheAheadMs")
                if (value) {
                    listener.onBuffering()
                } else if (ready && MPVLib.getPropertyBoolean("seeking") != true) {
                    listener.onReady()
                }
            }
            "eof-reached" -> if (value) listener.onEnded()
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.MPV_EVENT_FILE_LOADED -> {
                durationMs = (MPVLib.getPropertyDouble("duration")?.times(1000.0))
                    ?.toLong()
                    ?.coerceAtLeast(0L)
                    ?: 0L
                pendingSubtitleUrls
                    .filterNot { subtitleUrl -> subtitleUrl == pendingSelectedSubtitleUrl }
                    .forEach { subtitleUrl ->
                        addSubtitleTrack(subtitleUrl, "auto")
                    }
                pendingSelectedSubtitleUrl?.let { subtitleUrl ->
                    addSubtitleTrack(subtitleUrl, "select")
                }
                if (pendingSelectedSubtitleUrl == null) {
                    pendingSubtitleTrackId?.let { trackId ->
                        MPVLib.setPropertyString("sid", trackId)
                    }
                }
                pendingSubtitleUrls = emptyList()
                pendingSelectedSubtitleUrl = null
                pendingSubtitleTrackId = null
                applySubtitlePreferences()
                applyDolbyDecodeOptions(asOptions = false)
                applyDolbyRuntimeOptions()
                logHwdecColor("FILE_LOADED")
                logSubtitleTracks("FILE_LOADED")
            }
            MpvEvent.MPV_EVENT_SEEK -> {
                if (ready) restartStartedAtMs = SystemClock.elapsedRealtime()
                ready = false
                cacheAheadMs = 0L
                listener.onBuffering()
            }
            MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                // 旧的 restart 事件可能在下一次 seek 已开始后才送达。
                if (MPVLib.getPropertyBoolean("seeking") == true) return
                val firstReady = !ready
                MPVLib.getPropertyDouble("time-pos")?.let {
                    positionMs = (it * 1000.0).toLong().coerceAtLeast(0L)
                }
                ready = true
                pausedForCache = MPVLib.getPropertyBoolean("paused-for-cache") == true
                if (playWhenReady) {
                    MPVLib.setPropertyBoolean("pause", false)
                }
                if (pausedForCache) listener.onBuffering() else listener.onReady()
                if (firstReady) {
                    Log.i(
                        PERFORMANCE_LOG_TAG,
                        "ready positionMs=$positionMs waitMs=${SystemClock.elapsedRealtime() - restartStartedAtMs}"
                    )
                    logSubtitleTracks("READY")
                }
            }
            MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                applyDolbyRuntimeOptions()
                logHwdecColor("VIDEO_RECONFIG")
            }
            MpvEvent.MPV_EVENT_SHUTDOWN -> Unit
            else -> Unit
        }
    }

    private fun configureMpv() {
        val shaderCacheDir = appContext.cacheDir.resolve("mpv-shaders")
        shaderCacheDir.mkdirs()

        MPVLib.setOptionString("gpu-shader-cache-dir", shaderCacheDir.path)
        MPVLib.setOptionString("icc-cache-dir", shaderCacheDir.path)
        MPVLib.setOptionString("config", "no")
        MPVLib.setOptionString("load-scripts", "no")
        MPVLib.setOptionString("load-auto-profiles", "no")
        MPVLib.setOptionString("load-stats-overlay", "no")
        MPVLib.setOptionString("load-console", "no")
        MPVLib.setOptionString("load-commands", "no")
        MPVLib.setOptionString("load-select", "no")
        MPVLib.setOptionString("load-positioning", "no")

        val upscaleFilter = playerPreferences.getMpvUpscaleFilter()
        val downscaleFilter = playerPreferences.getMpvDownscaleFilter()
        val deband = playerPreferences.getMpvDeband()

        MPVLib.setOptionString("profile", "fast")
        MPVLib.setOptionString("terminal", "no")
        // vo=error：GPU 着色器编译失败只在 vo 层报错，屏蔽后用户 hook 会静默失效。
        MPVLib.setOptionString("msg-level", "all=no,cplayer=warn,ffmpeg=error,sub=info,demux=warn,vo=error")
        MPVLib.setOptionString("vo", "null")
        MPVLib.setOptionString("gpu-api", "opengl")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("scale", upscaleFilter)
        MPVLib.setOptionString("dscale", downscaleFilter)
        MPVLib.setOptionString("deband", if (deband) "yes" else "no")
        MPVLib.setOptionString("target-prim", playerPreferences.getMpvTargetPrim())
        MPVLib.setOptionString("target-trc", playerPreferences.getMpvTargetTrc())
        MPVLib.setOptionString("video-output-levels", playerPreferences.getMpvOutputLevels())
        MPVLib.setOptionString(
            "hdr-compute-peak",
            if (playerPreferences.getMpvDynamicPeak()) "yes" else "no"
        )
        applyDolbyDecodeOptions(asOptions = true)
        MPVLib.setOptionString("tscale", "oversample")
        applySpeedPerformance(1.0, asOptions = true)
        MPVLib.setOptionString("ao", audioOutput)
        MPVLib.setOptionString("hwdec", hardwareDecoding)
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        MPVLib.setOptionString("tls-verify", "no")
        MPVLib.setOptionString("keep-open", "no")
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("force-seekable", "yes")
        applyCachePolicy(asOptions = true)
        MPVLib.setOptionString("index", "default")
        MPVLib.setOptionString("hr-seek", "yes")
        // 不强制 lavf：MPV 原生 MKV demuxer 才能稳定保留内嵌 ASS、附件字体与容器 PTS。
        MPVLib.setOptionString("demuxer-mkv-probe-start-time", "no")
        MPVLib.setOptionString("demuxer-mkv-probe-video-duration", "no")
        // 沿用 mpv 的索引回溯策略与窗口；强制回读 60 秒会让网盘上的高码率 MKV 跳转等待上百秒。
        MPVLib.setOptionString("demuxer-mkv-subtitle-preroll", "index")
        // 音轨/字幕轨选择依赖完整流信息，不能用 nostreams 牺牲正确性换取少量探测速度。
        MPVLib.setOptionString("demuxer-lavf-probe-info", "yes")
        MPVLib.setOptionString("demuxer-lavf-probesize", "5MiB")
        MPVLib.setOptionString("demuxer-lavf-analyzeduration", "10")
        MPVLib.setOptionString("sub-ass", if (playerPreferences.isSubtitleAssCompatible()) "yes" else "no")
        MPVLib.setOptionString("embeddedfonts", "yes")
        MPVLib.setOptionString("sub-ass-use-video-data", "aspect-ratio")
        MPVLib.setOptionString("sub-scale-with-window", "no")
        MPVLib.setOptionString("sub-font-size", "55")
        MPVLib.setOptionString("keepaspect", "yes")
        applySubtitleWindowMargins()
        configureSubtitleFonts()
        MPVLib.setOptionString("ytdl", "no")
        applySubtitlePreferences()
    }

    @OptIn(UnstableApi::class)
    fun refreshCachePolicy() {
        if (!released) {
            applyCachePolicy(asOptions = false)
            if (pendingRemoteHttpPlayback) {
                MPVLib.setPropertyString("cache", "yes")
                MPVLib.setPropertyString("force-seekable", "yes")
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun applyRemoteStreamOptions(remoteHttpPlayback: Boolean) {
        if (remoteHttpPlayback) {
            setMpv("demuxer-lavf-probesize", "5MiB")
            setMpv("demuxer-lavf-analyzeduration", "10")
            setMpv("demuxer-lavf-probe-info", "yes")
            MPVLib.setPropertyString("cache", "yes")
            MPVLib.setPropertyString("force-seekable", "yes")
            applyCachePolicy(asOptions = false)
        } else {
            // 服务端静态流同样是 HTTP，MKV 的字幕轨/字体附件可能超过 64KiB 探测窗口。
            setMpv("demuxer-lavf-probesize", "5MiB")
            setMpv("demuxer-lavf-analyzeduration", "10")
            setMpv("demuxer-lavf-probe-info", "yes")
        }
    }

    private fun applyDolbyDecodeOptions(asOptions: Boolean) {
        val deviceSupport = HdrCapabilityManager.getDeviceHdrSupport(appContext)
        val options = DolbyVisionMpv.decodeOptions(
            convertDv7ToDv81 = playerPreferences.isDolbyDv7ToDv81Enabled(),
            deviceSupportsDolbyVision = deviceSupport == HdrCapabilityManager.HdrSupport.DOLBY_VISION
        )
        // operating_rate 是 MediaCodec 解码器的私有选项，软解不认，只在请求硬解时附加。
        val operatingRate = MpvRenderLoad.decoderOperatingRate(colorPolicyStreams)
            ?.takeIf { requestedHwdec != PlayerPreferences.MPV_HARDWARE_DECODING_NONE }
            ?.let { "operating_rate=$it" }
        val vdLavcO = listOfNotNull(options.vdLavcO.takeIf { it.isNotBlank() }, operatingRate).joinToString(",")
        Log.i(
            DOLBY_LOG_TAG,
            "decode path=${options.playbackPath} vf=${options.vf.ifBlank { "<none>" }} " +
                "vd-lavc-o=${vdLavcO.ifBlank { "<none>" }}"
        )
        val vf = HevcHwdecColor.composedVf(options.vf, colorPolicyStreams)
        Log.i(
            COLOR_LOG_TAG,
            "hwdec=${MPVLib.getPropertyString("hwdec")} vf=${vf.ifBlank { "<none>" }} " +
                "colorCopy=${HevcHwdecColor.needsCopyColorPath(colorPolicyStreams)}"
        )
        if (asOptions) {
            MPVLib.setOptionString("vf", vf)
            MPVLib.setOptionString("vd-lavc-o", vdLavcO)
        } else {
            setMpv("vf", vf)
            setMpv("vd-lavc-o", vdLavcO)
        }
    }

    private fun applyDolbyRuntimeOptions() {
        if (released) return
        val isDolbyVision = DolbyVisionMpv.isDolbyVisionTrack(
            codec = MPVLib.getPropertyString("video-codec"),
            format = MPVLib.getPropertyString("video-format"),
            doviFlag = MPVLib.getPropertyString("video-params/dolbyvision")
                ?: MPVLib.getPropertyString("metadata/dovi.profile")
        )
        val options = DolbyVisionMpv.runtimeOptions(
            isDolbyVisionContent = isDolbyVision,
            brightnessEnhancement = playerPreferences.isDolbyBrightnessEnhancementEnabled(),
            dynamicPeakEnabled = playerPreferences.getMpvDynamicPeak()
        )
        if (options.playbackPath == lastDolbyRuntimePath) {
            return
        }
        lastDolbyRuntimePath = options.playbackPath
        Log.i(
            DOLBY_LOG_TAG,
            "runtime path=${options.playbackPath} hdr-compute-peak=${options.hdrComputePeak} " +
                "tone-mapping-max-boost=${options.toneMappingMaxBoost}"
        )
        setMpv("hdr-compute-peak", options.hdrComputePeak)
        setMpv("tone-mapping-max-boost", options.toneMappingMaxBoost)
    }

    private fun applyHttpRequestHeaders(requestHeaders: Map<String, String>) {
        // STRM 常依赖 Referer/Cookie/User-Agent；播放请求已解析这些 headers，必须继续传给 mpv。
        setMpv("http-header-fields", MPVPlayer.httpHeaderFields(requestHeaders).orEmpty())
    }

    @OptIn(UnstableApi::class)
    private fun applyCachePolicy(asOptions: Boolean) {
        val budget = PlayerUtils.playbackCacheBudget(appContext, playerPreferences)
        val cacheTimeSeconds = budget.cacheTimeSeconds.toString()
        val pauseWait = MpvPlaybackTuning.cachePauseWaitSeconds(sourceBitrate, budget.cacheTimeSeconds).toString()
        val pauseInitial = if (pauseWait.toInt() > 1) "yes" else "no"
        val cacheSize = "${budget.cacheSizeMb}MiB"
        val backCacheSize = "${(budget.cacheSizeMb / 2).coerceAtLeast(32)}MiB"
        if (asOptions) {
            MPVLib.setOptionString("cache-secs", cacheTimeSeconds)
            MPVLib.setOptionString("demuxer-readahead-secs", cacheTimeSeconds)
            MPVLib.setOptionString("demuxer-max-bytes", cacheSize)
            MPVLib.setOptionString("demuxer-max-back-bytes", backCacheSize)
            MPVLib.setOptionString("cache-pause-wait", pauseWait)
            MPVLib.setOptionString("cache-pause-initial", pauseInitial)
        } else {
            MPVLib.setPropertyString("cache-secs", cacheTimeSeconds)
            MPVLib.setPropertyString("demuxer-readahead-secs", cacheTimeSeconds)
            MPVLib.setPropertyString("demuxer-max-bytes", cacheSize)
            MPVLib.setPropertyString("demuxer-max-back-bytes", backCacheSize)
            MPVLib.setPropertyString("cache-pause-wait", pauseWait)
            MPVLib.setPropertyString("cache-pause-initial", pauseInitial)
        }
        Log.i(PERFORMANCE_LOG_TAG, "cachePolicy maxMiB=${budget.cacheSizeMb} resumeSeconds=$pauseWait prebuffer=$pauseInitial bitrate=$sourceBitrate")
    }

    private fun addSubtitleTrack(url: String, flags: String) {
        MPVLib.command(arrayOf("sub-add", url, flags, "ASS"))
    }

    private fun configureSubtitleFonts() {
        val fontsDir = appContext.filesDir.resolve("mpv-fonts").apply { mkdirs() }
        val cacheDir = appContext.cacheDir.resolve("fontconfig").apply { mkdirs() }
        val fallback = installFallbackSubtitleFont(fontsDir)
        val fontsConf = appContext.filesDir.resolve("mpv/fonts.conf")
        fontsConf.parentFile?.mkdirs()
        fontsConf.writeText(
            """
            <?xml version="1.0"?>
            <!DOCTYPE fontconfig SYSTEM "fonts.dtd">
            <fontconfig>
              <dir>/system/fonts</dir>
              <dir>/product/fonts</dir>
              <dir>${fontsDir.path}</dir>
              <cachedir>${cacheDir.path}</cachedir>
            </fontconfig>
            """.trimIndent()
        )
        runCatching { Os.setenv("FONTCONFIG_FILE", fontsConf.absolutePath, true) }
        MPVLib.setOptionString("sub-fonts-dir", fontsDir.path)
        MPVLib.setOptionString("osd-fonts-dir", fontsDir.path)
        subtitleFontFamily = fallback?.family ?: "sans-serif"
        MPVLib.setOptionString("sub-font", subtitleFontFamily)
        Log.i(
            SUBTITLE_LOG_TAG,
            "subtitle font family=$subtitleFontFamily file=${fallback?.file?.name ?: "none"}"
        )
    }

    private fun installFallbackSubtitleFont(fontsDir: File): InstalledSubtitleFont? {
        val candidates = listOf(
            "Droid Sans Fallback" to listOf(
                "/system/fonts/DroidSansFallback.ttf",
                "/system/fonts/DroidSansFallbackFull.ttf"
            ),
            "Noto Sans CJK SC" to listOf(
                "/system/fonts/NotoSansSC-Regular.otf",
                "/system/fonts/NotoSansSC-Regular.ttf",
                "/system/fonts/NotoSansCJKsc-Regular.otf",
                "/system/fonts/NotoSansCJK-Regular.ttc",
                "/product/fonts/NotoSansCJK-Regular.ttc"
            ),
            "MiSans" to listOf(
                "/system/fonts/MiSans-Regular.ttf",
                "/system/fonts/MiSansVF.ttf",
                "/system/fonts/MiSans.ttf",
                "/product/fonts/MiSansVF.ttf"
            )
        )
        val match = candidates.firstNotNullOfOrNull { (family, paths) ->
            paths.firstOrNull { path -> File(path).exists() }?.let { family to File(it) }
        } ?: return null
        val (family, source) = match
        val target = File(fontsDir, source.name)
        if (!target.exists()) {
            val linked = runCatching {
                Os.symlink(source.absolutePath, target.absolutePath)
                true
            }.getOrDefault(false)
            if (!linked) {
                return InstalledSubtitleFont(family, source)
            }
        }
        return InstalledSubtitleFont(family, if (target.exists()) target else source)
    }

    private data class InstalledSubtitleFont(
        val family: String,
        val file: File
    )

    private fun logSubtitleTracks(stage: String) {
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val tracks = buildList {
            for (index in 0 until count) {
                val type = MPVLib.getPropertyString("track-list/$index/type") ?: continue
                if (type != "sub") continue
                add(
                    "id=${MPVLib.getPropertyString("track-list/$index/id")} " +
                        "codec=${MPVLib.getPropertyString("track-list/$index/codec")} " +
                        "lang=${MPVLib.getPropertyString("track-list/$index/lang")} " +
                        "title=${MPVPlayer.redactPlaybackSecret(MPVLib.getPropertyString("track-list/$index/title"))} " +
                        "selected=${MPVLib.getPropertyBoolean("track-list/$index/selected")} " +
                        "external=${MPVLib.getPropertyBoolean("track-list/$index/external")}"
                )
            }
        }
        Log.i(
            SUBTITLE_LOG_TAG,
            "$stage sid=${MPVLib.getPropertyString("sid")} " +
                "vis=${MPVLib.getPropertyString("sub-visibility")} " +
                "override=${MPVLib.getPropertyString("sub-ass-override")} " +
                "probe=${MPVLib.getPropertyString("demuxer-lavf-probe-info")} " +
                "font=${MPVLib.getPropertyString("sub-font")} " +
                "text=${MPVPlayer.redactPlaybackSecret(MPVLib.getPropertyString("sub-text")?.take(80))} " +
                "tracks=$tracks"
        )
    }

    private fun setMpv(name: String, value: String) {
        MPVLib.setOptionString(name, value)
        MPVLib.setPropertyString(name, value)
    }

    private fun mpvColor(color: String, opacityPercent: Int): String {
        val rgb = when (color) {
            PlayerPreferences.SUBTITLE_TEXT_COLOR_YELLOW -> "FFFF00"
            PlayerPreferences.SUBTITLE_TEXT_COLOR_GREEN -> "00FF00"
            PlayerPreferences.SUBTITLE_TEXT_COLOR_CYAN -> "00FFFF"
            PlayerPreferences.SUBTITLE_TEXT_COLOR_BLACK -> "000000"
            else -> "FFFFFF"
        }
        return "#${alphaHex(opacityPercent)}$rgb"
    }

    private fun mpvBackgroundColor(color: String): String {
        return when (color) {
            PlayerPreferences.SUBTITLE_BACKGROUND_BLACK -> "#CC000000"
            PlayerPreferences.SUBTITLE_BACKGROUND_WHITE -> "#CCFFFFFF"
            else -> "#00000000"
        }
    }

    private fun applySubtitleLayout() {
        if (released) return
        val scale = PlayerPreferences.mpvSubScaleForWindow(
            userScale = playerPreferences.getSubtitleScale(),
            windowWidth = surfaceWidth,
            windowHeight = surfaceHeight
        )
        setMpv(
            "sub-scale",
            String.format(Locale.US, "%.3f", scale)
        )
        if (playerPreferences.isSubtitleAssCompatible()) {
            // 兼容模式不使用黑边（sub-use-margins=no），sub-pos 以视频区域为基准；
            // 只移动对白类字幕，用 \pos 定位的特效字幕保持原位。
            setMpv(
                "sub-pos",
                PlayerPreferences.mpvSubPosFromBottomPercent(playerPreferences.getSubtitlePosition()).toString()
            )
            return
        }
        setMpv(
            "sub-pos",
            PlayerPreferences.mpvSubPosForWindow(
                bottomPercent = playerPreferences.getSubtitlePosition(),
                windowWidth = surfaceWidth,
                windowHeight = surfaceHeight,
                userScale = playerPreferences.getSubtitleScale()
            ).toString()
        )
    }

    private fun applySubtitleWindowMargins() {
        val compatible = playerPreferences.isSubtitleAssCompatible()
        if (compatible) {
            setMpv("sub-use-margins", "no")
            setMpv("sub-ass-force-margins", "no")
        } else {
            // 竖屏要把字幕画到视频下方的黑边；横屏无黑边时与视频底边重合。
            setMpv("sub-use-margins", "yes")
            setMpv("sub-ass-force-margins", "yes")
        }
        setMpv("sub-margin-y", "0")
        setMpv("sub-margin-x", "0")
    }

    private fun applySpeedPerformance(speed: Double, asOptions: Boolean = false) {
        // 插帧需要逐帧中间纹理，超高像素片源下反而导致掉帧。
        val smoothMotion = playerPreferences.getMpvSmoothMotion() && !heavySource
        val interpolation = MpvPlaybackTuning.interpolation(smoothMotion, speed)
        val videoSync = MpvPlaybackTuning.videoSync(smoothMotion, speed)
        val framedrop = MpvPlaybackTuning.FRAMEDROP
        val skipLoopFilter = MpvPlaybackTuning.skipLoopFilter(speed)
        if (asOptions) {
            MPVLib.setOptionString("interpolation", interpolation)
            MPVLib.setOptionString("video-sync", videoSync)
            MPVLib.setOptionString("framedrop", framedrop)
            MPVLib.setOptionString("hr-seek-framedrop", "yes")
            MPVLib.setOptionString("video-latency-hacks", "no")
            MPVLib.setOptionString("vd-lavc-skipframe", "default")
            MPVLib.setOptionString(
                "vd-lavc-skiploopfilter",
                if (skipLoopFilter) "nonkey" else "default"
            )
        } else {
            setMpv("interpolation", interpolation)
            setMpv("video-sync", videoSync)
            setMpv("framedrop", framedrop)
            setMpv("vd-lavc-skiploopfilter", if (skipLoopFilter) "nonkey" else "default")
        }
    }

    private fun applySubtitleEdge(edgeType: String) {
        when (edgeType) {
            PlayerPreferences.SUBTITLE_EDGE_TYPE_NONE -> {
                setMpv("sub-border-size", "0")
                setMpv("sub-shadow-offset", "0")
                setMpv("sub-border-color", "#00000000")
                setMpv("sub-shadow-color", "#00000000")
            }
            PlayerPreferences.SUBTITLE_EDGE_TYPE_OUTLINE -> {
                setMpv("sub-border-size", "3")
                setMpv("sub-shadow-offset", "0")
                setMpv("sub-border-color", "#FF000000")
                setMpv("sub-shadow-color", "#CC000000")
            }
            PlayerPreferences.SUBTITLE_EDGE_TYPE_DROP_SHADOW -> {
                setMpv("sub-border-size", "0")
                setMpv("sub-shadow-offset", "2")
                setMpv("sub-border-color", "#00000000")
                setMpv("sub-shadow-color", "#CC000000")
            }
            else -> {
                setMpv("sub-border-size", "2")
                setMpv("sub-shadow-offset", "0")
                setMpv("sub-border-color", "#FF000000")
                setMpv("sub-shadow-color", "#CC000000")
            }
        }
    }

    private fun alphaHex(opacityPercent: Int): String {
        val alpha = ((opacityPercent.coerceIn(0, 100) / 100f) * 255f)
            .toInt()
            .coerceIn(0, 255)
        return alpha.toString(16).uppercase().padStart(2, '0')
    }
}
