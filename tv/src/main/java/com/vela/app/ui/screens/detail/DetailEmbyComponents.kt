package com.vela.app.ui.screens.detail

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.app.ui.components.TvAccentGreen
import com.vela.app.ui.components.tvCardFocus
import com.vela.data.model.BaseItemDto
import com.vela.data.repository.MediaRepository
import com.vela.shared.R
import com.vela.shared.util.image.JellyfinPosterImage
import java.util.Date
import kotlinx.coroutines.flow.first

/**
 * 详情页左侧海报：电影、剧集用竖版海报，单集用 16:9 剧照。图片缺失时显示标题占位。
 */
@Composable
internal fun DetailPoster(
    item: BaseItemDto,
    mediaRepository: MediaRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val landscape = item.type == "Episode"
    val imageUrl by produceState<String?>(null, item.id) {
        val itemId = item.id ?: return@produceState
        value = runCatching {
            mediaRepository.getImageUrl(
                itemId = itemId,
                imageType = "Primary",
                width = if (landscape) 520 else 360,
                height = if (landscape) 292 else 540,
                quality = 90,
                enableImageEnhancers = false
            ).first()
        }.getOrNull()
    }
    Box(
        modifier = modifier
            .width(if (landscape) DetailLandscapePosterWidth else DetailPosterWidth)
            .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1C1C1F))
    ) {
        Text(
            text = item.name.orEmpty(),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(12.dp)
        )
        JellyfinPosterImage(
            imageUrl = imageUrl,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            context = context,
            contentScale = ContentScale.Crop
        )
    }
}

private val DetailPosterWidth = 190.dp
private val DetailLandscapePosterWidth = 300.dp

/** Emby TV 式操作按钮的描述；[active] 为 true 时图标显示为绿色（已收藏、已观看）。 */
internal data class DetailAction(
    val icon: ImageVector,
    val label: String,
    val active: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 一排圆形操作按钮，按钮下方是文字。获得焦点的按钮变为白底并放大。
 *
 * @param firstFocusRequester 挂在第一个按钮上，页面进入时把焦点交给它。
 */
@Composable
internal fun DetailActionRow(
    actions: List<DetailAction>,
    firstFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Top
    ) {
        actions.forEachIndexed { index, action ->
            DetailActionButton(
                action = action,
                modifier = if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier
            )
        }
    }
}

@Composable
private fun DetailActionButton(action: DetailAction, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "detail_action_scale")
    val background by animateColorAsState(
        if (focused) Color.White else Color.White.copy(alpha = 0.16f),
        label = "detail_action_bg"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(
            modifier = modifier
                .size(54.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .onFocusChanged { focused = it.isFocused }
                .clip(CircleShape)
                .background(background)
                .clickable(onClick = action.onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.label,
                tint = when {
                    focused -> Color.Black
                    action.active -> TvAccentGreen
                    else -> Color.White
                },
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = action.label,
            color = if (focused) Color.White else Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * 预计结束时间：现在加上剩余时长，按系统 12/24 小时制格式化。
 * 没有时长或已播完时返回 null。
 */
@Composable
internal fun rememberEndsAtText(runtimeTicks: Long?, positionTicks: Long): String? {
    val context = LocalContext.current
    val remainingTicks = runtimeTicks?.minus(positionTicks)?.takeIf { it > 0 } ?: return null
    // 1 tick = 100ns，与 Jellyfin RunTimeTicks 单位一致
    val endsAt = System.currentTimeMillis() + remainingTicks / 10_000
    return stringResource(R.string.tv_detail_ends_at, DateFormat.getTimeFormat(context).format(Date(endsAt)))
}

/** 剧集详情的「季」横排：季海报与名称，确认键打开该季的选集面板。 */
@Composable
internal fun SeasonPosterRow(
    seasons: List<BaseItemDto>,
    mediaRepository: MediaRepository,
    onSeasonClick: (Int) -> Unit
) {
    if (seasons.isEmpty()) return
    val context = LocalContext.current
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(
            text = stringResource(R.string.tv_detail_seasons),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)
        ) {
            itemsIndexed(seasons, key = { index, season -> season.id ?: "season_$index" }) { index, season ->
                val posterUrl by produceState<String?>(null, season.id) {
                    val seasonId = season.id ?: return@produceState
                    value = runCatching {
                        mediaRepository.getImageUrl(itemId = seasonId, imageType = "Primary", width = 240, height = 360, enableImageEnhancers = false).first()
                    }.getOrNull()
                }
                val seasonName = season.name ?: stringResource(R.string.detail_season_fallback)
                Column(modifier = Modifier.width(120.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .tvCardFocus()
                            .background(Color(0xFF1C1C1F))
                            .clickable { onSeasonClick(index) }
                    ) {
                        Text(
                            text = seasonName,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).padding(8.dp)
                        )
                        JellyfinPosterImage(
                            imageUrl = posterUrl,
                            contentDescription = seasonName,
                            modifier = Modifier.fillMaxSize(),
                            context = context,
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = seasonName,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
