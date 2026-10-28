package com.vela.app.ui.screens.peach

import com.vela.data.peach.peachString
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import com.vela.data.peach.PeachOnlineSubtitleFile
import com.vela.data.peach.PeachOnlineSubtitle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vela.data.peach.PeachAsset
import com.vela.data.peach.PeachIds
import com.vela.data.peach.PeachMagnet
import com.vela.data.peach.PeachRepository
import com.vela.shared.R

/**
 * 详情页上 Peach 独有的互动：评分、已看、不喜欢、O 计数、稍后看与找资源。
 * 切换类接口再调一次即取消，按钮以服务端返回的状态刷新，请求进行中禁用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeachItemPanel(
    itemId: String,
    modifier: Modifier = Modifier,
    onChanged: () -> Unit = {},
    onOpenItem: (String) -> Unit = {}
) {
    val assetId = PeachIds.assetId(itemId) ?: return
    val context = LocalContext.current
    val repository = remember { PeachRepository.getInstance(context) }
    var asset by remember(assetId) { mutableStateOf<PeachAsset?>(null) }
    var showResources by remember { mutableStateOf(false) }
    var showSubtitles by remember { mutableStateOf(false) }
    val actions = rememberPeachActions()
    LaunchedEffect(assetId) {
        asset = runCatching { repository.item(assetId) }.getOrNull()
    }
    val current = asset ?: return

    fun refresh(after: suspend PeachRepository.() -> Unit) {
        actions.run(onDone = onChanged) {
            after()
            asset = item(assetId)
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val stars = ((current.rating ?: 0) + 10) / 20
            (1..5).forEach { star ->
                IconButton(
                    enabled = !actions.isBusy,
                    onClick = { refresh { rate(assetId, if (stars == star) 0 else star) } },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (star <= stars) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = stringResource(R.string.peach_rate_stars, star),
                        tint = if (star <= stars) Color(0xFFFFC107) else Color.White.copy(alpha = 0.5f)
                    )
                }
            }
            current.oCount?.takeIf { it > 0 }?.let {
                Text("O × $it", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(start = 8.dp))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = current.isSeen,
                enabled = !actions.isBusy,
                onClick = { refresh { feedback(assetId, "seen") } },
                label = { Text(stringResource(R.string.peach_action_seen)) }
            )
            FilterChip(
                selected = current.watchLater,
                enabled = !actions.isBusy,
                onClick = { refresh { toggleWatchLater(assetId) } },
                label = { Text(stringResource(R.string.peach_action_watch_later)) }
            )
            FilterChip(
                selected = current.liked,
                enabled = !actions.isBusy,
                onClick = { refresh { setLiked(assetId, !current.liked) } },
                label = { Text(stringResource(R.string.peach_action_liked)) }
            )
            FilterChip(
                selected = current.isDisliked,
                enabled = !actions.isBusy,
                onClick = { refresh { feedback(assetId, "dislike") } },
                label = { Text(stringResource(R.string.peach_action_dislike)) }
            )
            FilterChip(
                selected = false,
                enabled = !actions.isBusy,
                onClick = { refresh { feedback(assetId, "o") } },
                label = { Text(stringResource(R.string.peach_action_o)) }
            )
            if ((current.oCount ?: 0) > 0) {
                FilterChip(
                    selected = false,
                    enabled = !actions.isBusy,
                    onClick = { refresh { feedback(assetId, "o-undo") } },
                    label = { Text(stringResource(R.string.peach_action_o_undo)) }
                )
            }
            current.code?.let {
                FilterChip(
                    selected = false,
                    onClick = { showResources = true },
                    label = { Text(stringResource(R.string.peach_find_resources)) }
                )
            }
            FilterChip(
                selected = false,
                onClick = { showSubtitles = true },
                label = { Text(stringResource(R.string.peach_online_subtitles)) }
            )
        }
        PeachItemManageRow(asset = current, onOpenItem = onOpenItem, onChanged = { updated -> asset = updated; onChanged() })
    }
    if (showSubtitles) {
        PeachOnlineSubtitleDialog(
            assetId = assetId,
            initialQuery = current.code ?: current.displayTitle,
            onDismiss = { showSubtitles = false }
        )
    }
    if (showResources) {
        current.code?.let { code ->
            PeachResourceDialog(code = code, title = current.displayTitle, onDismiss = { showResources = false })
        }
    }
}

/** 找资源 → 选磁力 → 选下载渠道提交。渠道未配置时只给说明，不提供提交。 */
@Composable
fun PeachResourceDialog(code: String, title: String?, onDismiss: () -> Unit) {
    val resources = rememberPeachLoad { findResources(code) }
    val downloads = rememberPeachLoad { downloads(limit = 1) }
    val actions = rememberPeachActions()
    var selected by remember { mutableStateOf<PeachMagnet?>(null) }
    val channels = (downloads.value as? PeachLoad.Ready)?.value?.configuredChannels.orEmpty()
    val submittedText = stringResource(R.string.peach_download_submitted)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.peach_resources_for, code)) },
        text = {
            Box(Modifier.heightIn(max = 460.dp)) {
                when (val value = resources.value) {
                    PeachLoad.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    is PeachLoad.Failed -> Text(value.error.message ?: value.error.toString())
                    is PeachLoad.Ready -> {
                        val result = value.value
                        val magnet = selected
                        if (magnet != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(magnet.name, fontWeight = FontWeight.SemiBold)
                                if (channels.isEmpty()) {
                                    Text(stringResource(R.string.peach_downloads_configure_on_host), style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text(stringResource(R.string.peach_choose_channel), style = MaterialTheme.typography.bodySmall)
                                    channels.forEach { channel ->
                                        OutlinedButton(
                                            enabled = !actions.isBusy,
                                            modifier = Modifier.fillMaxWidth(),
                                            onClick = {
                                                actions.run(successMessage = submittedText, onDone = onDismiss) {
                                                    submitMagnet(magnet.uri, channel.key, code = code, title = title)
                                                }
                                            }
                                        ) { Text(channel.label) }
                                    }
                                }
                            }
                        } else if (result.items.isEmpty()) {
                            Text(result.error ?: stringResource(R.string.peach_resources_empty))
                        } else {
                            LazyColumn {
                                items(result.items) { candidate ->
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp)
                                    ) {
                                        TextButton(onClick = { selected = candidate }) {
                                            Column(Modifier.fillMaxWidth()) {
                                                Text(candidate.name, maxLines = 2)
                                                val tags = listOfNotNull(
                                                    candidate.sizeText,
                                                    candidate.resolution?.let { "${it}p" },
                                                    stringResource(R.string.peach_resource_chinese).takeIf { candidate.chinese },
                                                    stringResource(R.string.peach_resource_uncensored).takeIf { candidate.uncensored },
                                                    candidate.seeders?.let { "↑$it" },
                                                    candidate.source
                                                )
                                                Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selected != null) {
                TextButton(onClick = { selected = null }) { Text(stringResource(R.string.peach_back)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.peach_close)) } }
    )
}

/**
 * Peach 实体页的操作：出镜者可订阅新作；所有实体可收藏、看图集，并打开带 Peach 筛选的全部作品。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeachEntityActions(
    kind: String,
    entityId: Long,
    name: String,
    onOpenPage: (page: String, arg: String?, title: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { PeachRepository.getInstance(context) }
    var favorite by remember(entityId) { mutableStateOf<Boolean?>(null) }
    var editingAliases by remember { mutableStateOf(false) }
    val actions = rememberPeachActions()
    LaunchedEffect(entityId) {
        favorite = runCatching { repository.isFavoriteEntity(kind, entityId) }.getOrNull()
    }
    if (editingAliases) {
        PeachEntityAliasDialog(kind = kind, entityId = entityId, onDismiss = { editingAliases = false }, onRenamed = {})
    }
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (kind == "performer") PeachFollowPerformerButton(entityId)
        favorite?.let { current ->
            FilterChip(
                selected = current,
                enabled = !actions.isBusy,
                onClick = {
                    actions.run {
                        setFavoriteEntity(entityId, !current)
                        favorite = !current
                    }
                },
                label = { Text(stringResource(R.string.peach_entity_favorite)) }
            )
        }
        FilterChip(
            selected = false,
            onClick = { onOpenPage(PeachBrowsePages.PHOTOS, "$kind:$entityId", name) },
            label = { Text(stringResource(R.string.peach_entity_photos)) }
        )
        if (kind != "tag") {
            FilterChip(selected = false, onClick = { editingAliases = true }, label = { Text(stringResource(R.string.peach_entity_aliases)) })
        }
        if (kind in setOf("performer", "creator", "studio", "series", "agency", "tag")) {
            FilterChip(
                selected = false,
                onClick = { onOpenPage(PeachBrowsePages.ITEMS, PeachBrowsePages.itemsArg(kind to name), name) },
                label = { Text(stringResource(R.string.peach_entity_all_works)) }
            )
        }
    }
}

/** 人物页「订阅新作」开关；状态以服务端返回为准。 */
@Composable
fun PeachFollowPerformerButton(entityId: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { PeachRepository.getInstance(context) }
    var following by remember(entityId) { mutableStateOf<Boolean?>(null) }
    val actions = rememberPeachActions()
    LaunchedEffect(entityId) {
        following = runCatching { repository.isFollowingPerformer(entityId) }.getOrNull()
    }
    val current = following ?: return
    FilterChip(
        modifier = modifier,
        selected = current,
        enabled = !actions.isBusy,
        onClick = { actions.run { following = followPerformer(entityId, !current) } },
        label = { Text(stringResource(if (current) R.string.peach_unfollow_performer else R.string.peach_follow_performer)) }
    )
}

/**
 * 在线字幕（SubtitleCat）：搜索 → 选字幕包里的文件 → 存进 Peach 字幕记忆。
 * 与网页一致，保存后下次播放（应用内或网页）默认选中这条字幕。
 */
@Composable
fun PeachOnlineSubtitleDialog(assetId: Long, initialQuery: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { PeachRepository.getInstance(context) }
    val actions = rememberPeachActions()
    var query by remember { mutableStateOf(initialQuery) }
    var searchKey by remember { mutableStateOf(initialQuery to 0) }
    var results by remember { mutableStateOf<PeachLoad<Pair<List<PeachOnlineSubtitle>, Boolean>>>(PeachLoad.Loading) }
    var opened by remember { mutableStateOf<PeachOnlineSubtitle?>(null) }
    var files by remember { mutableStateOf<PeachLoad<List<PeachOnlineSubtitleFile>>?>(null) }
    val savedText = stringResource(R.string.peach_online_subtitle_saved)

    LaunchedEffect(searchKey) {
        results = PeachLoad.Loading
        results = runCatching { repository.searchOnlineSubtitles(searchKey.first, searchKey.second) }
            .fold({ PeachLoad.Ready(it) }, { PeachLoad.Failed(it) })
    }
    LaunchedEffect(opened) {
        val subtitle = opened ?: return@LaunchedEffect
        files = PeachLoad.Loading
        files = runCatching { repository.onlineSubtitleFiles(subtitle.id) }
            .fold({ PeachLoad.Ready(it) }, { PeachLoad.Failed(it) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.peach_online_subtitles)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val subtitle = opened
                if (subtitle == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(enabled = query.trim().length >= 3, onClick = { searchKey = query.trim() to 0 }) {
                            Text(stringResource(R.string.peach_search))
                        }
                    }
                }
                Box(Modifier.heightIn(max = 380.dp)) {
                    if (subtitle != null) {
                        when (val value = files) {
                            null, PeachLoad.Loading -> CircularProgressIndicator()
                            is PeachLoad.Failed -> Text(value.error.message ?: value.error.toString())
                            is PeachLoad.Ready -> LazyColumn {
                                items(value.value) { file ->
                                    TextButton(
                                        enabled = !actions.isBusy,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            actions.run(successMessage = savedText, onDone = onDismiss) {
                                                saveOnlineSubtitle(assetId, subtitle.id, file)
                                            }
                                        }
                                    ) {
                                        Text(listOfNotNull(file.name, file.language).joinToString(" · "), modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    } else {
                        when (val value = results) {
                            PeachLoad.Loading -> CircularProgressIndicator()
                            is PeachLoad.Failed -> Text(value.error.message ?: value.error.toString())
                            is PeachLoad.Ready -> {
                                val (items, more) = value.value
                                if (items.isEmpty()) {
                                    Text(stringResource(R.string.peach_online_subtitles_empty))
                                } else {
                                    LazyColumn {
                                        items(items) { item ->
                                            TextButton(onClick = { opened = item }, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.fillMaxWidth()) {
                                                    Text(item.title, maxLines = 2)
                                                    Text(
                                                        listOfNotNull(item.language, item.format).joinToString(" · "),
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                            }
                                        }
                                        if (more && searchKey.second + 6 < 60) {
                                            item {
                                                TextButton(onClick = { searchKey = searchKey.first to searchKey.second + 6 }) {
                                                    Text(stringResource(R.string.peach_next_page))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (opened != null) TextButton(onClick = { opened = null; files = null }) { Text(stringResource(R.string.peach_back)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.peach_close)) } }
    )
}

/**
 * 关注帖子在详情页上的操作：标记已看、忽略、保存进馆藏，打开来源页，以及帖子里的其他媒体。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeachFollowPanel(itemId: String, onOpenItem: (com.vela.data.model.BaseItemDto) -> Unit, modifier: Modifier = Modifier) {
    val (followId, currentMedia) = PeachIds.followRef(itemId) ?: return
    val context = LocalContext.current
    val repository = remember { PeachRepository.getInstance(context) }
    var post by remember(followId) { mutableStateOf<com.vela.data.peach.PeachFollowItem?>(null) }
    val actions = rememberPeachActions()
    LaunchedEffect(followId) { post = runCatching { repository.followItem(followId) }.getOrNull() }
    val current = post ?: return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = current.status == "seen",
                enabled = !actions.isBusy,
                onClick = {
                    actions.run {
                        setFollowStatus(listOf(followId), if (current.status == "seen") "new" else "seen")
                        post = followItem(followId)
                    }
                },
                label = { Text(stringResource(R.string.peach_action_seen)) }
            )
            FilterChip(
                selected = current.isSaved,
                enabled = !actions.isBusy && !current.isSaved,
                onClick = {
                    actions.run {
                        saveFollowItem(followId)
                        post = followItem(followId)
                    }
                },
                label = { Text(stringResource(R.string.peach_follow_save)) }
            )
            FilterChip(
                selected = current.status == "ignored",
                enabled = !actions.isBusy,
                onClick = {
                    actions.run {
                        setFollowStatus(listOf(followId), if (current.status == "ignored") "new" else "ignored")
                        post = followItem(followId)
                    }
                },
                label = { Text(stringResource(R.string.peach_ignore)) }
            )
            current.url?.let { url ->
                FilterChip(
                    selected = false,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.peach_open_source)) }
                )
            }
        }
        if (current.mediaNeedsCredential || current.mediaError != null) {
            Text(
                current.mediaError ?: stringResource(R.string.peach_follow_needs_credential),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        val others = current.media.filter { it.index != currentMedia }
        if (current.media.size > 1 && others.isNotEmpty()) {
            Text(stringResource(R.string.peach_follow_media_count, current.media.size), color = Color.White.copy(alpha = 0.7f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                others.forEach { media ->
                    AssistChip(
                        onClick = { onOpenItem(com.vela.data.peach.PeachMapper.followToBaseItem(current, media.index)) },
                        label = { Text(media.name ?: "#${media.index + 1}", maxLines = 1) }
                    )
                }
            }
        }
    }
}

private val REGIONS = listOf("jp", "kr", "cn", "west", "other", "none")

/**
 * 详情页的整理类操作：编辑标签、想要高清版、产地、其他版次、加入播放列表、对账所在目录。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeachItemManageRow(asset: PeachAsset, onOpenItem: (String) -> Unit, onChanged: (PeachAsset) -> Unit) {
    val context = LocalContext.current
    val repository = remember { PeachRepository.getInstance(context) }
    val actions = rememberPeachActions()
    var betterVersion by remember(asset.id) { mutableStateOf<Boolean?>(null) }
    var editions by remember(asset.id) { mutableStateOf<List<PeachAsset>>(emptyList()) }
    var editingTags by remember { mutableStateOf(false) }
    var choosingRegion by remember { mutableStateOf(false) }
    var choosingPlaylist by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf(false) }
    LaunchedEffect(asset.id) {
        betterVersion = runCatching { repository.qualityGoal(asset.id).first }.getOrNull()
        editions = runCatching { repository.editions(asset.id) }.getOrDefault(emptyList())
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { editingTags = true }, label = { Text(stringResource(R.string.peach_edit_tags)) })
        betterVersion?.let { wanted ->
            FilterChip(
                selected = wanted,
                enabled = !actions.isBusy,
                onClick = { actions.run { betterVersion = setQualityGoal(asset.id, !wanted) } },
                label = { Text(stringResource(R.string.peach_want_better_version)) }
            )
        }
        AssistChip(onClick = { choosingRegion = true }, label = { Text(asset.regionLabel ?: stringResource(R.string.peach_filter_region)) })
        AssistChip(onClick = { choosingPlaylist = true }, label = { Text(stringResource(R.string.peach_add_to_playlist)) })
        AssistChip(onClick = { confirmPurge = true }, label = { Text(stringResource(R.string.peach_purge_missing)) })
    }
    if (editions.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.peach_other_editions), color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.CenterVertically))
            editions.forEach { edition ->
                AssistChip(
                    onClick = { onOpenItem(edition.id.toString()) },
                    label = { Text(edition.editionLabel ?: edition.container ?: edition.displayTitle, maxLines = 1) }
                )
            }
        }
    }
    if (editingTags) {
        var tags by remember { mutableStateOf(asset.tags) }
        var newTag by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editingTags = false },
            title = { Text(stringResource(R.string.peach_edit_tags)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { tag ->
                            AssistChip(
                                enabled = !actions.isBusy,
                                onClick = { actions.run { tags = editItemTag(asset.id, tag, add = false) } },
                                label = { Text("$tag ×") }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(newTag, { newTag = it.take(80) }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(enabled = newTag.isNotBlank() && !actions.isBusy, onClick = {
                            val tag = newTag
                            newTag = ""
                            actions.run { tags = editItemTag(asset.id, tag, add = true) }
                        }) { Text(stringResource(R.string.peach_add)) }
                    }
                    Text(stringResource(R.string.peach_edit_tags_hint), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingTags = false
                    actions.run { onChanged(item(asset.id)) }
                }) { Text(stringResource(R.string.peach_close)) }
            }
        )
    }
    if (choosingRegion) {
        AlertDialog(
            onDismissRequest = { choosingRegion = false },
            title = { Text(stringResource(R.string.peach_filter_region)) },
            text = {
                Column {
                    REGIONS.forEach { region ->
                        TextButton(onClick = {
                            choosingRegion = false
                            actions.run {
                                batch(listOf(asset.id), "region", region = region)
                                onChanged(item(asset.id))
                            }
                        }) { Text(regionName(region)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingRegion = false }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
    if (choosingPlaylist) {
        val playlists = rememberPeachLoad { playlists().filterNot { it.isMix } }
        var newName by remember { mutableStateOf("") }
        val addedText = stringResource(R.string.peach_added_to_playlist)
        AlertDialog(
            onDismissRequest = { choosingPlaylist = false },
            title = { Text(stringResource(R.string.peach_add_to_playlist)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    (playlists.value as? PeachLoad.Ready)?.value?.forEach { playlist ->
                        TextButton(enabled = !actions.isBusy, onClick = {
                            choosingPlaylist = false
                            actions.run(successMessage = addedText) { addToPlaylist(playlist.id, listOf(asset.id)) }
                        }) { Text("${playlist.name} (${playlist.itemCount})") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(
                            newName,
                            { newName = it.take(80) },
                            singleLine = true,
                            label = { Text(stringResource(R.string.peach_playlist_create)) },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(enabled = newName.isNotBlank() && !actions.isBusy, onClick = {
                            val name = newName.trim()
                            choosingPlaylist = false
                            actions.run(successMessage = addedText) { createPlaylist(name, listOf(asset.id)) }
                        }) { Text(stringResource(R.string.peach_add)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingPlaylist = false }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text(stringResource(R.string.peach_purge_missing)) },
            text = { Text(stringResource(R.string.peach_purge_missing_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPurge = false
                    actions.run {
                        val result = purgeMissing(asset.id)
                        result.peachString("error")?.let { throw IllegalStateException(it) }
                    }
                }) { Text(stringResource(R.string.peach_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
}

@Composable
private fun regionName(region: String): String = when (region) {
    "jp" -> stringResource(R.string.peach_region_jp)
    "kr" -> stringResource(R.string.peach_region_kr)
    "cn" -> stringResource(R.string.peach_region_cn)
    "west" -> stringResource(R.string.peach_region_west)
    "other" -> stringResource(R.string.peach_junk_other)
    else -> stringResource(R.string.peach_region_auto)
}
