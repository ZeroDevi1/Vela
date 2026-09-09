package com.vela.app.ui.screens.music

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vela.app.ui.activity.VelaActivity
import com.vela.data.model.BaseItemDto
import com.vela.data.repository.AuthRepositoryProvider
import com.vela.data.repository.LibraryAudioSource
import com.vela.data.repository.LibraryMediaSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class MusicState(
    val session: LibraryMediaSession? = null,
    val queue: List<BaseItemDto> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
    val reportingError: String? = null,
    val compatibleAudio: Boolean = false,
    val sleepAtMs: Long? = null
) {
    val item: BaseItemDto? get() = queue.getOrNull(index)
}

/** UI 只连接和控制服务；不随页面销毁播放器。 */
@UnstableApi
internal object MusicPlayback {
    internal val mutableState = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = mutableState.asStateFlow()
    internal var service: MusicPlaybackService? = null
    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null

    suspend fun play(context: Context, session: LibraryMediaSession, items: List<BaseItemDto>, index: Int = 0) {
        require(items.isNotEmpty()) { "没有可播放的音频" }
        connect(context)
        val playbackService = service ?: throw IOException("音乐播放服务未连接，请重试")
        playbackService.load(session, items, index)
    }

    private suspend fun connect(context: Context): MediaController {
        controller?.let { return it }
        val app = context.applicationContext
        val future = connecting ?: MediaController.Builder(app, SessionToken(app, ComponentName(app, MusicPlaybackService::class.java)))
            .buildAsync().also { connecting = it }
        return suspendCancellableCoroutine { continuation ->
            future.addListener({
                try {
                    val result = future.get()
                    controller = result
                    connecting = null
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    connecting = null
                    if (continuation.isActive) continuation.resumeWithException(IOException("无法连接音乐服务", error))
                }
            }, ContextCompat.getMainExecutor(app))
        }
    }

    fun toggle() { controller?.let {
        if (it.playWhenReady && it.playbackState != Player.STATE_ENDED) it.pause()
        else {
            if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition()
            mutableState.value = mutableState.value.copy(error = null)
            it.prepare(); it.play()
        }
    } }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun seek(position: Long) { controller?.seekTo(position.coerceAtLeast(0)) }
    fun seekIndex(index: Int) { controller?.let { it.seekToDefaultPosition(index); it.play() } }
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun repeat() { controller?.let { it.repeatMode = when (it.repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    } } }
    fun speed(speed: Float) { controller?.setPlaybackSpeed(speed) }
    fun sleep(minutes: Int?) { service?.setSleepTimer(minutes) }
    fun retryCompatible() { service?.retryCompatible() }
    fun pauseForVideo() { controller?.pause() }
    fun stop() {
        service?.clear()
        controller?.release()
        controller = null
        connecting?.let { MediaController.releaseFuture(it) }
        connecting = null
        service?.stopSelf()
    }
    internal fun disconnected() { controller = null; connecting = null; service = null; mutableState.value = MusicState() }
}

@UnstableApi
class MusicPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private var librarySession: LibraryMediaSession? = null
    private var queue: List<BaseItemDto> = emptyList()
    private var sources: List<LibraryAudioSource> = emptyList()
    private var current: LibraryAudioSource? = null
    private var reportStarted = false
    private var lastPosition = 0L
    private var compatible = false
    private var sleepAt: Long? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reports = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val http = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        val factory = DataSource.Factory {
            val session = librarySession ?: throw IOException("音乐会话已失效")
            val origin = session.baseUrl.toHttpUrl()
            val client = http.newBuilder().addNetworkInterceptor { chain ->
                val url = chain.request().url
                val builder = chain.request().newBuilder().removeHeader("Authorization")
                if (url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port) {
                    session.requestHeaders.forEach { (key, value) -> builder.header(key, value) }
                }
                chain.proceed(builder.build())
            }.build()
            OkHttpDataSource.Factory(client).createDataSource()
        }
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
        val intent = Intent(this, VelaActivity::class.java).apply {
            action = ACTION_OPEN_MUSIC
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 101, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                    // 系统和蓝牙可以控制已有队列，不能注入带账户权限的任意 URL。
                    return Futures.immediateFailedFuture(SecurityException("请从 Vela 音乐库添加歌曲"))
                }
            }).build()
        MusicPlayback.service = this
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val next = sources.getOrNull(player.currentMediaItemIndex)
                if (next?.playSessionId != current?.playSessionId) {
                    stopReport()
                    current = next
                    reportStarted = false
                    lastPosition = 0
                }
                if (player.playbackState == Player.STATE_READY) startReport()
                publish()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) startReport()
                if (playbackState == Player.STATE_ENDED) { lastPosition = player.duration.coerceAtLeast(0); stopReport() }
                publish()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) lastPosition = oldPosition.positionMs
            }
            override fun onEvents(player: Player, events: Player.Events) { publish() }
            override fun onPlayerError(error: PlaybackException) {
                stopReport(failed = true)
                MusicPlayback.mutableState.value = MusicPlayback.mutableState.value.copy(
                    error = "播放失败（${error.errorCodeName}）。可重试或使用兼容音频。")
                publish()
            }
        })
        reportScope.launch {
            for (report in reports) {
                try { withTimeout(8_000) { report() }; MusicPlayback.mutableState.value = MusicPlayback.mutableState.value.copy(reportingError = null) }
                catch (e: TimeoutCancellationException) { MusicPlayback.mutableState.value = MusicPlayback.mutableState.value.copy(reportingError = "播放进度同步超时，请检查服务器连接") }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { MusicPlayback.mutableState.value = MusicPlayback.mutableState.value.copy(reportingError = "播放进度同步失败，请检查服务器连接") }
            }
        }
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(1000)
                if (sleepAt?.let { System.currentTimeMillis() >= it } == true) { player.pause(); sleepAt = null }
                lastPosition = player.currentPosition.coerceAtLeast(0)
                publish()
                if (++ticks % 10 == 0 && reportStarted) {
                    val session = librarySession
                    val source = current
                    val position = lastPosition
                    val paused = !player.isPlaying
                    if (session != null && source != null) reports.trySend { session.reportProgress(source, position, paused) }
                }
            }
        }
        val auth = AuthRepositoryProvider.getInstance(this)
        scope.launch {
            combine(auth.getActiveServerId(), auth.getServerUrl(), auth.getAccessToken()) { id, url, token -> Triple(id, url, token) }
                .collect { (id, url, token) ->
                    val session = librarySession ?: return@collect
                    if (id != session.accountKey || url?.trimEnd('/') != session.baseUrl.trimEnd('/') || !session.matchesToken(token)) {
                        clear()
                    }
                }
        }
    }

    internal fun load(session: LibraryMediaSession, items: List<BaseItemDto>, index: Int) {
        stopReport()
        player.stop()
        current = null
        librarySession = session
        queue = items
        compatible = false
        sources = items.map { session.audioSource(it) }
        MusicPlayback.mutableState.value = MusicState(session = session, queue = items, index = index.coerceIn(items.indices))
        player.setMediaItems(items.mapIndexed { i, item -> mediaItem(item, sources[i]) }, index.coerceIn(items.indices),
            if (items[index.coerceIn(items.indices)].type.equals("AudioBook", true))
                (items[index.coerceIn(items.indices)].userData?.playbackPositionTicks ?: 0) / 10_000 else 0L)
        player.prepare()
        player.play()
        publish()
    }

    private fun mediaItem(item: BaseItemDto, source: LibraryAudioSource): MediaItem = MediaItem.Builder()
        .setMediaId(source.playSessionId).setUri(source.url)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name)
            .setArtist(item.artists?.joinToString(" / ") ?: item.albumArtist)
            .setAlbumTitle(item.album).setMediaType(if (item.type.equals("AudioBook", true)) MediaMetadata.MEDIA_TYPE_AUDIO_BOOK else MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()).build()

    internal fun retryCompatible() {
        val session = librarySession ?: return
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val position = player.currentPosition.coerceAtLeast(0)
        stopReport()
        current = null
        compatible = true
        sources = queue.map { session.audioSource(it, transcode = true) }
        MusicPlayback.mutableState.value = MusicPlayback.mutableState.value.copy(error = null)
        player.setMediaItems(queue.mapIndexed { i, item -> mediaItem(item, sources[i]) }, index, position)
        player.prepare(); player.play(); publish()
    }

    internal fun setSleepTimer(minutes: Int?) { sleepAt = minutes?.let { System.currentTimeMillis() + it * 60_000L }; publish() }
    internal fun clear() {
        stopReport()
        player.stop(); player.clearMediaItems()
        current = null; queue = emptyList(); sources = emptyList(); librarySession = null; sleepAt = null
        MusicPlayback.mutableState.value = MusicState()
    }

    private fun startReport() {
        if (reportStarted) return
        val session = librarySession ?: return
        val source = current ?: return
        reportStarted = true
        val position = player.currentPosition.coerceAtLeast(0)
        val paused = !player.playWhenReady
        reports.trySend { session.reportStart(source, position, paused) }
    }
    private fun stopReport(failed: Boolean = false) {
        if (!reportStarted) return
        val session = librarySession ?: return
        val source = current ?: return
        val position = lastPosition
        reportStarted = false
        reports.trySend { session.reportStop(source, position, failed) }
    }
    private fun publish() {
        MusicPlayback.mutableState.value = MusicPlayback.mutableState.value.copy(session = librarySession, queue = queue,
            index = player.currentMediaItemIndex.coerceAtLeast(0), playing = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING, positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0), shuffle = player.shuffleModeEnabled, repeatMode = player.repeatMode,
            compatibleAudio = compatible, sleepAtMs = sleepAt)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
    override fun onDestroy() {
        stopReport()
        reports.close()
        player.release()
        mediaSession?.release()
        MusicPlayback.disconnected()
        // 给最后一次停止回报有限时间，页面退出不应让网络任务永久持有 Service。
        scope.cancel()
        reportScope.launch { delay(3000); reportScope.cancel() }
        super.onDestroy()
    }

    companion object { const val ACTION_OPEN_MUSIC = "com.vela.app.OPEN_MUSIC" }
}
