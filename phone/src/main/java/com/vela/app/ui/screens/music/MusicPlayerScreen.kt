package com.vela.app.ui.screens.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.vela.app.ui.screens.library.LibraryArtwork
import com.vela.app.ui.screens.library.LibraryError
import com.vela.app.ui.screens.library.librarySubtitle
import com.vela.data.model.LyricLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.Locale

internal fun musicTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

@UnstableApi
@Composable
internal fun MusicMiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state by MusicPlayback.state.collectAsState()
    val item = state.item ?: return
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(MaterialTheme.shapes.extraLarge), color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 5.dp) {
        Column {
            LinearProgressIndicator(progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LibraryArtwork(state.session, item, Modifier.size(44.dp))
                Column(Modifier.weight(1f).musicSwipe(onPrevious = MusicPlayback::previous, onNext = MusicPlayback::next, onUp = onOpen).padding(horizontal = 12.dp)) {
                    Text(item.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(if (state.error != null) "播放失败 · 点击查看" else item.librarySubtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = MusicPlayback::toggle) {
                    if (state.buffering) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "暂停音乐" else "播放音乐")
                }
                IconButton(onClick = MusicPlayback::next, enabled = state.queue.size > 1) { Icon(Icons.Default.SkipNext, "下一首") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
internal fun MusicNowPlayingScreen(onBack: () -> Unit) {
    val state by MusicPlayback.state.collectAsState()
    val item = state.item
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var lyrics by remember(item?.id) { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricLoading by remember(item?.id) { mutableStateOf(true) }
    var lyricError by remember(item?.id) { mutableStateOf<String?>(null) }
    var lyricsRetry by remember { mutableIntStateOf(0) }
    var favorite by remember(item?.id) { mutableStateOf(item?.userData?.isFavorite == true) }
    var actionError by remember(item?.id) { mutableStateOf<String?>(null) }
    var seek by remember(item?.id) { mutableStateOf<Float?>(null) }
    val lyricList = rememberLazyListState()
    val activeLine = lyrics.indexOfLast { it.startMs?.let { start -> start <= state.positionMs } == true }
    LaunchedEffect(item?.id, state.session, lyricsRetry) {
        val session = state.session ?: return@LaunchedEffect
        val id = item?.id ?: return@LaunchedEffect
        lyricLoading = true; lyricError = null
        try { lyrics = session.lyrics(id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { lyricError = e.message ?: "歌词加载失败" }
        finally { lyricLoading = false }
    }
    LaunchedEffect(activeLine, showLyrics) {
        if (showLyrics && activeLine >= 0 && !lyricList.isScrollInProgress) lyricList.animateScrollToItem((activeLine - 2).coerceAtLeast(0))
    }
    BackHandler { onBack() }
    if (item == null) { LaunchedEffect(Unit) { onBack() }; return }
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text("正在播放", style = MaterialTheme.typography.titleMedium); Text(if (state.compatibleAudio) "兼容音频 · MP3" else "原始音质", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "收起播放器") } },
            actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Default.MoreHoriz, "播放设置") } })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val compact = maxHeight < 600.dp
            Column(Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier).background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f), MaterialTheme.colorScheme.surface))).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showLyrics) Box((if (compact) Modifier.height(240.dp) else Modifier.weight(1f)).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        lyricLoading -> CircularProgressIndicator()
                        lyricError != null -> LibraryError(lyricError.orEmpty()) { lyricsRetry++ }
                        lyrics.isEmpty() -> Text("暂无歌词", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> LazyColumn(state = lyricList, contentPadding = PaddingValues(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            itemsIndexed(lyrics) { index, line -> Text(line.text, Modifier.fillMaxWidth().clickable(enabled = line.startMs != null) { line.startMs?.let(MusicPlayback::seek) },
                                color = if (index == activeLine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.titleLarge, fontWeight = if (index == activeLine) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center) }
                        }
                    }
                } else Box((if (compact) Modifier.height(240.dp) else Modifier.weight(1f)).fillMaxWidth().padding(vertical = if (compact) 8.dp else 24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f, fill = false).aspectRatio(1f, matchHeightConstraintsFirst = true).sizeIn(maxWidth = 420.dp, maxHeight = 420.dp)
                            .musicSwipe(MusicPlayback::previous, MusicPlayback::next, onUp = { queue = true }, onDown = onBack)
                            .pointerInput(Unit) { detectTapGestures(onTap = { showLyrics = true }, onDoubleTap = { MusicPlayback.toggle() }) }, contentAlignment = Alignment.Center) {
                            VinylRecord(state.playing && !state.buffering) {
                                LibraryArtwork(state.session, item, Modifier.fillMaxSize(.56f).clip(CircleShape))
                            }
                        }
                        Text("轻点看歌词 · 双击播放/暂停 · 左右滑动切歌", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name.orEmpty(), style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.artists?.joinToString(" / ") ?: item.albumArtist ?: item.album.orEmpty(), Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { scope.launch {
                        try { state.session?.favorite(requireNotNull(item.id), !favorite); favorite = !favorite }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { actionError = e.message ?: "收藏失败" }
                    } }) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏歌曲", tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                state.reportingError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Slider(value = seek ?: state.positionMs.toFloat().coerceAtMost(state.durationMs.toFloat()),
                    onValueChange = { seek = it }, onValueChangeFinished = { seek?.let { MusicPlayback.seek(it.toLong()) }; seek = null },
                    valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = state.durationMs > 0)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(musicTime((seek?.toLong() ?: state.positionMs)), style = MaterialTheme.typography.labelMedium)
                    Text(musicTime(state.durationMs), style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 6.dp else 18.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = MusicPlayback::shuffle) { Icon(Icons.Default.Shuffle, "随机播放", tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = MusicPlayback::previous) { Icon(Icons.Default.SkipPrevious, "上一首", Modifier.size(32.dp)) }
                    FilledIconButton(onClick = MusicPlayback::toggle, Modifier.size(if (compact) 60.dp else 72.dp), shape = CircleShape) {
                        if (state.buffering) CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        else Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "暂停" else "播放", Modifier.size(36.dp))
                    }
                    IconButton(onClick = MusicPlayback::next, enabled = state.queue.size > 1) { Icon(Icons.Default.SkipNext, "下一首", Modifier.size(32.dp)) }
                    IconButton(onClick = MusicPlayback::repeat) { Icon(if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "循环模式", tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { showLyrics = !showLyrics }) { Icon(Icons.Default.Lyrics, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(if (showLyrics) "封面" else "歌词") }
                    TextButton(onClick = { queue = true }) { Icon(Icons.Default.QueueMusic, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("队列 ${state.queue.size}") }
                    TextButton(onClick = { settings = true }) { Icon(Icons.Default.Bedtime, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(if (state.sleepAtMs != null) "定时中" else "定时") }
                }
            }
        }
    }
    if (queue) ModalBottomSheet(onDismissRequest = { queue = false }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("播放队列 · ${state.queue.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { MusicPlayback.stop(); queue = false; onBack() }) { Text("停止播放") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(state.queue) { index, track -> ListItem(headlineContent = { Text(track.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(track.librarySubtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { if (index == state.index) Icon(Icons.Default.GraphicEq, "正在播放", tint = MaterialTheme.colorScheme.primary) else Text("${index + 1}") },
                modifier = Modifier.clickable { MusicPlayback.seekIndex(index); queue = false }) }
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("播放设置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("睡眠定时", style = MaterialTheme.typography.titleSmall)
            Row { listOf(15, 30, 60).forEach { minutes -> TextButton(onClick = { MusicPlayback.sleep(minutes); settings = false }) { Text("${minutes}分钟") } } }
            if (state.sleepAtMs != null) TextButton(onClick = { MusicPlayback.sleep(null); settings = false }) { Text("关闭定时") }
            Text("播放速度", style = MaterialTheme.typography.titleSmall)
            Row { listOf(.75f, 1f, 1.25f, 1.5f).forEach { speed -> TextButton(onClick = { MusicPlayback.speed(speed); settings = false }, contentPadding = PaddingValues(4.dp)) { Text("${speed}×") } } }
            TextButton(onClick = { MusicPlayback.retryCompatible(); settings = false }) { Text("使用兼容音频重新播放") }
            Text("兼容音频由服务器转为 MP3，适用于设备无法解码的格式。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text("完成") } })
}

/** 手势仅绑定封面和迷你播放器标题，避免抢占歌词滚动与进度条拖动。 */
@Composable
private fun Modifier.musicSwipe(onPrevious: () -> Unit, onNext: () -> Unit, onUp: () -> Unit, onDown: () -> Unit = {}): Modifier {
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val up by rememberUpdatedState(onUp)
    val down by rememberUpdatedState(onDown)
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    return pointerInput(threshold) {
        var distance = 0f
        detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f },
            onDragEnd = { if (distance < -threshold) next() else if (distance > threshold) previous() }) { change, amount ->
            change.consume(); distance += amount
        }
    }.pointerInput(threshold) {
        var distance = 0f
        detectVerticalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f },
            onDragEnd = { if (distance < -threshold) up() else if (distance > threshold) down() }) { change, amount ->
            change.consume(); distance += amount
        }
    }
}

@Composable
private fun VinylRecord(playing: Boolean, artwork: @Composable () -> Unit) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) while (isActive) {
            rotation.animateTo(rotation.value + 360f, tween(24_000, easing = LinearEasing))
            rotation.snapTo(rotation.value % 360f)
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation.value }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2
            drawCircle(Color(0xFF303139), radius)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF34353C), Color(0xFF101116))), radius * .98f)
            for (index in 0..15) drawCircle(Color.White.copy(alpha = .07f), radius * (.60f + index * .024f), style = Stroke(1.dp.toPx()))
        }
        artwork()
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF141519)))
    }
}
