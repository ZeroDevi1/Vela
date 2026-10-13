package com.vela.app.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.vela.data.model.BaseItemDto
import com.vela.data.repository.MediaRepository
import com.vela.shared.R

/**
 * 多 CD 影片的分段卡片；第一张是 [item] 本身。
 *
 * @param mediaSourceId 详情页当前选中的版本；分段随版本变化，切换版本后重新获取。
 */
@Composable
internal fun AdditionalPartsSection(
    item: BaseItemDto,
    mediaSourceId: String?,
    isSeerDetail: Boolean,
    mediaRepository: MediaRepository,
    onPartClick: (BaseItemDto) -> Unit,
    modifier: Modifier = Modifier
) {
    var additionalParts by remember(item.id, mediaSourceId) { mutableStateOf<List<BaseItemDto>>(emptyList()) }

    LaunchedEffect(item.id, mediaSourceId, isSeerDetail) {
        additionalParts = if (isSeerDetail) {
            emptyList()
        } else {
            val itemId = item.id?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            mediaRepository.getAdditionalParts(itemId, mediaSourceId).getOrDefault(emptyList())
        }
    }

    if (additionalParts.isEmpty()) return

    val parts = remember(item.id, additionalParts) {
        listOf(item) + additionalParts
    }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.detail_additional_parts),
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(
                items = parts,
                key = { index, part -> part.id ?: "part_$index" }
            ) { index, part ->
                AdditionalPartCard(
                    part = part,
                    index = index,
                    fallbackItemId = item.id,
                    mediaRepository = mediaRepository,
                    onClick = { onPartClick(part) }
                )
            }
        }
    }
}

@Composable
private fun AdditionalPartCard(
    part: BaseItemDto,
    index: Int,
    fallbackItemId: String?,
    mediaRepository: MediaRepository,
    onClick: () -> Unit
) {
    val imageItemId = part.id?.takeIf { it.isNotBlank() } ?: fallbackItemId.orEmpty()
    val imageUrl by mediaRepository.getImageUrl(
        itemId = imageItemId,
        imageType = "Primary",
        width = 416,
        height = 234,
        imageTag = part.imageTags?.get("Primary") ?: part.imageTags?.get("Thumb")
    ).collectAsState(initial = null)
    val fallbackName = stringResource(R.string.detail_part_index, index + 1)
    val title = part.name?.takeIf { it.isNotBlank() } ?: fallbackName

    Column(
        modifier = Modifier
            .width(208.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF2A2A2A)),
            contentAlignment = Alignment.Center
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = fallbackName,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.62f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = title,
            fontSize = 13.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}
