package com.vela.app.ui.screens.music

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.vela.app.ui.screens.library.LibraryArtwork
import com.vela.app.ui.screens.library.librarySubtitle
import com.vela.shared.R

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
internal fun PlayerQueueSheet(state: MusicState, onDismiss: () -> Unit, onStop: () -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (state.index - 1).coerceAtLeast(0))
    val colors = MaterialTheme.colorScheme
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = landscape),
        containerColor = colors.surfaceContainerLow
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.music_player_queue_count, state.queue.size),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge
            )
            TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(state.queue) { index, track ->
                val current = index == state.index
                Surface(
                    onClick = { MusicPlayback.seekIndex(index); onDismiss() },
                    shape = MaterialTheme.shapes.medium,
                    color = if (current) colors.secondaryContainer else Color.Transparent
                ) {
                    ListItem(
                        headlineContent = { Text(track.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(track.librarySubtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            LibraryArtwork(state.session, track, Modifier.size(46.dp), shape = MaterialTheme.shapes.small)
                        },
                        trailingContent = if (current) {
                            { Icon(Icons.Rounded.GraphicEq, stringResource(R.string.player_playlist_now_playing), tint = colors.onSecondaryContainer) }
                        } else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@UnstableApi
@Composable
internal fun PlayerOptionsSheet(state: MusicState, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.player_settings_title), style = MaterialTheme.typography.titleLarge)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.navidrome_shuffle), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.shuffle, onCheckedChange = { MusicPlayback.shuffle() })
            }
            HorizontalDivider(color = colors.outlineVariant)
            Text(stringResource(R.string.player_playback_speed), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(.75f, 1f, 1.25f, 1.5f).forEach { speed ->
                    FilterChip(
                        selected = speed == state.speed,
                        onClick = { MusicPlayback.speed(speed) },
                        label = { Text("${speed}×") }
                    )
                }
            }
            Text(stringResource(R.string.player_sleep_timer), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.sleepAtMs == null,
                    onClick = { MusicPlayback.sleep(null) },
                    label = { Text(stringResource(R.string.player_sleep_timer_off)) }
                )
                for (minutes in listOf(15, 30, 60)) {
                    AssistChip(
                        onClick = { MusicPlayback.sleep(minutes); onDismiss() },
                        label = { Text(stringResource(R.string.player_sleep_timer_minutes, minutes)) }
                    )
                }
            }
            Text(stringResource(R.string.player_settings_audio_quality), style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(R.string.music_player_audio_original, R.string.music_player_audio_compatible).forEachIndexed { index, label ->
                    val compatible = index == 1
                    SegmentedButton(
                        selected = state.compatibleAudio == compatible,
                        onClick = {
                            if (state.compatibleAudio != compatible) {
                                MusicPlayback.retryAudio(compatible)
                                onDismiss()
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
    }
}
