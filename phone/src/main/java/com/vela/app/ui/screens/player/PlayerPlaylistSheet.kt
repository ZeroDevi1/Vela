package com.vela.app.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vela.data.model.BaseItemDto
import com.vela.data.repository.MediaRepository
import com.vela.shared.R
import com.vela.shared.util.image.JellyfinPosterImage
import com.vela.shared.util.image.imageTagFor
import com.vela.shared.util.image.rememberImageUrl

/**
 * 播放列表：多 CD / 多分段影片的各部分。当前部分高亮，点选其他部分原地切换播放。
 *
 * @param items 按播放顺序排列的各部分，第一项为主条目
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerPlaylistSheet(
    items: List<BaseItemDto>,
    currentItemId: String,
    mediaRepository: MediaRepository,
    onDismiss: () -> Unit,
    onItemSelected: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Text(
            text = stringResource(R.string.player_playlist_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.navigationBarsPadding()
        ) {
            itemsIndexed(items, key = { index, item -> item.id ?: "part:$index" }) { index, item ->
                PlaylistRow(
                    index = index,
                    item = item,
                    current = item.id == currentItemId,
                    mediaRepository = mediaRepository,
                    onClick = { item.id?.let(onItemSelected) }
                )
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    index: Int,
    item: BaseItemDto,
    current: Boolean,
    mediaRepository: MediaRepository,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val imageUrl = rememberImageUrl(
        itemId = item.id,
        imageType = "Primary",
        width = 320,
        quality = 85,
        imageTag = item.imageTagFor("Primary"),
        mediaRepository = mediaRepository
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (current) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(112.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                JellyfinPosterImage(
                    imageUrl = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    context = context,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.player_part_label, index + 1),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (current) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                val details = listOfNotNull(
                    item.name?.takeIf { it.isNotBlank() },
                    item.runTimeTicks?.takeIf { it > 0 }?.let { ticks ->
                        stringResource(R.string.player_part_minutes, (ticks / 600_000_000L).toInt().coerceAtLeast(1))
                    }
                ).joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (current) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = stringResource(R.string.player_playlist_now_playing),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(24.dp)
                )
            }
        }
    }
}
