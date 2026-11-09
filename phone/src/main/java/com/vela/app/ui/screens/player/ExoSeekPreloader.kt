package com.vela.app.ui.screens.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.vela.player.core.PlayerUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** 在主线程管理一个待跳转位置。预加载和播放共用 Media3 的播放线程、缓冲分配器与带宽估计。 */
@UnstableApi
internal class ExoSeekPreloader(
    context: Context,
    private val mediaItem: MediaItem,
    bufferOverride: PlayerUtils.PlaybackBufferOverride? = null,
    private val createSource: (MediaItem) -> MediaSource
) {
    private class Request(
        val item: MediaItem,
        val index: Int,
        val status: DefaultPreloadManager.PreloadStatus,
        val ready: CompletableDeferred<Boolean> = CompletableDeferred()
    )

    private var request: Request? = null
    private var handedOffItem: MediaItem? = null
    private var nextIndex = 0
    private lateinit var preloadTrackSelector: DefaultTrackSelector
    private val builder = DefaultPreloadManager.Builder(context.applicationContext) { index ->
        request?.takeIf { it.index == index }?.status
            ?: DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
    }.setTrackSelectorFactory { selectorContext ->
        // buildExoPlayer 先创建播放选轨器，随后 build 创建独立的预加载选轨器。
        DefaultTrackSelector(selectorContext).also { preloadTrackSelector = it }
    }

    val player = PlayerUtils.createPlayer(context, bufferOverride, preloadBuilder = builder)
    private var manager: DefaultPreloadManager? = null

    fun start(positionMs: Long, durationMs: Long): Deferred<Boolean> {
        cancelPending()
        val preloader = manager ?: builder.build().also {
            manager = it
            it.addListener(object : PreloadManagerListener {
                override fun onCompleted(mediaItem: MediaItem) {
                    request?.takeIf { it.item == mediaItem }?.ready?.complete(true)
                }

                override fun onError(exception: PreloadException) {
                    request?.takeIf { it.item == exception.mediaItem }?.ready?.complete(false)
                }
            })
        }
        preloadTrackSelector.setParameters(player.trackSelectionParameters)
        val index = ++nextIndex
        // 唯一 ID 用于区分迟到的回调，保留原 URL、字幕配置和磁盘缓存键。
        val source = createSource(mediaItem.buildUpon().setMediaId("vela-seek-$index").build())
        val pending = Request(
            item = source.mediaItem,
            index = index,
            status = DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(positionMs, durationMs)
        )
        request = pending
        preloader.add(source, index)
        preloader.invalidate()
        return pending.ready
    }

    fun handOff(positionMs: Long): Boolean {
        val pending = request ?: return false
        val source = manager?.getMediaSource(pending.item) ?: return false
        request = null
        finishHandoff()
        handedOffItem = pending.item
        player.setMediaSource(source, positionMs)
        return true
    }

    fun finishHandoff() {
        val item = handedOffItem ?: return
        handedOffItem = null
        // 等主播放器接管预加载的 MediaPeriod 后再移除，否则队列里的样本会被提前释放。
        manager?.remove(item)
    }

    fun cancelPending() {
        val pending = request ?: return
        request = null
        pending.ready.cancel()
        manager?.remove(pending.item)
    }

    fun release() {
        cancelPending()
        manager?.release()
        manager = null
        handedOffItem = null
    }
}
