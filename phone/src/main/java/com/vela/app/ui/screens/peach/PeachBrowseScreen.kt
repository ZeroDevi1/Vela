package com.vela.app.ui.screens.peach

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vela.data.model.BaseItemDto
import com.vela.data.peach.PeachIds
import com.vela.data.peach.PeachImageUrls
import com.vela.data.peach.PeachIndexEntry
import com.vela.data.peach.PeachRepository
import com.vela.shared.R
import java.net.URLDecoder
import java.net.URLEncoder

/** 「Peach 浏览」各页的路由名。参数统一放在 `arg` 里。 */
object PeachBrowsePages {
    const val HUB = "browse"
    const val ROSTER = "roster"
    const val ITEMS = "items"
    const val FOLDERS = "folders"
    const val PLAYLISTS = "playlists"
    const val PLAYLIST = "playlist"
    const val FOLLOW = "follow"
    const val JAV = "jav"
    const val JAV_DETAIL = "javdetail"
    const val PHOTOS = "photos"
    const val PHOTO_SET = "photoset"
    const val SHORTCUTS = "shortcuts"

    /** 海报墙的筛选参数编码成查询串放进 `arg`。 */
    fun itemsArg(vararg query: Pair<String, String?>): String =
        query.filter { !it.second.isNullOrBlank() }
            .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }

    internal fun parseQuery(arg: String?): List<Pair<String, String>> =
        arg.orEmpty().split('&').filter { '=' in it }.map { part ->
            part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), "UTF-8")
        }
}

@Composable
fun PeachBrowseScreen(
    page: String,
    arg: String?,
    title: String?,
    onBack: () -> Unit,
    onOpenPage: (page: String, arg: String?, title: String?) -> Unit,
    onOpenItem: (BaseItemDto) -> Unit
) {
    when (page) {
        PeachBrowsePages.ROSTER -> PeachRosterPage(arg ?: "performers", title, onBack, onOpenPage, onOpenItem)
        PeachBrowsePages.ITEMS -> PeachItemsPage(PeachBrowsePages.parseQuery(arg), title ?: "Peach", onBack, onOpenItem)
        PeachBrowsePages.FOLDERS -> PeachFoldersPage(arg?.takeIf { it.isNotBlank() }, title, onBack, onOpenPage)
        PeachBrowsePages.PLAYLISTS -> PeachPlaylistsPage(onBack, onOpenPage)
        PeachBrowsePages.PLAYLIST -> PeachPlaylistPage(arg?.toLongOrNull() ?: 0L, title, onBack, onOpenItem)
        PeachBrowsePages.FOLLOW -> PeachFollowTimelinePage(onBack, onOpenItem)
        PeachBrowsePages.JAV -> PeachJavExplorePage(onBack, onOpenPage, onOpenItem)
        PeachBrowsePages.JAV_DETAIL -> PeachJavDetailPage(arg.orEmpty(), onBack, onOpenItem)
        PeachBrowsePages.PHOTOS -> PeachPhotosPage(arg.orEmpty(), title, onBack, onOpenPage)
        PeachBrowsePages.PHOTO_SET -> PeachPhotoSetPage(arg?.toLongOrNull() ?: 0L, title, onBack)
        PeachBrowsePages.SHORTCUTS -> PeachShortcutsPage(onBack, onOpenPage, onOpenItem)
        else -> PeachBrowseHub(onBack, onOpenPage, onOpenItem)
    }
}

private val ROSTER_KINDS = listOf(
    "performers" to R.string.peach_roster_performers,
    "creators" to R.string.peach_roster_creators,
    "studios" to R.string.peach_roster_studios,
    "agencies" to R.string.peach_roster_agencies,
    "series" to R.string.peach_roster_series,
    "tags" to R.string.peach_roster_tags
)

private fun entityKindOf(rosterKind: String): String = when (rosterKind) {
    "agencies" -> "agency"
    "series" -> "series"
    else -> rosterKind.removeSuffix("s")
}

private fun openEntity(entry: PeachIndexEntry, rosterKind: String, onOpenPage: (String, String?, String?) -> Unit, onOpenItem: (BaseItemDto) -> Unit) {
    val kind = entry.kind ?: entityKindOf(rosterKind)
    if (kind == "tag" || entry.id == null) {
        onOpenPage(PeachBrowsePages.ITEMS, PeachBrowsePages.itemsArg("tag" to entry.name), entry.name)
    } else {
        onOpenItem(BaseItemDto(id = PeachIds.entity(kind, entry.id!!), name = entry.name, type = "Person"))
    }
}

// region 入口

@Composable
private fun PeachBrowseHub(
    onBack: () -> Unit,
    onOpenPage: (String, String?, String?) -> Unit,
    onOpenItem: (BaseItemDto) -> Unit
) {
    val baseUrl = rememberPeachBaseUrl()
    val tops = rememberPeachLoad { tops() }
    PeachPageScaffold(stringResource(R.string.peach_browse_title), onBack) {
        item {
            PeachSection(stringResource(R.string.peach_browse_rosters)) {
                ROSTER_KINDS.forEachIndexed { index, (kind, label) ->
                    if (index > 0) PeachDivider()
                    val text = stringResource(label)
                    PeachRow(text, onClick = { onOpenPage(PeachBrowsePages.ROSTER, kind, text) })
                }
            }
        }
        item {
            PeachSection(stringResource(R.string.peach_browse_more)) {
                val folders = stringResource(R.string.peach_browse_folders)
                PeachRow(folders, onClick = { onOpenPage(PeachBrowsePages.FOLDERS, null, folders) })
                PeachDivider()
                PeachRow(stringResource(R.string.peach_browse_playlists), onClick = { onOpenPage(PeachBrowsePages.PLAYLISTS, null, null) })
                PeachDivider()
                PeachRow(stringResource(R.string.peach_browse_follow), onClick = { onOpenPage(PeachBrowsePages.FOLLOW, null, null) })
                PeachDivider()
                PeachRow(stringResource(R.string.peach_browse_jav), onClick = { onOpenPage(PeachBrowsePages.JAV, null, null) })
                PeachDivider()
                PeachRow(stringResource(R.string.peach_browse_shortcuts), onClick = { onOpenPage(PeachBrowsePages.SHORTCUTS, null, null) })
                PeachDivider()
                val trash = stringResource(R.string.peach_trash_title)
                PeachRow(trash, onClick = { onOpenPage(PeachBrowsePages.ITEMS, PeachBrowsePages.itemsArg("state" to "trash"), trash) })
            }
        }
        (tops.value as? PeachLoad.Ready)?.value?.let { value ->
            listOf(
                Triple(R.string.peach_browse_top_performers, value.performers, "performers"),
                Triple(R.string.peach_browse_top_studios, value.studios, "studios")
            ).filter { it.second.isNotEmpty() }.forEach { (label, entries, kind) ->
                item {
                    PeachSection(stringResource(label)) {
                        Row(
                            Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            entries.forEach { entry ->
                                Box(Modifier.width(84.dp)) {
                                    PeachEntityCard(
                                        name = entry.name,
                                        count = entry.count,
                                        imageUrl = entry.id?.takeIf { entry.hasImage }?.let {
                                            PeachImageUrls.imageUrl(baseUrl ?: return@let null, PeachIds.entity(entry.kind ?: "performer", it))
                                        },
                                        onClick = { openEntity(entry, kind, onOpenPage, onOpenItem) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// endregion

// region 名册

@Composable
private fun PeachRosterPage(
    kind: String,
    title: String?,
    onBack: () -> Unit,
    onOpenPage: (String, String?, String?) -> Unit,
    onOpenItem: (BaseItemDto) -> Unit
) {
    val baseUrl = rememberPeachBaseUrl()
    var query by remember { mutableStateOf("") }
    var appliedQuery by remember { mutableStateOf("") }
    val pager = rememberPeachPager(kind to appliedQuery) { offset -> roster(kind, appliedQuery, offset) }
    val isTags = kind == "tags"
    PeachGridScaffold(
        title = title ?: kind,
        onBack = onBack,
        minCellWidth = if (isTags) 140.dp else 96.dp,
        onReachEnd = pager::loadMore
    ) {
        fullWidth {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.peach_search)) },
                trailingIcon = { TextButton(onClick = { appliedQuery = query.trim() }) { Text(stringResource(R.string.peach_search)) } },
                modifier = Modifier.fillMaxWidth()
            )
        }
        items(pager.items, key = { "${it.id}:${it.name}" }) { entry ->
            if (isTags) {
                AssistChip(onClick = { openEntity(entry, kind, onOpenPage, onOpenItem) }, label = { Text("${entry.name} · ${entry.count ?: 0}", maxLines = 1) })
            } else {
                PeachEntityCard(
                    name = entry.name,
                    count = entry.count,
                    round = kind == "performers" || kind == "creators",
                    imageUrl = entry.id?.takeIf { entry.hasImage }?.let { id ->
                        baseUrl?.let { PeachImageUrls.imageUrl(it, PeachIds.entity(entry.kind ?: entityKindOf(kind), id)) }
                    },
                    onClick = { openEntity(entry, kind, onOpenPage, onOpenItem) }
                )
            }
        }
        peachPagerFooter(pager, R.string.peach_empty)
    }
}

// endregion

// region 海报墙与筛选

private val SORT_OPTIONS = listOf(
    "new" to R.string.peach_sort_new,
    "release" to R.string.peach_sort_release,
    "played" to R.string.peach_sort_played,
    "rating" to R.string.peach_sort_rating,
    "plays" to R.string.peach_sort_plays,
    "dur" to R.string.peach_sort_duration,
    "size" to R.string.peach_sort_size,
    "name" to R.string.peach_sort_name,
    "daily" to R.string.peach_sort_daily
)

private val STATE_OPTIONS = listOf(
    "" to R.string.peach_state_all,
    "fresh" to R.string.peach_state_fresh,
    "played" to R.string.peach_state_played,
    "later" to R.string.peach_state_later,
    "flagged" to R.string.peach_state_flagged
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PeachItemsPage(
    baseQuery: List<Pair<String, String>>,
    title: String,
    onBack: () -> Unit,
    onOpenItem: (BaseItemDto) -> Unit
) {
    val baseUrl = rememberPeachBaseUrl()
    val isTrash = baseQuery.any { it == ("state" to "trash") }
    var sort by remember { mutableStateOf(baseQuery.firstOrNull { it.first == "sort" }?.second ?: "new") }
    var state by remember { mutableStateOf(baseQuery.firstOrNull { it.first == "state" }?.second.orEmpty()) }
    var filters by remember { mutableStateOf(mapOf<String, String>()) }
    var showFilters by remember { mutableStateOf(false) }
    val query = remember(sort, state, filters) {
        baseQuery.filterNot { it.first in setOf("sort", "state") || it.first in filters } +
            listOfNotNull("sort" to sort, state.takeIf { it.isNotEmpty() }?.let { "state" to it }) +
            filters.toList()
    }
    val pager = rememberPeachPager(query) { offset -> items(query, offset) }
    val actions = rememberPeachActions()
    PeachGridScaffold(
        title = title,
        onBack = onBack,
        minCellWidth = 150.dp,
        onReachEnd = pager::loadMore,
        actions = {
            if (!isTrash) {
                IconButton(onClick = { showFilters = true }) {
                    Icon(Icons.Rounded.FilterList, contentDescription = stringResource(R.string.peach_filters), tint = Color.White)
                }
            }
        }
    ) {
        if (!isTrash) {
            fullWidth {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SORT_OPTIONS.forEach { (key, label) ->
                        FilterChip(selected = sort == key, onClick = { sort = key }, label = { Text(stringResource(label)) })
                    }
                }
            }
            fullWidth {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    STATE_OPTIONS.forEach { (key, label) ->
                        FilterChip(selected = state == key, onClick = { state = key }, label = { Text(stringResource(label)) })
                    }
                    filters.forEach { (key, value) ->
                        FilterChip(selected = true, onClick = { filters = filters - key }, label = { Text("$value ×") })
                    }
                }
            }
        } else {
            fullWidth { PeachHint(stringResource(R.string.peach_trash_hint)) }
        }
        items(pager.items, key = { it.id ?: it.hashCode().toString() }) { item ->
            Column {
                PeachMediaCard(
                    title = item.name.orEmpty(),
                    subtitle = listOfNotNull(
                        item.providerIds?.get(com.vela.data.peach.PeachMapper.PROVIDER_CODE),
                        item.people?.firstOrNull()?.name
                    ).joinToString(" · "),
                    imageUrl = baseUrl?.let { PeachImageUrls.imageUrl(it, item.id.orEmpty(), item.imageTags?.get("Primary")) },
                    onClick = { onOpenItem(item) }
                )
                if (isTrash) {
                    TextButton(enabled = !actions.isBusy, onClick = {
                        val id = PeachIds.assetId(item.id) ?: return@TextButton
                        actions.run(onDone = { pager.items.remove(item) }) { batch(listOf(id), "restore") }
                    }) { Text(stringResource(R.string.peach_restore)) }
                }
            }
        }
        peachPagerFooter(pager, R.string.peach_empty)
    }
    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            val facets = rememberPeachLoad { facets(query.filterNot { it.first == "sort" }) }
            Column(
                Modifier
                    .padding(16.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (val value = facets.value) {
                    PeachLoad.Loading -> Text(stringResource(R.string.peach_loading))
                    is PeachLoad.Failed -> Text(value.error.message.orEmpty())
                    is PeachLoad.Ready -> {
                        val f = value.value
                        listOf(
                            Triple(R.string.peach_filter_year, "year", f.years),
                            Triple(R.string.peach_filter_region, "region", f.regions),
                            Triple(R.string.peach_filter_orientation, "orient", f.orientations),
                            Triple(R.string.peach_filter_location, "loc", f.locations),
                            Triple(R.string.peach_filter_tag, "tag", f.tags.take(80))
                        ).filter { it.third.isNotEmpty() }.forEach { (label, key, options) ->
                            Text(stringResource(label), fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                options.forEach { option ->
                                    FilterChip(
                                        selected = filters[key] == option.key,
                                        onClick = {
                                            filters = if (filters[key] == option.key) filters - key else filters + (key to option.key)
                                        },
                                        label = { Text("${option.label} ${option.count}") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// endregion

// region 原目录

@Composable
private fun PeachFoldersPage(
    folderId: String?,
    title: String?,
    onBack: () -> Unit,
    onOpenPage: (String, String?, String?) -> Unit
) {
    val folders = rememberPeachLoad { folders(folderId) }
    PeachPageScaffold(title ?: stringResource(R.string.peach_browse_folders), onBack, onRefresh = folders::reload) {
        peachLoadItems(folders) { value ->
            val selected = value.selected
            if (selected != null) {
                item {
                    PeachSection {
                        if (value.ancestors.size > 1) {
                            PeachRow(value.ancestors.dropLast(1).joinToString(" / ") { it.name })
                            PeachDivider()
                        }
                        PeachRow(
                            stringResource(R.string.peach_folder_items, selected.videos),
                            onClick = {
                                onOpenPage(PeachBrowsePages.ITEMS, PeachBrowsePages.itemsArg("folder" to selected.id), selected.name)
                            }
                        )
                        PeachDivider()
                        PeachRow(
                            stringResource(R.string.peach_folder_items_here),
                            onClick = {
                                onOpenPage(
                                    PeachBrowsePages.ITEMS,
                                    PeachBrowsePages.itemsArg("folder" to selected.id, "folder_recursive" to "0"),
                                    selected.name
                                )
                            }
                        )
                    }
                }
            }
            val list = if (selected == null) value.roots else value.children
            item {
                PeachSection {
                    PeachRows(list, stringResource(R.string.peach_empty)) { folder ->
                        PeachRow(
                            title = folder.name,
                            subtitle = listOfNotNull(folder.location, folder.path.takeIf { selected == null }).joinToString(" · "),
                            value = folder.videos.toString(),
                            onClick = {
                                if (folder.hasChildren) {
                                    onOpenPage(PeachBrowsePages.FOLDERS, folder.id, folder.name)
                                } else {
                                    onOpenPage(PeachBrowsePages.ITEMS, PeachBrowsePages.itemsArg("folder" to folder.id), folder.name)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// endregion

// region 播放列表

@Composable
private fun PeachPlaylistsPage(onBack: () -> Unit, onOpenPage: (String, String?, String?) -> Unit) {
    val playlists = rememberPeachLoad { playlists() }
    val actions = rememberPeachActions()
    var editing by remember { mutableStateOf<Pair<Long?, String>?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    PeachPageScaffold(stringResource(R.string.peach_browse_playlists), onBack, onRefresh = playlists::reload) {
        item {
            OutlinedButton(onClick = { editing = null to "" }) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Text(stringResource(R.string.peach_playlist_create))
            }
        }
        peachLoadItems(playlists) { items ->
            item {
                PeachSection {
                    PeachRows(items, stringResource(R.string.peach_empty)) { playlist ->
                        PeachRow(
                            title = playlist.name,
                            subtitle = if (playlist.isMix) stringResource(R.string.peach_playlist_mix) else null,
                            value = playlist.itemCount.toString(),
                            onClick = { onOpenPage(PeachBrowsePages.PLAYLIST, playlist.id.toString(), playlist.name) },
                            trailing = {
                                Row {
                                    TextButton(onClick = { editing = playlist.id to playlist.name }) { Text(stringResource(R.string.peach_rename)) }
                                    TextButton(onClick = { deleting = playlist.id }) { Text(stringResource(R.string.peach_remove)) }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    editing?.let { (id, initial) ->
        var name by remember(id) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (id == null) R.string.peach_playlist_create else R.string.peach_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && !actions.isBusy, onClick = {
                    val value = name.trim()
                    actions.run(onDone = { editing = null; playlists.reload() }) {
                        if (id == null) createPlaylist(value) else renamePlaylist(id, value)
                    }
                }) { Text(stringResource(R.string.peach_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.peach_playlist_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.run(onDone = { deleting = null; playlists.reload() }) { deletePlaylist(id) }
                }) { Text(stringResource(R.string.peach_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
}

@Composable
private fun PeachPlaylistPage(playlistId: Long, title: String?, onBack: () -> Unit, onOpenItem: (BaseItemDto) -> Unit) {
    val baseUrl = rememberPeachBaseUrl()
    val items = rememberPeachLoad { playlistItems(playlistId) }
    val actions = rememberPeachActions()
    PeachGridScaffold(title = title ?: "", onBack = onBack, minCellWidth = 150.dp) {
        when (val value = items.value) {
            PeachLoad.Loading -> fullWidth { PeachHint(stringResource(R.string.peach_loading)) }
            is PeachLoad.Failed -> fullWidth { PeachErrorCard(value.error, items::reload) }
            is PeachLoad.Ready -> {
                if (value.value.isEmpty()) fullWidth { PeachHint(stringResource(R.string.peach_empty)) }
                items(value.value, key = { it.id.orEmpty() }) { item ->
                    Column {
                        PeachMediaCard(
                            title = item.name.orEmpty(),
                            subtitle = item.providerIds?.get(com.vela.data.peach.PeachMapper.PROVIDER_CODE),
                            imageUrl = baseUrl?.let { PeachImageUrls.imageUrl(it, item.id.orEmpty(), item.imageTags?.get("Primary")) },
                            onClick = { onOpenItem(item) }
                        )
                        TextButton(enabled = !actions.isBusy, onClick = {
                            val assetId = PeachIds.assetId(item.id) ?: return@TextButton
                            actions.run(onDone = items::reload) { removeFromPlaylist(playlistId, assetId) }
                        }) { Text(stringResource(R.string.peach_remove)) }
                    }
                }
            }
        }
    }
}

// endregion

// region 关注时间线

private val FOLLOW_TABS = listOf(
    "new" to R.string.peach_follow_tab_new,
    "seen" to R.string.peach_follow_tab_seen,
    "saved" to R.string.peach_follow_tab_saved,
    "ignored" to R.string.peach_follow_tab_ignored
)

@Composable
private fun PeachFollowTimelinePage(onBack: () -> Unit, onOpenItem: (BaseItemDto) -> Unit) {
    val baseUrl = rememberPeachBaseUrl()
    var tab by remember { mutableStateOf(0) }
    var sort by remember { mutableStateOf("new") }
    val status = FOLLOW_TABS[tab].first
    val pager = rememberPeachPager(status to sort) { offset ->
        val page = followTimeline(status = status, sort = sort, offset = offset)
        page.items to page.hasMore
    }
    val actions = rememberPeachActions()
    PeachGridScaffold(title = stringResource(R.string.peach_browse_follow), onBack = onBack, minCellWidth = 160.dp, onReachEnd = pager::loadMore) {
        fullWidth {
            TabRow(selectedTabIndex = tab, containerColor = Color.Black) {
                FOLLOW_TABS.forEachIndexed { index, (_, label) ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) })
                }
            }
        }
        fullWidth {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("new" to R.string.peach_sort_new, "hot" to R.string.peach_sort_hot, "dur" to R.string.peach_sort_duration, "rand" to R.string.peach_sort_random)
                    .forEach { (key, label) ->
                        FilterChip(selected = sort == key, onClick = { sort = key }, label = { Text(stringResource(label)) })
                    }
            }
        }
        items(pager.items, key = { it.id }) { post ->
            Column {
                PeachMediaCard(
                    title = post.displayTitle,
                    subtitle = listOfNotNull(post.author, post.providerLabel).joinToString(" · "),
                    imageUrl = peachUrl(baseUrl, post.thumbUrl),
                    aspectRatio = 16f / 9f,
                    onClick = { onOpenItem(com.vela.data.peach.PeachMapper.followToBaseItem(post)) }
                )
                Row {
                    if (post.status != "seen") {
                        TextButton(enabled = !actions.isBusy, onClick = {
                            actions.run(onDone = { pager.items.remove(post) }) { setFollowStatus(listOf(post.id), "seen") }
                        }) { Text(stringResource(R.string.peach_action_seen)) }
                    }
                    if (!post.isSaved) {
                        TextButton(enabled = !actions.isBusy, onClick = {
                            actions.run(successMessage = null) { saveFollowItem(post.id) }
                        }) { Text(stringResource(R.string.peach_follow_save)) }
                    }
                    if (post.status != "ignored") {
                        TextButton(enabled = !actions.isBusy, onClick = {
                            actions.run(onDone = { pager.items.remove(post) }) { setFollowStatus(listOf(post.id), "ignored") }
                        }) { Text(stringResource(R.string.peach_ignore)) }
                    }
                }
            }
        }
        peachPagerFooter(pager, R.string.peach_empty)
    }
}

// endregion

// region JAV 探索

@Composable
private fun PeachJavExplorePage(
    onBack: () -> Unit,
    onOpenPage: (String, String?, String?) -> Unit,
    onOpenItem: (BaseItemDto) -> Unit
) {
    val baseUrl = rememberPeachBaseUrl()
    var scope by remember { mutableStateOf("external") }
    var sort by remember { mutableStateOf("taste") }
    val pager = rememberPeachPager(scope to sort) { _ -> javDiscoveries(scope, sort) to false }
    PeachGridScaffold(title = stringResource(R.string.peach_browse_jav), onBack = onBack, minCellWidth = 150.dp) {
        fullWidth {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = scope == "external", onClick = { scope = "external" }, label = { Text(stringResource(R.string.peach_jav_external)) })
                FilterChip(selected = scope == "library", onClick = { scope = "library" }, label = { Text(stringResource(R.string.peach_jav_library)) })
                listOf(
                    "taste" to R.string.peach_sort_taste,
                    "release_date" to R.string.peach_sort_release,
                    "source_score" to R.string.peach_sort_rating,
                    "duration" to R.string.peach_sort_duration
                ).forEach { (key, label) ->
                    FilterChip(selected = sort == key, onClick = { sort = key }, label = { Text(stringResource(label)) })
                }
            }
        }
        items(pager.items, key = { it.code }) { work ->
            PeachMediaCard(
                title = listOfNotNull(work.code, work.title).joinToString(" "),
                subtitle = listOfNotNull(work.performers, work.releaseDate).joinToString(" · "),
                imageUrl = when {
                    work.assetId != null -> baseUrl?.let { PeachImageUrls.imageUrl(it, work.assetId.toString(), coverKey = work.code.takeIf { work.hasCover }) }
                    work.hasCover -> peachUrl(baseUrl, "/cover?code=${PeachImageUrls.encodeQueryValue(work.code)}&thumb=2")
                    else -> peachUrl(baseUrl, "/cover-image?code=${PeachImageUrls.encodeQueryValue(work.code)}")
                },
                onClick = {
                    val assetId = work.assetId
                    if (assetId != null) {
                        onOpenItem(BaseItemDto(id = assetId.toString(), name = work.title ?: work.code, type = "Movie"))
                    } else {
                        onOpenPage(PeachBrowsePages.JAV_DETAIL, work.code, work.code)
                    }
                }
            )
        }
        peachPagerFooter(pager, R.string.peach_empty)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeachJavDetailPage(code: String, onBack: () -> Unit, onOpenItem: (BaseItemDto) -> Unit) {
    val baseUrl = rememberPeachBaseUrl()
    val context = LocalContext.current
    val detail = rememberPeachLoad { javDetail(code) }
    val owned = rememberPeachLoad { libraryItemsForCode(code) }
    val reviews = rememberPeachLoad { javReviews(code) }
    val actions = rememberPeachActions()
    var showResources by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    PeachPageScaffold(code, onBack, onRefresh = { detail.reload(); owned.reload() }) {
        peachLoadItems(detail) { work ->
            item {
                AsyncImage(
                    model = peachUrl(baseUrl, "/jav-image?code=${PeachImageUrls.encodeQueryValue(work.code)}&image=cover"),
                    contentDescription = work.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.5f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1A1A1A))
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(listOfNotNull(work.code, work.title).joinToString(" "), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(work.releaseDate, work.studio, work.director, work.durationMinutes?.let { "${it} min" }, work.score?.let { "★ $it" })
                            .joinToString(" · "),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    work.message?.let { PeachHint(it) }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!work.previewVideoUrl.isNullOrBlank()) {
                        Button(onClick = { onOpenItem(BaseItemDto(id = PeachIds.javPreview(work.code), name = work.code, type = "Movie")) }) {
                            Text(stringResource(R.string.peach_jav_preview))
                        }
                    }
                    OutlinedButton(enabled = !actions.isBusy, onClick = {
                        actions.run(onDone = detail::reload) {
                            val wantId = work.wantId
                            if (work.wanted && wantId != null) removeWants(listOf(wantId)) else addWant(work.code)
                        }
                    }) { Text(stringResource(if (work.wanted) R.string.peach_unwant else R.string.peach_want)) }
                    OutlinedButton(onClick = { showResources = true }) { Text(stringResource(R.string.peach_find_resources)) }
                    work.sourceUrl?.let { url ->
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text(stringResource(R.string.peach_open_source)) }
                    }
                }
            }
            (owned.value as? PeachLoad.Ready)?.value?.takeIf { it.isNotEmpty() }?.let { items ->
                item {
                    PeachSection(stringResource(R.string.peach_jav_in_library)) {
                        PeachRows(items, "") { item -> PeachRow(item.name.orEmpty(), onClick = { onOpenItem(item) }) }
                    }
                }
            }
            if (work.performers.isNotEmpty()) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        work.performers.forEach { ref ->
                            AssistChip(
                                onClick = {
                                    ref.id?.let { id -> onOpenItem(BaseItemDto(id = PeachIds.entity("performer", id), name = ref.name, type = "Person")) }
                                },
                                label = { Text(ref.name) }
                            )
                        }
                    }
                }
            }
            if (work.tags.isNotEmpty()) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        work.tags.forEach { tag -> AssistChip(onClick = {}, label = { Text(tag) }) }
                    }
                }
            }
            work.summary?.let { summary ->
                item { Text(summary, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium) }
            }
            if (work.previewCount > 0) {
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        (0 until work.previewCount).forEach { index ->
                            val url = peachUrl(baseUrl, "/jav-image?code=${PeachImageUrls.encodeQueryValue(work.code)}&image=$index")
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(160.dp)
                                    .aspectRatio(1.5f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { viewing = url }
                            )
                        }
                    }
                }
            }
            (reviews.value as? PeachLoad.Ready)?.value?.first?.takeIf { it.isNotEmpty() }?.let { list ->
                item {
                    PeachSection(stringResource(R.string.peach_jav_reviews)) {
                        PeachRows(list, "") { review ->
                            PeachRow(review.content, subtitle = listOfNotNull(review.username, review.score?.let { "★ $it" }, review.createdAt).joinToString(" · "))
                        }
                    }
                }
            }
        }
    }
    if (showResources) PeachResourceDialog(code = code, title = null, onDismiss = { showResources = false })
    viewing?.let { url -> PeachImageViewer(url) { viewing = null } }
}

// endregion

// region 图集

@Composable
private fun PeachPhotosPage(arg: String, title: String?, onBack: () -> Unit, onOpenPage: (String, String?, String?) -> Unit) {
    val baseUrl = rememberPeachBaseUrl()
    val kind = arg.substringBefore(':')
    val entityId = arg.substringAfter(':').toLongOrNull() ?: 0L
    val pager = rememberPeachPager(arg) { offset ->
        val page = photos(kind, entityId, offset)
        // 图集分组只在第一页给出，用负数 ID 与散图区分。
        val sets = if (offset == 0) page.sets.map { PhotoTile.Set(it) } else emptyList()
        (sets + page.photos.map { PhotoTile.Photo(it) }) to page.hasMore
    }
    var viewing by remember { mutableStateOf<String?>(null) }
    PeachGridScaffold(title = title ?: stringResource(R.string.peach_photos), onBack = onBack, minCellWidth = 110.dp, onReachEnd = pager::loadMore) {
        items(pager.items, key = { it.key }) { tile ->
            when (tile) {
                is PhotoTile.Set -> PeachMediaCard(
                    title = tile.set.title,
                    subtitle = tile.set.count.toString(),
                    imageUrl = if (tile.set.kind == "code") {
                        peachUrl(baseUrl, "/sample-thumb?code=${PeachImageUrls.encodeQueryValue(tile.set.code.orEmpty())}&n=0")
                    } else {
                        peachUrl(baseUrl, "/photo-thumb?id=${tile.set.id}")
                    },
                    aspectRatio = 1f,
                    onClick = {
                        tile.set.id.toLongOrNull()?.let { onOpenPage(PeachBrowsePages.PHOTO_SET, it.toString(), tile.set.title) }
                    }
                )
                is PhotoTile.Photo -> AsyncImage(
                    model = peachUrl(baseUrl, "/photo-thumb?id=${tile.id}"),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewing = peachUrl(baseUrl, "/photo?id=${tile.id}") }
                )
            }
        }
        peachPagerFooter(pager, R.string.peach_empty)
    }
    viewing?.let { url -> PeachImageViewer(url) { viewing = null } }
}

private sealed interface PhotoTile {
    val key: String

    data class Set(val set: com.vela.data.peach.PeachPhotoPage.PhotoSet) : PhotoTile {
        override val key: String get() = "set:${set.id}"
    }

    data class Photo(val id: Long) : PhotoTile {
        override val key: String get() = "photo:$id"
    }
}

@Composable
private fun PeachPhotoSetPage(anchorId: Long, title: String?, onBack: () -> Unit) {
    val baseUrl = rememberPeachBaseUrl()
    val pager = rememberPeachPager(anchorId) { offset ->
        val page = photoSet(anchorId, offset)
        page.photos to page.hasMore
    }
    var viewing by remember { mutableStateOf<String?>(null) }
    PeachGridScaffold(title = title ?: stringResource(R.string.peach_photos), onBack = onBack, minCellWidth = 110.dp, onReachEnd = pager::loadMore) {
        items(pager.items, key = { it }) { id ->
            AsyncImage(
                model = peachUrl(baseUrl, "/photo-thumb?id=$id"),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { viewing = peachUrl(baseUrl, "/photo?id=$id") }
            )
        }
        peachPagerFooter(pager, R.string.peach_empty)
    }
    viewing?.let { url -> PeachImageViewer(url) { viewing = null } }
}

/** 全屏看大图，点任意处关闭。 */
@Composable
internal fun PeachImageViewer(url: String, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

// endregion

// region 收藏与保存的筛选

@Composable
private fun PeachShortcutsPage(
    onBack: () -> Unit,
    onOpenPage: (String, String?, String?) -> Unit,
    onOpenItem: (BaseItemDto) -> Unit
) {
    val shortcuts = rememberPeachLoad { shortcuts() }
    PeachPageScaffold(stringResource(R.string.peach_browse_shortcuts), onBack, onRefresh = shortcuts::reload) {
        peachLoadItems(shortcuts) { value ->
            item {
                PeachSection(stringResource(R.string.peach_shortcuts_favorites)) {
                    PeachRows(value.favorites, stringResource(R.string.peach_empty)) { favorite ->
                        PeachRow(favorite.name, subtitle = favorite.kind, onClick = {
                            val id = favorite.entityId
                            if (favorite.kind == "tag" || id == null) {
                                onOpenPage(PeachBrowsePages.ITEMS, PeachBrowsePages.itemsArg("tag" to favorite.name), favorite.name)
                            } else {
                                onOpenItem(BaseItemDto(id = PeachIds.entity(favorite.kind, id), name = favorite.name, type = "Person"))
                            }
                        })
                    }
                }
            }
            item {
                PeachSection(stringResource(R.string.peach_shortcuts_filters)) {
                    PeachRows(value.filters, stringResource(R.string.peach_empty)) { filter ->
                        PeachRow(filter.name, onClick = {
                            onOpenPage(PeachBrowsePages.ITEMS, savedFilterArg(filter.href), filter.name)
                        })
                    }
                }
            }
        }
    }
}

/** 网页保存的筛选是站内地址（`/unseen?tag=…`），换成海报墙的查询参数。 */
internal fun savedFilterArg(href: String): String {
    val path = href.substringBefore('?')
    val state = when (path) {
        "/unseen" -> "fresh"
        "/watch-later" -> "later"
        "/flagged" -> "flagged"
        else -> null
    }
    val query = href.substringAfter('?', "")
    return listOfNotNull(state?.let { "state=$it" }, query.takeIf { it.isNotBlank() }).joinToString("&")
}

// endregion
