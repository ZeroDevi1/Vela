package com.vela.app.ui.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.core.view.WindowCompat
import androidx.media3.common.util.UnstableApi
import com.vela.app.R
import com.vela.app.locale.AppLanguageManager
import com.vela.app.ui.screens.player.requestedOrientationFor
import com.vela.player.preferences.PlayerBehaviorPreferences
import com.vela.player.preferences.PlayerPreferences
import com.vela.app.ui.player.PictureInPictureHost
import com.vela.app.ui.player.applyPlayerPipParams
import com.vela.app.ui.player.findActivity
import com.vela.app.ui.screens.player.PlayerScreen
import com.vela.app.ui.screens.player.PlayerViewModel
import com.vela.data.model.BaseItemDto
import com.vela.data.repository.MediaRepositoryProvider
import com.vela.shared.ui.theme.VelaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@UnstableApi
@AndroidEntryPoint
class PlayerActivity : ComponentActivity(), PictureInPictureHost {

    /** 与 PlayerScreen 里 hiltViewModel() 取到的是同一实例（同为本 Activity 的 ViewModelStore）。 */
    private val playerViewModel: PlayerViewModel by viewModels()
    private val pipMode = MutableStateFlow(false)
    private val playbackArgs = MutableStateFlow<PlaybackArgs?>(null)

    override val pictureInPictureMode: StateFlow<Boolean> = pipMode.asStateFlow()
    override var userLeaveHintHandler: (() -> Unit)? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguageManager.applySavedLanguage(this)
        applyEdgeToEdgeSystemBars()
        // 首帧之前就锁定方向：否则窗口先按竖屏出现，等 Compose 生效后再旋转，会多一次布局与旋转动画。
        requestedOrientation = requestedOrientationFor(PlayerPreferences(this).getPlayerOrientation())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.player_fade_in, R.anim.player_hold)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.player_hold, R.anim.player_fade_out)
        }
        playbackArgs.value = PlaybackArgs.from(intent)
        pipMode.value = isInPictureInPictureMode

        setContent {
            VelaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ComposeColor.Black
                ) {
                    val args by playbackArgs.collectAsState()
                    val current = args ?: return@Surface
                    PlayerRoute(args = current)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        playbackArgs.value = PlaybackArgs.from(intent)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        userLeaveHintHandler?.invoke()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode.value = isInPictureInPictureMode
        applyEdgeToEdgeSystemBars()
    }

    /**
     * 所有退出路径（返回键、系统预测性返回、媒体会话停止、返回按钮）最终都会走到这里：
     * 先暂停并上报停止，解码器释放留给 ViewModel.onCleared，在退出转场结束后进行。
     */
    override fun finish() {
        if (!isFinishing) playerViewModel.prepareExit()
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.player_hold, R.anim.player_fade_out)
        }
    }

    private fun applyEdgeToEdgeSystemBars() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    companion object {
        const val EXTRA_MEDIA_ID = "media_id"
        const val EXTRA_FROM_START = "from_start"
        const val EXTRA_SEEK_MS = "seek_ms"
        const val EXTRA_MEDIA_SOURCE_ID = "media_source_id"
        const val EXTRA_AUDIO_INDEX = "audio_index"
        const val EXTRA_SUBTITLE_INDEX = "subtitle_index"
        const val EXTRA_REMOTE_URL = "remote_url"
        const val EXTRA_REMOTE_TITLE = "remote_title"
        const val EXTRA_PARTS_OWNER_ID = "parts_owner_id"

        fun start(
            context: Context,
            mediaId: String,
            startFromBeginning: Boolean = false,
            seekPositionMs: Long? = null,
            mediaSourceId: String? = null,
            audioStreamIndex: Int? = null,
            subtitleStreamIndex: Int? = null,
            remoteUrl: String? = null,
            remoteTitle: String? = null,
            partsOwnerId: String? = null
        ) {
            if (mediaId.isBlank() && remoteUrl.isNullOrBlank()) return
            com.vela.app.ui.screens.music.MusicPlayback.pauseForVideo()
            val activity = context.findActivity()
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_MEDIA_ID, mediaId)
                putExtra(EXTRA_FROM_START, startFromBeginning)
                seekPositionMs?.let { putExtra(EXTRA_SEEK_MS, it) }
                mediaSourceId?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_MEDIA_SOURCE_ID, it) }
                audioStreamIndex?.let { putExtra(EXTRA_AUDIO_INDEX, it) }
                subtitleStreamIndex?.let { putExtra(EXTRA_SUBTITLE_INDEX, it) }
                remoteUrl?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_REMOTE_URL, it) }
                remoteTitle?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_REMOTE_TITLE, it) }
                partsOwnerId?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_PARTS_OWNER_ID, it) }
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                if (activity == null) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                activity?.overridePendingTransition(R.anim.player_fade_in, R.anim.player_hold)
            }
        }
    }
}

private data class PlaybackArgs(
    val mediaId: String,
    val startFromBeginning: Boolean,
    val seekPositionMs: Long?,
    val mediaSourceId: String?,
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
    val remoteUrl: String?,
    val remoteTitle: String?,
    /**
     * 多 CD 影片的主条目（CD1）。从详情页分段卡片启动 CD2 等分段时传入，用于取完整分段列表；
     * 为空时 [mediaId] 本身就是主条目或非分段影片。
     */
    val partsOwnerId: String?
) {
    companion object {
        fun from(intent: Intent?): PlaybackArgs? {
            if (intent == null) return null
            val mediaId = intent.getStringExtra(PlayerActivity.EXTRA_MEDIA_ID).orEmpty()
            val remoteUrl = intent.getStringExtra(PlayerActivity.EXTRA_REMOTE_URL)
            if (mediaId.isBlank() && remoteUrl.isNullOrBlank()) return null
            return PlaybackArgs(
                mediaId = mediaId.ifBlank { "remote_${remoteUrl.hashCode()}" },
                startFromBeginning = intent.getBooleanExtra(PlayerActivity.EXTRA_FROM_START, false),
                seekPositionMs = if (intent.hasExtra(PlayerActivity.EXTRA_SEEK_MS)) {
                    intent.getLongExtra(PlayerActivity.EXTRA_SEEK_MS, 0L)
                } else {
                    null
                },
                mediaSourceId = intent.getStringExtra(PlayerActivity.EXTRA_MEDIA_SOURCE_ID),
                audioStreamIndex = if (intent.hasExtra(PlayerActivity.EXTRA_AUDIO_INDEX)) {
                    intent.getIntExtra(PlayerActivity.EXTRA_AUDIO_INDEX, 0)
                } else {
                    null
                },
                subtitleStreamIndex = if (intent.hasExtra(PlayerActivity.EXTRA_SUBTITLE_INDEX)) {
                    intent.getIntExtra(PlayerActivity.EXTRA_SUBTITLE_INDEX, 0)
                } else {
                    null
                },
                remoteUrl = remoteUrl,
                remoteTitle = intent.getStringExtra(PlayerActivity.EXTRA_REMOTE_TITLE),
                partsOwnerId = intent.getStringExtra(PlayerActivity.EXTRA_PARTS_OWNER_ID)
            )
        }
    }
}

@UnstableApi
@androidx.compose.runtime.Composable
private fun PlayerRoute(args: PlaybackArgs) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mediaRepository = remember { MediaRepositoryProvider.getInstance(context) }
    var mediaId by remember(args.mediaId, args.remoteUrl) { mutableStateOf(args.mediaId) }
    var episodePreviousId by remember { mutableStateOf<String?>(null) }
    var episodeNextId by remember { mutableStateOf<String?>(null) }
    // 多 CD / 多分段影片的各部分，按启动的条目取一次；部分之间原地切换时不重新获取。
    var parts by remember(args.mediaId, args.remoteUrl, args.partsOwnerId) { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    val partIndex = parts.indexOfFirst { it.id == mediaId }
    // 剧集优先用上一集/下一集；没有剧集导航时（电影分段）用相邻部分，自动续播同样生效。
    val previousEpisodeId = episodePreviousId
        ?: parts.getOrNull(partIndex - 1)?.id?.takeIf { partIndex > 0 }
    val nextEpisodeId = episodeNextId
        ?: parts.getOrNull(partIndex + 1)?.id?.takeIf { partIndex >= 0 }

    LaunchedEffect(args) {
        mediaId = args.mediaId
    }

    LaunchedEffect(mediaId, args.remoteUrl) {
        if (!args.remoteUrl.isNullOrBlank()) {
            episodePreviousId = null
            episodeNextId = null
            return@LaunchedEffect
        }
        val navigation = mediaRepository.getEpisodeNavigationIds(mediaId)
        episodePreviousId = navigation.previousEpisodeId
        episodeNextId = navigation.nextEpisodeId
    }

    LaunchedEffect(args.mediaId, args.remoteUrl, args.partsOwnerId) {
        if (!args.remoteUrl.isNullOrBlank()) return@LaunchedEffect
        // 从分段卡片进入时用详情页的主条目；其余入口启动的就是主条目（或非分段影片）本身。
        val ownerId = args.partsOwnerId?.takeIf { it.isNotBlank() } ?: args.mediaId
        parts = mediaRepository.getPartsPlaylist(ownerId)
    }

    LaunchedEffect(Unit) {
        context.findActivity()?.let { applyPlayerPipParams(it, playing = true) }
    }

    val isLaunchItem = mediaId == args.mediaId
    PlayerScreen(
        mediaId = mediaId,
        remoteMediaUrl = args.remoteUrl,
        remoteMediaTitle = args.remoteTitle,
        preferredAudioStreamIndex = args.audioStreamIndex,
        preferredSubtitleStreamIndex = args.subtitleStreamIndex,
        // 版本、起播位置只属于启动条目；切到其它分段/剧集后沿用会请求到别的条目的源或位置。
        startFromBeginning = args.startFromBeginning && isLaunchItem,
        initialSeekPositionMs = args.seekPositionMs.takeIf { isLaunchItem },
        mediaSourceId = args.mediaSourceId.takeIf { isLaunchItem },
        previousEpisodeId = previousEpisodeId,
        nextEpisodeId = nextEpisodeId,
        onWatchPreviousEpisode = { episodeId -> mediaId = episodeId },
        onWatchNextEpisode = { episodeId -> mediaId = episodeId },
        playlist = parts,
        onPlaylistItemSelected = { partId -> mediaId = partId },
        onPlaybackCompleted = { completedId ->
            val nextId = nextEpisodeId
            // 「自动播放下一集」关闭时停在结尾，由用户手动选择（与 iOS 设置一致）。
            if (!nextId.isNullOrBlank() && nextId != completedId && PlayerBehaviorPreferences(context).autoPlayNext) {
                mediaId = nextId
            }
        },
        onBackPressed = {
            context.findActivity()?.finish()
        }
    )
}
