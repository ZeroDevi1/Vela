package com.vela.app.ui.screens.dashboard.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.vela.app.ui.screens.dashboard.media.TvClock
import com.vela.data.model.BaseItemDto
import com.vela.data.repository.MediaRepository
import com.vela.detail.CodecUtils
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * 首页卡片获得焦点时上报所代表的条目，驱动顶部信息区与背景图（Emby TV 首页样式）。
 * 默认不处理，只有 Emby 式首页会提供实现；卡片无需关心当前是哪种首页。
 */
internal val LocalHomeItemFocus = staticCompositionLocalOf<(BaseItemDto) -> Unit> { {} }

/** 焦点停留多久后才加载详情与背景图（ms）：遥控器连续移动时不为每张经过的卡片发请求。 */
private const val FOCUS_SETTLE_MS = 300L

/** 顶部信息区高度：1080p 电视（约 540dp 高）下留出一行半卡片的空间给下方横排。 */
internal val HomeHeaderHeight: Dp = 196.dp

/**
 * 焦点停稳后的条目详情（含简介、分级、时长）；切换焦点时先返回 null。
 * 结果按 id 缓存在 [cache] 中，同一条目再次获得焦点不重复请求。媒体库等非影视条目不请求。
 */
@Composable
internal fun rememberSettledDetails(
    item: BaseItemDto?,
    mediaRepository: MediaRepository,
    cache: MutableMap<String, BaseItemDto>
): BaseItemDto? {
    val itemId = item?.id
    val details by produceState<BaseItemDto?>(itemId?.let(cache::get), itemId) {
        if (itemId == null || item.type !in DETAIL_TYPES) {
            value = null
            return@produceState
        }
        cache[itemId]?.let { value = it; return@produceState }
        value = null
        delay(FOCUS_SETTLE_MS)
        mediaRepository.getItemById(itemId).getOrNull()?.let { loaded ->
            cache[itemId] = loaded
            value = loaded
        }
    }
    return details
}

private val DETAIL_TYPES = setOf("Movie", "Series", "Episode", "BoxSet", "Video")

/**
 * 全屏背景：焦点条目（单集取所属剧集）的背景图，左侧与下方叠暗色渐变保证文字可读。
 * 焦点停稳后才切换，切换时淡入淡出。
 */
@Composable
internal fun HomeFocusBackdrop(
    item: BaseItemDto?,
    mediaRepository: MediaRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val backdropId = item?.let { if (it.type == "Episode") it.seriesId ?: it.id else it.id }
        ?.takeIf { item.type in DETAIL_TYPES }
    val backdropUrl by produceState<String?>(null, backdropId) {
        if (backdropId == null) {
            value = null
            return@produceState
        }
        delay(FOCUS_SETTLE_MS)
        value = runCatching {
            mediaRepository.getImageUrl(
                itemId = backdropId,
                imageType = "Backdrop",
                width = 1280,
                quality = 80,
                enableImageEnhancers = false
            ).first()
        }.getOrNull()
    }
    Box(modifier = modifier.fillMaxSize()) {
        Crossfade(targetState = backdropUrl, animationSpec = tween(400), label = "home_backdrop") { url ->
            if (url != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(url).crossfade(false).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopEnd,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.92f),
                        0.55f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black.copy(alpha = 0.25f)
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.3f to Color.Black.copy(alpha = 0.35f),
                        0.5f to Color.Black.copy(alpha = 0.85f),
                        1f to Color.Black
                    )
                )
        )
    }
}

/**
 * 顶部固定信息区：左侧是焦点条目的标题（有 Logo 时显示 Logo）、元数据与简介，右上角是 [trailing] 与时钟。
 *
 * @param item 当前焦点条目，null 时只显示右上角。
 * @param details 焦点停稳后加载的详情，补充简介、分级、时长等行数据里没有的字段。
 */
@Composable
internal fun HomeFocusHeader(
    item: BaseItemDto?,
    details: BaseItemDto?,
    mediaRepository: MediaRepository,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val merged = details?.takeIf { it.id == item?.id } ?: item
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HomeHeaderHeight)
            // 标题、单集名与简介总高可能超过固定高度，裁掉溢出部分，避免压到下方横排
            .clipToBounds()
            .padding(start = 24.dp, end = 32.dp, top = 20.dp)
    ) {
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            trailing()
            TvClock()
        }
        if (merged != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp)
                    .widthIn(max = 560.dp)
            ) {
                HeaderTitle(merged, mediaRepository)
                val meta = merged.headerMetadata()
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                merged.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

/** 标题：优先显示条目（单集取剧集）的 Logo 图，没有 Logo 或加载失败时显示文字。 */
@Composable
private fun HeaderTitle(item: BaseItemDto, mediaRepository: MediaRepository) {
    val context = LocalContext.current
    val title = when (item.type) {
        "Episode" -> item.seriesName ?: item.name
        else -> item.name
    }.orEmpty()
    val logoId = (if (item.type == "Episode") item.seriesId else item.id)?.takeIf { item.type in DETAIL_TYPES }
    val logoUrl by produceState<String?>(null, logoId) {
        value = logoId?.let { id ->
            runCatching {
                mediaRepository.getImageUrl(itemId = id, imageType = "Logo", width = 500, enableImageEnhancers = false).first()
            }.getOrNull()
        }
    }
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
    if (logoUrl != null && !logoFailed) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(logoUrl).crossfade(true).build(),
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { logoFailed = true },
            modifier = Modifier
                .height(44.dp)
                .widthIn(max = 300.dp)
        )
    } else {
        Text(
            text = title,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (item.type == "Episode") {
        Spacer(Modifier.height(4.dp))
        Text(
            text = listOfNotNull(
                item.parentIndexNumber?.let { "S$it" }.orEmpty() + item.indexNumber?.let { "E$it" }.orEmpty(),
                item.name
            ).filter(String::isNotBlank).joinToString(" · "),
            color = Color.White,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 「年份（剧集为起止年份） · 分级 · 时长 · ★评分」，缺失的项省略。 */
private fun BaseItemDto.headerMetadata(): String {
    val startYear = productionYear ?: premiereDate?.take(4)?.toIntOrNull()
    val years = when {
        startYear == null -> null
        type == "Series" -> {
            val endYear = endDate?.take(4)?.toIntOrNull()
            if (endYear != null && endYear != startYear) "$startYear - $endYear" else startYear.toString()
        }
        type == "Episode" -> null
        else -> startYear.toString()
    }
    return listOfNotNull(
        years,
        officialRating?.takeIf(String::isNotBlank),
        runTimeTicks?.takeIf { it > 0 && type != "Series" }?.let(CodecUtils::formatRuntime),
        communityRating?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.US, "%.1f", it) }
    ).joinToString(" · ")
}
