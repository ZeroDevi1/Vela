package com.vela.app.ui.screens.music

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.vela.app.ui.screens.library.LibraryArtwork
import com.vela.app.ui.screens.library.librarySubtitle
import com.vela.data.model.BaseItemDto
import com.vela.data.navidrome.NavidromeSession
import com.vela.shared.R
import com.vela.shared.ui.theme.velaMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

internal fun musicTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

@UnstableApi
@Composable
internal fun MusicMiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state by MusicPlayback.state.collectAsStateWithLifecycle()
    val item = state.item ?: return
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    val bufferingLabel = stringResource(R.string.music_player_buffering)
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        color = colors.surfaceContainerHigh,
        shape = shape,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                Surface(
                    onClick = onOpen,
                    modifier = Modifier.weight(1f).miniPlayerSwipe(onOpen),
                    color = Color.Transparent
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LibraryArtwork(state.session, item, Modifier.size(44.dp), shape = MaterialTheme.shapes.small)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(item.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (state.error != null) stringResource(R.string.music_player_failed) else item.librarySubtitle(),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
                IconButton(onClick = MusicPlayback::toggle) {
                    if (state.buffering && state.playWhenReady) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp).semantics { contentDescription = bufferingLabel },
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            stringResource(if (state.playWhenReady) R.string.pause else R.string.play)
                        )
                    }
                }
                IconButton(onClick = MusicPlayback::next, enabled = state.queue.size > 1) {
                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.music_player_next))
                }
            }
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp)
            )
        }
    }
}

private enum class PlayerPage(@StringRes val label: Int) {
    RECOMMENDATIONS(R.string.navidrome_recommendations),
    COVER(R.string.navidrome_cover),
    LYRICS(R.string.navidrome_lyrics)
}

private enum class PlayerSheet { QUEUE, OPTIONS }

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
internal fun MusicNowPlayingScreen(onBack: () -> Unit) {
    val state by MusicPlayback.state.collectAsStateWithLifecycle()
    val item = state.item
    val session = state.session
    if (item == null || session == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val navidrome = session as? NavidromeSession
    val pages = remember(navidrome != null) {
        if (navidrome == null) listOf(PlayerPage.COVER, PlayerPage.LYRICS) else PlayerPage.entries.toList()
    }
    val pager = key(navidrome != null) {
        rememberPagerState(initialPage = pages.indexOf(PlayerPage.COVER)) { pages.size }
    }
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.velaMotion
    var sheet by rememberSaveable { mutableStateOf<PlayerSheet?>(null) }

    var favorite by remember(session, item.id) { mutableStateOf(item.userData?.isFavorite == true) }
    var savingFavorite by remember(session, item.id) { mutableStateOf(false) }
    var favoriteFailed by remember(session, item.id) { mutableStateOf(false) }

    fun toggleFavorite() {
        if (savingFavorite) return
        savingFavorite = true
        favoriteFailed = false
        scope.launch {
            try {
                session.favorite(item, !favorite)
                favorite = !favorite
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                favoriteFailed = true
            } finally {
                savingFavorite = false
            }
        }
    }

    val content: @Composable (Modifier) -> Unit = { pageModifier ->
        HorizontalPager(
            state = pager,
            modifier = pageModifier,
            verticalAlignment = Alignment.Top
        ) { index ->
            when (pages[index]) {
                PlayerPage.COVER -> PlayerCover(session, item)
                PlayerPage.LYRICS -> key(session, item.id) {
                    PlayerLyrics(session, item, state.positionMs, state.canSeek)
                }
                PlayerPage.RECOMMENDATIONS -> if (navidrome != null) NavidromeRecommendations(navidrome, item)
            }
        }
    }
    val currentContent by rememberUpdatedState(content)
    // 横竖屏共用同一份分页内容，旋转时保留已加载的歌词和列表位置。
    val pagesContent = remember { movableContentOf { modifier: Modifier -> currentContent(modifier) } }
    val controls: @Composable (Modifier, Boolean) -> Unit = { controlModifier, compact ->
        PlaybackControls(
            state = state,
            item = item,
            favorite = favorite,
            favoriteEnabled = !savingFavorite,
            favoriteFailed = favoriteFailed,
            onFavorite = ::toggleFavorite,
            onOpenQueue = { sheet = PlayerSheet.QUEUE },
            compact = compact,
            modifier = controlModifier
        )
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PlayerBackdrop(session, item)
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                PlayerHeader(
                    pages = pages,
                    selected = pager.currentPage.coerceIn(pages.indices),
                    onSelect = { index ->
                        scope.launch { pager.animateScrollToPage(index, animationSpec = motion.defaultSpatialSpec()) }
                    },
                    hasSleepTimer = state.sleepAtMs != null,
                    onOptions = { sheet = PlayerSheet.OPTIONS },
                    onClose = onBack
                )
            }
        ) { padding ->
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentAlignment = Alignment.Center
            ) {
                if (maxWidth >= 600.dp && maxWidth > maxHeight) {
                    Row(
                        Modifier.fillMaxHeight().widthIn(max = 1040.dp).fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pagesContent(Modifier.weight(1f).fillMaxHeight())
                        controls(Modifier.weight(1f).verticalScroll(rememberScrollState()), true)
                    }
                } else if (maxHeight < 400.dp) {
                    val pageHeight = (maxHeight * .58f).coerceAtLeast(120.dp)
                    Column(
                        Modifier.fillMaxHeight().widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        pagesContent(Modifier.fillMaxWidth().height(pageHeight))
                        controls(Modifier.fillMaxWidth().padding(horizontal = 24.dp), true)
                    }
                } else {
                    val horizontalPadding = if (maxWidth < 360.dp) 16.dp else 28.dp
                    val compact = maxHeight < 600.dp
                    Column(
                        Modifier.fillMaxHeight().widthIn(max = 520.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        pagesContent(Modifier.fillMaxWidth().weight(1f))
                        controls(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding), compact)
                    }
                }
            }
        }
    }

    when (sheet) {
        PlayerSheet.QUEUE -> PlayerQueueSheet(state, onDismiss = { sheet = null }, onStop = {
            MusicPlayback.stop()
            sheet = null
        })
        PlayerSheet.OPTIONS -> PlayerOptionsSheet(state, onDismiss = { sheet = null })
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerHeader(
    pages: List<PlayerPage>,
    selected: Int,
    onSelect: (Int) -> Unit,
    hasSleepTimer: Boolean,
    onOptions: () -> Unit,
    onClose: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(if (pages[selected] == PlayerPage.COVER) R.string.player_playlist_now_playing else pages[selected].label),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                    pages.forEachIndexed { index, page ->
                        val label = stringResource(page.label)
                        val isSelected = index == selected
                        val width by animateDpAsState(
                            if (isSelected) 18.dp else 6.dp,
                            animationSpec = MaterialTheme.velaMotion.fastSpatialSpec(),
                            label = "musicPageIndicator"
                        )
                        Box(
                            Modifier.size(48.dp)
                                .clip(CircleShape)
                                .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(index) })
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier.size(width, 5.dp).background(
                                    if (isSelected) colors.primary else colors.onSurfaceVariant.copy(alpha = .55f),
                                    CircleShape
                                )
                            )
                        }
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.music_player_collapse))
            }
        },
        actions = {
            IconButton(onClick = onOptions) {
                Icon(
                    if (hasSleepTimer) Icons.Rounded.Bedtime else Icons.Rounded.MoreHoriz,
                    stringResource(if (hasSleepTimer) R.string.music_player_timer_active else R.string.player_settings_title),
                    tint = if (hasSleepTimer) colors.primary else colors.onSurfaceVariant
                )
            }
        },
        expandedHeight = 76.dp,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
private fun PlaybackControls(
    state: MusicState,
    item: BaseItemDto,
    favorite: Boolean,
    favoriteEnabled: Boolean,
    favoriteFailed: Boolean,
    onFavorite: () -> Unit,
    onOpenQueue: () -> Unit,
    compact: Boolean,
    modifier: Modifier
) {
    val colors = MaterialTheme.colorScheme
    val bufferingLabel = stringResource(R.string.music_player_buffering)
    val positionLabel = stringResource(R.string.music_player_progress)
    val repeatLabel = when (state.repeatMode) {
        Player.REPEAT_MODE_ALL -> R.string.music_player_repeat_all
        Player.REPEAT_MODE_ONE -> R.string.music_player_repeat_one
        else -> R.string.music_player_repeat_off
    }
    var scrub by remember(state.session, state.index, item.id) { mutableStateOf<Float?>(null) }
    val position = scrub?.toLong() ?: state.positionMs
    val artist = item.artists.orEmpty().filter { it.isNotBlank() }.joinToString(" / ").ifBlank { item.albumArtist.orEmpty() }
    val subtitle = listOf(artist, item.album.orEmpty()).filter { it.isNotBlank() }.distinct().joinToString(" · ")

    Column(
        modifier.padding(top = 8.dp, bottom = if (compact) 12.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.name.orEmpty().ifBlank { stringResource(R.string.music_player_untitled) },
                    style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            IconToggleButton(checked = favorite, onCheckedChange = { onFavorite() }, enabled = favoriteEnabled) {
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(if (favorite) R.string.item_action_remove_favorite else R.string.item_action_add_favorite),
                    tint = when {
                        !favoriteEnabled -> colors.onSurface.copy(alpha = .38f)
                        favorite -> colors.primary
                        else -> colors.onSurfaceVariant
                    }
                )
            }
        }

        val message = state.error ?: state.reportingError
            ?: if (favoriteFailed) stringResource(R.string.music_player_favorite_error) else null
        if (message != null) {
            Text(
                message,
                color = colors.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column {
            Slider(
                value = position.toFloat().coerceIn(0f, state.durationMs.coerceAtLeast(1).toFloat()),
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { if (state.canSeek) MusicPlayback.seek(it.toLong()) }
                    scrub = null
                },
                valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(),
                enabled = state.canSeek && state.durationMs > 0,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = positionLabel }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val counterStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                Text(musicTime(position), style = counterStyle, color = colors.onSurfaceVariant)
                Text(musicTime(state.durationMs), style = counterStyle, color = colors.onSurfaceVariant)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = MusicPlayback::repeat) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    stringResource(repeatLabel),
                    tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) colors.onSurfaceVariant else colors.primary
                )
            }
            IconButton(onClick = MusicPlayback::previous) {
                Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.music_player_previous), Modifier.size(30.dp))
            }
            FilledIconButton(
                onClick = MusicPlayback::toggle,
                modifier = Modifier.size(if (compact) 60.dp else 72.dp),
                shape = CircleShape
            ) {
                if (state.buffering && state.playWhenReady) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).semantics { contentDescription = bufferingLabel },
                        strokeWidth = 2.dp,
                        color = colors.onPrimary
                    )
                } else {
                    Icon(
                        if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (state.playWhenReady) R.string.pause else R.string.play),
                        Modifier.size(36.dp)
                    )
                }
            }
            IconButton(onClick = MusicPlayback::next, enabled = state.queue.size > 1) {
                Icon(Icons.Rounded.SkipNext, stringResource(R.string.music_player_next), Modifier.size(30.dp))
            }
            IconButton(onClick = onOpenQueue) {
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    stringResource(R.string.music_player_queue_count, state.queue.size)
                )
            }
        }
    }
}

@UnstableApi
@Composable
private fun Modifier.miniPlayerSwipe(onOpen: () -> Unit): Modifier {
    val open by rememberUpdatedState(onOpen)
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    return pointerInput(threshold) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragEnd = {
                if (distance < -threshold) MusicPlayback.next()
                else if (distance > threshold) MusicPlayback.previous()
            }
        ) { change, amount ->
            change.consume()
            distance += amount
        }
    }.pointerInput(threshold) {
        var distance = 0f
        detectVerticalDragGestures(
            onDragStart = { distance = 0f },
            onDragEnd = { if (distance < -threshold) open() }
        ) { change, amount ->
            change.consume()
            distance += amount
        }
    }
}
