package com.vela.app.ui.screens.detail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.shared.R
import com.vela.shared.preferences.Preferences
import com.vela.shared.util.image.JellyfinPosterImage
import com.vela.data.model.BaseItemDto
import com.vela.data.model.UserItemDataDto
import com.vela.data.repository.MediaRepositoryProvider
import com.vela.detail.CodecUtils
import com.vela.shared.ui.components.common.activeDetailMediaSources
import com.vela.shared.ui.components.common.buildInlineText
import com.vela.shared.ui.components.common.buildLocalVersionEntries
import com.vela.shared.ui.components.common.selectedVideoOption
import com.vela.player.core.TrackDetails
import com.vela.player.preferences.PlayerPreferences
import com.vela.shared.playback.UserDataRefreshEvent
import com.vela.shared.playback.UserDataRefreshSignals
import java.util.Locale
import kotlinx.coroutines.launch
import com.vela.app.ui.screens.dashboard.media.TvClock


@Composable
fun DetailContent(
    item: BaseItemDto,
    isLoading: Boolean = false,
    forceMergeVersions: Boolean = false,
    trackSelectionSyncVersion: Int = 0,
    onBackPressed: () -> Unit = {},
    onPlayClick: (Int?, Int?) -> Unit = { _, _ -> },
    onPreferredStreamIndexesChanged: (Int?, Int?) -> Unit = { _, _ -> },
    onSimilarItemClick: (String) -> Unit = {},
    onVersionItemSelected: (String) -> Unit = {},
    onPersonClick: (String) -> Unit = {},
    onSeasonClick: (String, String, String?, String?, String?) -> Unit = { _, _, _, _, _ -> }
) {
    val context = LocalContext.current
    val mediaRepository = remember { MediaRepositoryProvider.getInstance(context) }
    val playerPreferences = remember { PlayerPreferences(context) }
    val preferences = remember { Preferences(context) }
    val coroutineScope = rememberCoroutineScope()
    val userDataRefreshEvent by UserDataRefreshSignals.refreshEvent.collectAsState()
    val mergeVersionsEnabled by preferences.MergeVersionsEnabled()
        .collectAsState(initial = preferences.isMergeVersionsEnabled())
    val shouldMergeVersions = forceMergeVersions || mergeVersionsEnabled
    val isEpisode = item.type == "Episode"
    val isSeries = item.type == "Series"
    val episodeHeaderText = remember(
        item.type,
        item.parentIndexNumber,
        item.indexNumber,
        item.name
    ) {
        episodeHeaderText(item)
    }
    var heroImageCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var heroImageIndex by remember(item.id) { mutableStateOf(0) }
    val backdropImageUrl = heroImageCandidates.getOrNull(heroImageIndex)
    var logoImageUrl by remember(item.id) { mutableStateOf<String?>(null) }
    var logoResolved by remember(item.id) { mutableStateOf(false) }
    var logoLookup by remember(item.id) { mutableStateOf(true) }
    var logoLoadError by remember(item.id) { mutableStateOf(false) }
    val activeMediaSources = remember(item.id, item.mediaSources) {
        item.activeDetailMediaSources()
    }
    val effectiveMediaStreams = remember(item.mediaStreams, activeMediaSources) {
        val fromSources = activeMediaSources.flatMap { it.mediaStreams.orEmpty() }
        if (fromSources.isNotEmpty()) fromSources else item.mediaStreams.orEmpty()
    }
    val savedAudioOption = remember(item.id, item.seriesId, item.type, effectiveMediaStreams, trackSelectionSyncVersion) {
        val currentItemId = item.id ?: return@remember null
        AudioStreamIndex(
            streams = effectiveMediaStreams,
            streamIndex = playerPreferences.resolvePreferredAudioStreamIndex(
                itemId = currentItemId,
                seriesId = TrackDetails.seriesPreferenceId(item.type, item.seriesId),
                streams = effectiveMediaStreams
            )
        )
    }
    val savedSubtitleOption = remember(item.id, item.seriesId, item.type, effectiveMediaStreams, trackSelectionSyncVersion) {
        val currentItemId = item.id ?: return@remember null
        SubtitleStreamIndex(
            streams = effectiveMediaStreams,
            streamIndex = playerPreferences.resolvePreferredSubtitleStreamIndex(
                itemId = currentItemId,
                seriesId = TrackDetails.seriesPreferenceId(item.type, item.seriesId),
                streams = effectiveMediaStreams
            )
        )
    }
    val initialVideoOption = remember(item.id, effectiveMediaStreams) {
        buildVideoOptions(effectiveMediaStreams).firstOrNull().orEmpty()
    }
    val initialAudioOption = remember(item.id, effectiveMediaStreams, savedAudioOption) {
        savedAudioOption ?: defaultAudioOption(effectiveMediaStreams)
    }
    val initialSubtitleOption = remember(item.id, effectiveMediaStreams, savedSubtitleOption) {
        savedSubtitleOption ?: buildDefaultSubtitleOption(effectiveMediaStreams)
    }
    var selectedVideo by rememberSaveable(item.id) { mutableStateOf(initialVideoOption) }
    var selectedAudio by rememberSaveable(item.id, trackSelectionSyncVersion) { mutableStateOf(initialAudioOption) }
    var selectedSubtitle by rememberSaveable(item.id, trackSelectionSyncVersion) { mutableStateOf(initialSubtitleOption) }
    val runtimeTicks = item.runTimeTicks
    val playbackPositionTicks = item.userData?.playbackPositionTicks ?: 0L
    val isPartiallyWatched = runtimeTicks != null && playbackPositionTicks > 0L && playbackPositionTicks < runtimeTicks
    val logoFallbackTitle = if (isEpisode) {
        item.seriesName?.takeIf { it.isNotBlank() }
            ?: item.name?.takeIf { it.isNotBlank() }
            ?: "Unknown"
    } else {
        item.name?.takeIf { it.isNotBlank() } ?: "Unknown"
    }
    val showTitleFallback = !isLoading && !logoLookup && (logoImageUrl.isNullOrBlank() || logoLoadError)
    val genresText = remember(item.genres) {
        item.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
    var isFavorite by remember(item.id, item.userData?.isFavorite) {
        mutableStateOf(item.userData?.isFavorite == true)
    }
    var isWatched by remember(
        item.id,
        item.type,
        item.userData?.played,
        item.userData?.unplayedItemCount
    ) {
        mutableStateOf(
            if (isSeries) {
                item.userData?.unplayedItemCount?.let { it == 0 }
                    ?: (item.userData?.played == true)
            } else {
                item.userData?.played == true
            }
        )
    }
    var moreFromSeasonEpisodes by remember(item.id, item.seriesId, item.seasonId) {
        mutableStateOf<List<BaseItemDto>>(emptyList())
    }
    var localVersions by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    val directors = remember(item.people) {
        item.people?.filter { person ->
            listOf(person.role, person.type).any { field ->
                field?.contains("Director", ignoreCase = true) == true
            }
        }.orEmpty()
    }
    fun toggleFavorite() {
        val currentItemId = item.id ?: return
        val targetState = !isFavorite
        coroutineScope.launch {
            val result = mediaRepository.setFavoriteStatus(
                itemId = currentItemId,
                isFavorite = targetState
            )
            if (result.isSuccess) {
                isFavorite = targetState
            }
        }
    }
    fun toggleWatched() {
        val currentItemId = item.id ?: return
        val targetState = !isWatched
        coroutineScope.launch {
            val result = if (isSeries) {
                mediaRepository.setSeriesPlayedStatus(
                    seriesId = currentItemId,
                    isPlayed = targetState
                )
            } else {
                mediaRepository.setPlayedStatus(
                    itemId = currentItemId,
                    isPlayed = targetState
                )
            }
            if (result.isSuccess) {
                isWatched = targetState
                UserDataRefreshSignals.notifyUserDataChanged(
                    itemId = currentItemId,
                    played = targetState
                )
            }
        }
    }

    LaunchedEffect(item.id) {
        if (item.id == null) {
            logoLookup = false
            return@LaunchedEffect
        }
        heroImageCandidates = heroImageCandidates(
            item = item,
            mediaRepository = mediaRepository
        )
        heroImageIndex = 0

        if (logoResolved) {
            logoLookup = false
            return@LaunchedEffect
        }

        logoLookup = true
        logoLoadError = false
        try {
            val nextLogoImageUrl = logoImage(
                item = item,
                mediaRepository = mediaRepository
            )
            if (nextLogoImageUrl != logoImageUrl) {
                logoImageUrl = nextLogoImageUrl
            }
            logoResolved = true
        } finally {
            logoLookup = false
        }
    }

    LaunchedEffect(
        item.id,
        item.type,
        item.seriesId,
        item.seasonId,
        item.parentIndexNumber,
        userDataRefreshEvent
    ) {
        if (!isEpisode) {
            moreFromSeasonEpisodes = emptyList()
            return@LaunchedEffect
        }

        val seriesId = item.seriesId?.takeIf { it.isNotBlank() }
        if (seriesId.isNullOrBlank()) {
            moreFromSeasonEpisodes = emptyList()
            return@LaunchedEffect
        }

        moreFromSeasonEpisodes = moreFromSeasonEpisodes.withUserDataRefresh(userDataRefreshEvent)

        val seasonId = item.seasonId?.takeIf { it.isNotBlank() }
        val result = if (seasonId != null) {
            mediaRepository.getEpisodes(seriesId = seriesId, seasonId = seasonId)
        } else {
            mediaRepository.getEpisodes(seriesId = seriesId)
        }

        moreFromSeasonEpisodes = result
            .getOrNull()
            .orEmpty()
            .filter { episode ->
                when {
                    seasonId != null -> episode.seasonId == seasonId
                    item.parentIndexNumber != null -> episode.parentIndexNumber == item.parentIndexNumber
                    else -> true
                }
            }
            .filter { episode ->
                val episodeId = episode.id
                !episodeId.isNullOrBlank() && episodeId != item.id
            }
            .sortedWith(
                compareBy<BaseItemDto>(
                    { it.indexNumber ?: Int.MAX_VALUE },
                    { it.name.orEmpty() },
                    { it.id.orEmpty() }
                )
            )
            .withUserDataRefresh(userDataRefreshEvent)
    }

    LaunchedEffect(item.id, item.type, shouldMergeVersions) {
        val supportsLocalVersions = item.type.equals("Movie", ignoreCase = true) ||
            item.type.equals("Episode", ignoreCase = true)
        if (
            !shouldMergeVersions ||
            !supportsLocalVersions ||
            item.id.isNullOrBlank()
        ) {
            localVersions = emptyList()
            return@LaunchedEffect
        }

        mediaRepository.getLocalVersions(item)
            .getOrNull()
            ?.filter { version -> !version.id.isNullOrBlank() }
            ?.let { versions ->
                val hasCurrentVersionList = localVersions.any { version -> version.id == item.id }
                if (versions.size > 1 || !hasCurrentVersionList) {
                    localVersions = versions
                }
            }
    }

    val baseVideoOptions = remember(effectiveMediaStreams) { buildVideoOptions(effectiveMediaStreams) }
    val videoFallbackLabel = stringResource(R.string.detail_video_fallback)
    val smallFileSizeLabel = stringResource(R.string.detail_file_size_under_1_mb)
    val localVersionEntries = remember(localVersions, item.id, videoFallbackLabel, smallFileSizeLabel) {
        buildLocalVersionEntries(
            localVersions = localVersions,
            currentItemId = item.id,
            videoFallbackLabel = videoFallbackLabel,
            smallFileSizeLabel = smallFileSizeLabel
        )
    }
    val localVersionOptions = localVersionEntries.map { (label, _) -> label }
    val videoOptions = localVersionOptions.ifEmpty { baseVideoOptions }
    val displayedSelectedVideo = selectedVideoOption(
        localVersionEntries = localVersionEntries,
        currentItemId = item.id,
        selectedVideo = selectedVideo,
        videoOptions = videoOptions,
        baseVideoOptions = baseVideoOptions
    )
    val audioOptions = remember(effectiveMediaStreams) { buildAudioOptions(effectiveMediaStreams) }
    val subtitleOptions = remember(effectiveMediaStreams) { buildSubtitleOptions(effectiveMediaStreams) }
    val defaultSubtitleOption = remember(effectiveMediaStreams) { buildDefaultSubtitleOption(effectiveMediaStreams) }
    val codecBadges = CodecBadges(
        streams = effectiveMediaStreams,
        selectedVideo = displayedSelectedVideo,
        selectedAudio = selectedAudio
    )
    val videoInlineMetaText = remember(
        activeMediaSources,
        effectiveMediaStreams,
        localVersionOptions
    ) {
        if (localVersionOptions.isNotEmpty()) {
            null
        } else {
            buildInlineText(
                mediaSources = activeMediaSources,
                streams = effectiveMediaStreams,
                smallFileSizeLabel = smallFileSizeLabel
            )
        }
    }

    fun persistTrackSelection(audioOption: String, subtitleOption: String): Pair<Int?, Int?> {
        val audioStreamIndex = AudioStreamIndex(
            streams = effectiveMediaStreams,
            selectedOption = audioOption
        )
        val subtitleStreamIndex = SubtitleStreamIndex(
            streams = effectiveMediaStreams,
            selectedOption = subtitleOption
        )
        item.id?.let { currentItemId ->
            val seriesId = TrackDetails.seriesPreferenceId(item.type, item.seriesId)
            playerPreferences.persistAudioSelection(
                itemId = currentItemId,
                seriesId = seriesId,
                streams = effectiveMediaStreams,
                streamIndex = audioStreamIndex
            )
            playerPreferences.persistSubtitleSelection(
                itemId = currentItemId,
                seriesId = seriesId,
                streams = effectiveMediaStreams,
                streamIndex = subtitleStreamIndex
            )
        }
        onPreferredStreamIndexesChanged(audioStreamIndex, subtitleStreamIndex)
        return audioStreamIndex to subtitleStreamIndex
    }

    val onVideoOptionSelected: (String) -> Unit = { option ->
        val selectedVersion = localVersionEntries.firstOrNull { (label, _) -> label == option }?.second
        val selectedVersionId = selectedVersion?.id
        if (selectedVersionId != null && selectedVersionId != item.id) {
            onVersionItemSelected(selectedVersionId)
        } else {
            selectedVideo = option
        }
    }
    val onAudioOptionSelected: (String) -> Unit = { option ->
        selectedAudio = option
        persistTrackSelection(
            audioOption = option,
            subtitleOption = selectedSubtitle
        )
    }
    val onSubtitleOptionSelected: (String) -> Unit = { option ->
        selectedSubtitle = option
        persistTrackSelection(
            audioOption = selectedAudio,
            subtitleOption = option
        )
    }

    LaunchedEffect(displayedSelectedVideo, audioOptions, subtitleOptions, defaultSubtitleOption) {
        if (selectedVideo != displayedSelectedVideo) selectedVideo = displayedSelectedVideo
        if (selectedAudio !in audioOptions) {
            selectedAudio = defaultAudioOption(effectiveMediaStreams)
        }
    }

    val primaryActionRequester = remember(item.id) { FocusRequester() }
    // 选集面板打开时选中的季；null 表示面板关闭
    var seasonPanelIndex by remember(item.id) { mutableStateOf<Int?>(null) }
    var seasons by remember(item.id) { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    val endsAtText = if (isSeries) null else rememberEndsAtText(runtimeTicks, playbackPositionTicks)
    val playLabel = stringResource(if (isPartiallyWatched) R.string.resume else R.string.play)
    val watchedLabel = stringResource(R.string.watched)
    val favoriteLabel = stringResource(R.string.favorite)

    LaunchedEffect(item.id, isSeries) {
        val seriesId = item.id
        seasons = if (isSeries && seriesId != null) {
            mediaRepository.getSeasons(seriesId).getOrNull().orEmpty().sortedBy { it.indexNumber ?: 0 }
        } else {
            emptyList()
        }
    }

    // 进入详情页时焦点落在主操作按钮上（播放 / 已观看）
    LaunchedEffect(item.id) {
        withFrameNanos { }
        runCatching { primaryActionRequester.requestFocus() }
    }

    val actions = buildList {
        if (!isSeries) {
            add(
                DetailAction(
                    icon = Icons.Rounded.PlayArrow,
                    label = playLabel,
                    onClick = {
                        val (selectedAudioStreamIndex, selectedSubtitleStreamIndex) =
                            persistTrackSelection(audioOption = selectedAudio, subtitleOption = selectedSubtitle)
                        onPlayClick(selectedAudioStreamIndex, selectedSubtitleStreamIndex)
                    }
                )
            )
        }
        add(DetailAction(Icons.Rounded.Check, watchedLabel, active = isWatched, onClick = ::toggleWatched))
        add(
            DetailAction(
                icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = favoriteLabel,
                active = isFavorite,
                onClick = ::toggleFavorite
            )
        )
    }
    val moreFromSeasonLabel = item.parentIndexNumber
        ?.let { stringResource(R.string.detail_more_from_season, it) }
        ?: stringResource(
            R.string.detail_more_from_season_named,
            item.seasonName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.detail_season_fallback)
        )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        JellyfinPosterImage(
            imageUrl = backdropImageUrl,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            context = context,
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            onErrorStateChange = { hasError ->
                if (
                    hasError &&
                    backdropImageUrl == heroImageCandidates.getOrNull(heroImageIndex) &&
                    heroImageIndex < heroImageCandidates.lastIndex
                ) {
                    heroImageIndex += 1
                }
            }
        )

        // Emby TV 的详情页背景整体压暗，左侧与下方再加深，保证文字和横排清晰
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.9f),
                            0.5f to Color.Black.copy(alpha = 0.65f),
                            1.0f to Color.Black.copy(alpha = 0.35f)
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.55f to Color.Black.copy(alpha = 0.3f),
                            1.0f to Color.Black.copy(alpha = 0.9f)
                        )
                    )
                )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 56.dp, bottom = 48.dp)
        ) {
            item(key = "hero") {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    DetailPoster(item = item, mediaRepository = mediaRepository)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (!logoImageUrl.isNullOrBlank() && !logoLoadError) {
                            JellyfinPosterImage(
                                imageUrl = logoImageUrl,
                                contentDescription = item.name,
                                modifier = Modifier
                                    .fillMaxWidth(0.6f)
                                    .height(64.dp),
                                context = context,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart,
                                onErrorStateChange = { hasError -> logoLoadError = hasError }
                            )
                        } else if (showTitleFallback) {
                            Text(
                                text = logoFallbackTitle,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                lineHeight = 34.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (isEpisode) {
                            episodeHeaderText?.let { header ->
                                Text(
                                    text = header,
                                    fontSize = 18.sp,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        DetailMetadataRow(item = item, endsAtText = endsAtText)

                        if (!genresText.isNullOrBlank()) {
                            Text(
                                text = genresText.replace(", ", " / "),
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        item.overview?.takeIf { it.isNotBlank() }?.let { overviewText ->
                            Text(
                                text = overviewText,
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        if (directors.isNotEmpty()) {
                            DirectorCreditRow(
                                label = "Director:",
                                directors = directors,
                                onPersonClick = onPersonClick
                            )
                        }

                        if (!isSeries) {
                            TrackSection(
                                displayedSelectedVideo = displayedSelectedVideo,
                                videoOptions = videoOptions,
                                videoInlineMetaText = videoInlineMetaText,
                                selectedAudio = selectedAudio,
                                audioOptions = audioOptions,
                                selectedSubtitle = selectedSubtitle,
                                subtitleOptions = subtitleOptions,
                                onVideoOptionSelected = onVideoOptionSelected,
                                onAudioOptionSelected = onAudioOptionSelected,
                                onSubtitleOptionSelected = onSubtitleOptionSelected
                            )
                        }

                        DetailActionRow(
                            actions = actions,
                            firstFocusRequester = primaryActionRequester,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }

            if (isSeries && seasons.isNotEmpty()) {
                item(key = "seasons") {
                    SeasonPosterRow(
                        seasons = seasons,
                        mediaRepository = mediaRepository,
                        onSeasonClick = { index -> seasonPanelIndex = index }
                    )
                }
            }

            if (isEpisode && moreFromSeasonEpisodes.isNotEmpty()) {
                item(key = "more_from_season") {
                    MoreFromSeasonSection(
                        episodes = moreFromSeasonEpisodes,
                        mediaRepository = mediaRepository,
                        title = moreFromSeasonLabel,
                        onEpisodeClick = onSimilarItemClick
                    )
                }
            }

            item(key = "cast") {
                CastCrewSection(
                    item = item,
                    mediaRepository = mediaRepository,
                    onPersonClick = onPersonClick
                )
            }

            item(key = "recs") {
                Recommendations(
                    item = item,
                    directors = directors,
                    mediaRepository = mediaRepository,
                    onItemClick = onSimilarItemClick
                )
            }
        }

        TvClock(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 40.dp)
        )

        // 选集面板：从「季」横排进入，返回键关闭
        AnimatedVisibility(
            visible = seasonPanelIndex != null,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            BackHandler { seasonPanelIndex = null }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f))
                    .padding(horizontal = 48.dp, vertical = 32.dp)
            ) {
                item.id?.let { seriesId ->
                    SeasonsSection(
                        seriesId = seriesId,
                        mediaRepository = mediaRepository,
                        initialSeasonIndex = seasonPanelIndex ?: 0,
                        onEpisodeClick = onSimilarItemClick,
                        onDismiss = { seasonPanelIndex = null }
                    )
                }
            }
        }
    }
}

/** 「年份 · 时长 · 分级 · ★评分 · 结束于 HH:mm」，缺失的项省略；分级用描边小标签。 */
@Composable
private fun DetailMetadataRow(item: BaseItemDto, endsAtText: String?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp)
    ) {
        val startYear = item.productionYear ?: item.premiereDate?.take(4)?.toIntOrNull()
        val years = when {
            startYear == null -> null
            item.type == "Series" -> item.endDate?.take(4)?.toIntOrNull()
                ?.takeIf { it != startYear }
                ?.let { "$startYear - $it" } ?: startYear.toString()
            else -> startYear.toString()
        }
        years?.let { MetadataText(it) }
        item.runTimeTicks?.takeIf { it > 0 && item.type != "Series" }?.let { MetadataText(CodecUtils.formatRuntime(it)) }
        item.officialRating?.takeIf { it.isNotBlank() }?.let { rating ->
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(3.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f))
            ) {
                Text(
                    text = rating,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
        item.communityRating?.takeIf { it > 0 }?.let { rating ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp))
                MetadataText(String.format(Locale.US, "%.1f", rating), Modifier.padding(start = 2.dp))
            }
        }
        endsAtText?.let { MetadataText(it) }
    }
}

@Composable
private fun MetadataText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f), modifier = modifier)
}

private fun List<BaseItemDto>.withUserDataRefresh(
    event: UserDataRefreshEvent?
): List<BaseItemDto> {
    val itemId = event?.itemId?.takeIf { it.isNotBlank() } ?: return this
    val played = event.played ?: return this

    return map { episode ->
        if (episode.id != itemId) {
            episode
        } else {
            episode.copy(
                userData = (episode.userData ?: UserItemDataDto(itemId = itemId)).copy(
                    played = played,
                    playbackPositionTicks = 0L
                )
            )
        }
    }
}