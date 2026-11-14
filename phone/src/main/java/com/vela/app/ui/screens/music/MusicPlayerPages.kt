package com.vela.app.ui.screens.music

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.vela.app.ui.screens.library.LibraryArtwork
import com.vela.app.ui.screens.library.LibraryError
import com.vela.data.model.BaseItemDto
import com.vela.data.model.LyricLine
import com.vela.data.navidrome.NavidromeSession
import com.vela.data.repository.LibraryMediaSession
import com.vela.data.repository.MusicMediaSession
import com.vela.shared.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PlayerCover(session: MusicMediaSession, item: BaseItemDto) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val side = minOf(maxWidth, maxHeight, 432.dp)
        Surface(
            modifier = Modifier.size(side),
            shape = shape,
            color = colors.surfaceContainerHigh,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .55f)),
            shadowElevation = 14.dp
        ) {
            LibraryArtwork(session, item, Modifier.fillMaxSize(), shape = shape)
        }
    }
}

@Composable
internal fun PlayerBackdrop(session: MusicMediaSession, item: BaseItemDto) {
    val colors = MaterialTheme.colorScheme
    // Android 12 以下不支持 RenderEffect，直接用主题渐变作为背景。
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var artwork by remember(session, item, canBlur) {
        mutableStateOf<Any?>(if (canBlur) (session as? LibraryMediaSession)?.artworkUrl(item, width = 144) else null)
    }
    LaunchedEffect(session, item, canBlur) {
        if (!canBlur || session !is NavidromeSession) return@LaunchedEffect
        try {
            artwork = session.cover(item, width = 144)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 背景只是装饰，取图失败时仍保留主题渐变。
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds().background(colors.surface)) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surface))))
        if (artwork != null) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().graphicsLayer {
                    scaleX = 1.12f
                    scaleY = 1.12f
                    alpha = .65f
                }.blur(56.dp)
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to colors.surface.copy(alpha = .78f),
                    .36f to colors.surface.copy(alpha = .32f),
                    .66f to colors.surface.copy(alpha = .76f),
                    1f to colors.surface
                )
            )
        )
    }
}

@UnstableApi
@Composable
internal fun PlayerLyrics(session: MusicMediaSession, item: BaseItemDto, position: Long, canSeek: Boolean) {
    var lyrics by remember(session, item.id) { mutableStateOf<List<LyricLine>?>(null) }
    var failed by remember(session, item.id) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var following by remember(session, item.id) { mutableStateOf(true) }
    val list = rememberLazyListState()
    val userDragging by list.interactionSource.collectIsDraggedAsState()
    val colors = MaterialTheme.colorScheme
    val lyricStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp, letterSpacing = 0.sp)
    val activeStyle = lyricStyle.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)

    LaunchedEffect(session, item.id, retry) {
        failed = false
        try {
            lyrics = session.lyrics(item)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    val lines = lyrics.orEmpty()
    val active = lines.indexOfLast { it.startMs?.let { start -> start <= position } == true }
    LaunchedEffect(userDragging) {
        if (userDragging) following = false
    }
    LaunchedEffect(active, following) {
        if (following && active >= 0) list.animateScrollToItem((active - 2).coerceAtLeast(0))
    }
    Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        when {
            failed -> LibraryError(stringResource(R.string.music_player_lyrics_error)) { retry++ }
            lyrics == null -> CircularProgressIndicator()
            lines.isEmpty() -> Text(stringResource(R.string.music_player_no_lyrics), color = colors.onSurfaceVariant)
            else -> LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val edge = (28.dp.toPx() / size.height.coerceAtLeast(1f)).coerceAtMost(.25f)
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent, edge to Color.Black,
                                1f - edge to Color.Black, 1f to Color.Transparent
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    },
                contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 26.dp, bottom = 52.dp)
            ) {
                itemsIndexed(lines) { index, line ->
                    val highlighted = index == active
                    val start = line.startMs
                    Surface(
                        onClick = {
                            if (start != null) {
                                MusicPlayback.seek(start)
                                following = true
                            }
                        },
                        enabled = start != null && canSeek,
                        shape = MaterialTheme.shapes.small,
                        color = Color.Transparent
                    ) {
                        Text(
                            line.text,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 8.dp),
                            style = if (highlighted) activeStyle else lyricStyle,
                            color = if (highlighted) colors.primary else colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (!failed && !following && active >= 0) {
            FilledTonalButton(
                onClick = { following = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 4.dp)
            ) {
                Text(stringResource(R.string.music_player_follow_lyrics))
            }
        }
    }
}

@UnstableApi
@Composable
internal fun NavidromeRecommendations(session: NavidromeSession, item: BaseItemDto) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var songs by remember(session, item.id) { mutableStateOf<List<BaseItemDto>?>(null) }
    var error by remember(session, item.id) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val fallback = stringResource(R.string.connection_failed)
    LaunchedEffect(session, item.id, retry) {
        error = null
        try {
            songs = session.similarTracks(item)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = fallback
        }
    }
    val recommendations = songs
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            error != null -> LibraryError(error.orEmpty()) { retry++ }
            recommendations == null -> CircularProgressIndicator()
            recommendations.isEmpty() -> Text(stringResource(R.string.navidrome_recommendations_empty))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(recommendations) { index, track ->
                    MusicTrackRow(session, track, onClick = {
                        scope.launch {
                            try {
                                MusicPlayback.play(context, session, recommendations, index)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                error = fallback
                            }
                        }
                    })
                }
            }
        }
    }
}
