package com.vela.app.ui.screens.library

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.vela.app.ui.screens.books.BookReaderScreen
import com.vela.app.ui.screens.books.RemoteComicArchive
import com.vela.data.repository.BookRangeUnsupportedException
import com.vela.app.ui.screens.music.MusicMiniPlayer
import com.vela.app.ui.screens.music.MusicNowPlayingScreen
import com.vela.app.ui.screens.music.MusicPlayback
import com.vela.app.ui.screens.music.musicTime
import com.vela.data.model.*
import com.vela.data.repository.AuthRepositoryProvider
import com.vela.data.repository.LibraryMediaSession
import com.vela.data.repository.MediaRepositoryProvider
import com.vela.data.repository.libraryCacheKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

internal enum class MediaLibraryKind(val title: String) { MUSIC("音乐"), BOOKS("书籍") }
private enum class LibrarySection(val label: String) {
    SONGS("歌曲"), ALBUMS("专辑"), ARTISTS("歌手"), PLAYLISTS("歌单"), GENRES("流派"),
    BOOKS("书架"), AUTHORS("作者"), AUDIOBOOKS("有声书"), FAVORITES("收藏"), FOLDERS("文件夹")
}

@UnstableApi
@Composable
internal fun MediaLibraryScreen(kind: MediaLibraryKind, libraryId: String?, initialItemId: String?, showNowPlaying: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { MediaRepositoryProvider.getInstance(context) }
    val auth = remember(context) { AuthRepositoryProvider.getInstance(context) }
    val accountFlow = remember(auth) { auth.getActiveServerId() }
    val account by accountFlow.collectAsState(initial = null)
    val serverUrlFlow = remember(auth) { auth.getServerUrl() }
    val serverUrl by serverUrlFlow.collectAsState(initial = null)
    var session by remember(account, serverUrl) { mutableStateOf<LibraryMediaSession?>(null) }
    var error by remember(account, serverUrl) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(account, serverUrl, reload) {
        try { session = repository.openLibrarySession(); error = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法连接媒体服务器" }
    }
    val current = session
    if (current != null) key(current.accountKey, current.baseUrl, libraryId, initialItemId, kind) {
        LibraryBrowser(current, kind, libraryId, initialItemId, showNowPlaying, onBack)
    } else LibraryLoadingScaffold(kind.title, onBack, error, { reload++ })
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
private fun LibraryBrowser(session: LibraryMediaSession, kind: MediaLibraryKind, libraryId: String?, initialItemId: String?, showNowPlaying: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sections = if (kind == MediaLibraryKind.MUSIC) listOf(LibrarySection.SONGS, LibrarySection.ALBUMS, LibrarySection.ARTISTS,
        LibrarySection.PLAYLISTS, LibrarySection.GENRES, LibrarySection.FAVORITES, LibrarySection.FOLDERS)
    else listOf(LibrarySection.BOOKS, LibrarySection.AUTHORS, LibrarySection.AUDIOBOOKS, LibrarySection.FAVORITES, LibrarySection.FOLDERS)
    var section by rememberSaveable { mutableStateOf(sections.first()) }
    var query by rememberSaveable { mutableStateOf("") }
    var listView by rememberSaveable(kind) { mutableStateOf(false) }
    var newest by rememberSaveable { mutableStateOf(false) }
    var title by remember { mutableStateOf(kind.title) }
    var group by remember { mutableStateOf<BaseItemDto?>(null) }
    var trail by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var items by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var canLoadMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var preparingQueue by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var selectedBook by remember { mutableStateOf<BaseItemDto?>(null) }
    var showPlayer by rememberSaveable { mutableStateOf(showNowPlaying) }
    var actionItem by remember { mutableStateOf<BaseItemDto?>(null) }
    var playlistName by rememberSaveable { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf<BaseItemDto?>(null) }
    val recentPrefs = remember(context) { context.getSharedPreferences("book_library", Context.MODE_PRIVATE) }
    val recentKey = libraryCacheKey(session.accountKey + "|" + libraryId.orEmpty())
    val playback by MusicPlayback.state.collectAsState()

    fun open(item: BaseItemDto) {
        when {
            item.isBookItem() -> selectedBook = item
            item.isAudioItem() -> scope.launch {
                try {
                    val audio = items.filter { it.isAudioItem() }
                    val position = audio.indexOfFirst { it.id == item.id }
                    MusicPlayback.play(context, session, if (position >= 0) audio else listOf(item), position.coerceAtLeast(0))
                    showPlayer = true
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "无法开始播放" }
            }
            else -> { group?.let { trail = trail + it }; group = item; query = "" }
        }
    }

    fun request(start: Int, limit: Int): MediaLibraryQuery {
        val type = when {
            group?.type == "MusicAlbum" || group?.type == "MusicArtist" || group?.type == "MusicGenre" -> "Audio"
            group?.type == "Person" -> if (kind == MediaLibraryKind.MUSIC) "Audio" else "Book,AudioBook"
            section == LibrarySection.FOLDERS -> null
            section == LibrarySection.ALBUMS -> "MusicAlbum"
            section == LibrarySection.PLAYLISTS -> "Playlist"
            section == LibrarySection.AUDIOBOOKS -> "AudioBook"
            kind == MediaLibraryKind.MUSIC -> "Audio"
            else -> "Book"
        }
        val person = group?.takeIf { it.type in setOf("Person", "MusicArtist") }?.id
        val genre = group?.takeIf { it.type in setOf("Genre", "MusicGenre") }?.id
        val parent = if (person != null || genre != null) libraryId else group?.id ?: libraryId
        return MediaLibraryQuery(parentId = parent, includeItemTypes = type,
            recursive = section != LibrarySection.FOLDERS || person != null || genre != null,
            searchTerm = query.trim().takeIf { it.isNotEmpty() }, startIndex = start, limit = limit,
            sortBy = if (group?.type == "MusicAlbum") "ParentIndexNumber,IndexNumber,SortName" else if (newest) "DateCreated" else "SortName",
            sortOrder = if (newest && group?.type != "MusicAlbum") "Descending" else "Ascending",
            filters = if (section == LibrarySection.FAVORITES) "IsFavorite" else null, personIds = person, genreIds = genre)
    }

    val groupSnapshot = group
    val sectionSnapshot = section
    val querySnapshot = request(0, 60)
    val signature = listOf(section, query, newest, group?.id, reload)
    val latestSignature by rememberUpdatedState(signature)
    suspend fun page(start: Int, limit: Int): QueryResult<BaseItemDto> = when {
        groupSnapshot?.type == "Playlist" -> session.playlistItems(requireNotNull(groupSnapshot.id), start, limit)
        groupSnapshot == null && sectionSnapshot in listOf(LibrarySection.ARTISTS, LibrarySection.AUTHORS) ->
            session.people(libraryId, if (sectionSnapshot == LibrarySection.ARTISTS) "Artist,AlbumArtist" else "Author", querySnapshot.searchTerm, start, limit)
        groupSnapshot == null && sectionSnapshot == LibrarySection.GENRES -> session.genres(libraryId, "Audio", start, limit)
        else -> session.items(querySnapshot.copy(startIndex = start, limit = limit))
    }

    LaunchedEffect(session, libraryId) {
        try {
            if (libraryId != null) title = session.item(libraryId).name ?: kind.title
            if (kind == MediaLibraryKind.BOOKS) recentPrefs.getString(recentKey, null)?.let { recent = session.item(it) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* 可选标题/续读不影响书库主体，主查询独立展示错误。 */ }
    }
    LaunchedEffect(initialItemId) {
        if (initialItemId != null) try {
            val item = session.item(initialItemId)
            if (item.isAudioItem()) {
                MusicPlayback.play(context, session, listOf(item)); showPlayer = true
            } else if (item.isBookItem()) selectedBook = item else group = item
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法打开条目" }
    }
    LaunchedEffect(section, query, newest, group?.id, reload) {
        loading = true; loadingMore = false; items = emptyList(); error = null
        try {
            if (query.isNotBlank()) delay(300)
            val result = page(0, 60)
            items = result.items.orEmpty()
            total = result.totalRecordCount ?: items.size
            canLoadMore = result.totalRecordCount?.let { items.size < it } ?: (items.size == 60)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "加载失败，请重试" }
        finally { loading = false }
    }

    fun loadMore() { scope.launch {
        loadingMore = true
        try {
            val result = page(items.size, 60)
            if (signature != latestSignature) return@launch
            val next = result.items.orEmpty()
            items = items + next
            total = result.totalRecordCount ?: items.size
            canLoadMore = next.isNotEmpty() && (result.totalRecordCount?.let { items.size < it } ?: (next.size == 60))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "加载下一页失败" }
        finally { loadingMore = false }
    } }
    fun playAll(shuffle: Boolean) { scope.launch {
        preparingQueue = true
        try {
            val all = mutableListOf<BaseItemDto>()
            var offset = 0
            do {
                val result = page(offset, 200)
                val next = result.items.orEmpty()
                all += next.filter { it.isAudioItem() }
                offset += next.size
                if (offset > 10000) throw IllegalStateException("队列最多支持 10000 项，请选择一个专辑或歌单")
                val more = next.isNotEmpty() && (result.totalRecordCount?.let { offset < it } ?: (next.size == 200))
            } while (more)
            if (signature != latestSignature) return@launch
            if (all.isEmpty()) throw IllegalStateException("没有可播放的音频")
            MusicPlayback.play(context, session, if (shuffle) all.shuffled() else all)
            showPlayer = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "准备播放列表失败" }
        finally { preparingQueue = false }
    } }

    val back = {
        when {
            showPlayer -> showPlayer = false
            selectedBook != null -> selectedBook = null
            group != null -> { group = trail.lastOrNull(); trail = trail.dropLast(1); query = "" }
            else -> onBack()
        }
    }
    BackHandler { back() }
    if (showPlayer && playback.item != null) {
        MusicNowPlayingScreen(onBack = { showPlayer = false }); return
    }
    selectedBook?.let { book ->
        BookDetailScreen(session, book, onBack = { selectedBook = null }, onRead = {
            recentPrefs.edit().putString(recentKey, book.id).apply(); recent = book
        })
        return
    }

    val supportsGrid = group?.type !in listOf("MusicAlbum", "Playlist") &&
        (section in listOf(LibrarySection.BOOKS, LibrarySection.ALBUMS, LibrarySection.PLAYLISTS, LibrarySection.AUDIOBOOKS) ||
            (kind == MediaLibraryKind.BOOKS && section == LibrarySection.FAVORITES))
    Scaffold(topBar = {
        TopAppBar(title = { Column {
            Text(group?.name ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            Text(if (kind == MediaLibraryKind.BOOKS) "每一页，都是新的世界" else "让喜欢的声音陪伴你", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, navigationIcon = {
            IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, actions = {
            IconButton(onClick = { reload++ }, enabled = !loading) { Icon(Icons.Default.Refresh, "刷新") }
        })
    }, bottomBar = { if (playback.item != null) MusicMiniPlayer(onOpen = { showPlayer = true }, modifier = Modifier.navigationBarsPadding()) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (group == null) LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sections) { tab -> FilterChip(selected = section == tab, onClick = { section = tab; query = "" }, label = { Text(tab.label) }) }
            }
            if (group?.type != "Playlist" && section != LibrarySection.GENRES) OutlinedTextField(query, { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
                placeholder = { Text("搜索${group?.name ?: section.label}") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清除搜索") } },
                shape = MaterialTheme.shapes.extraLarge)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (loading) "正在加载…" else "${section.label} · $total 项", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (supportsGrid) IconButton(onClick = { listView = !listView }) { Icon(if (listView) Icons.Default.GridView else Icons.Default.ViewList, if (listView) "网格视图" else "列表视图") }
                TextButton(onClick = { newest = !newest }, enabled = !loading && group?.type != "Playlist") {
                    Icon(Icons.Default.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (newest) "最近添加" else "名称排序")
                }
            }
            if (!loading && items.any { it.isAudioItem() }) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { playAll(false) }, enabled = !preparingQueue) {
                    Icon(Icons.Default.PlayArrow, null); Text(if (preparingQueue) "正在准备…" else "播放全部")
                }
                OutlinedButton(onClick = { playAll(true) }, enabled = !preparingQueue) { Icon(Icons.Default.Shuffle, null); Text("随机播放") }
            }
            error?.let { LibraryError(it, { reload++ }) }
            PullToRefreshBox(isRefreshing = loading, onRefresh = { reload++ }, modifier = Modifier.weight(1f)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                items.isEmpty() && error == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (query.isNotBlank()) "没有匹配的内容，试试其他关键词" else "这里还没有${section.label}，可在 Jellyfin 中添加或整理媒体", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    val grid = !listView && supportsGrid
                    if (grid) LazyVerticalGrid(GridCells.Adaptive(if (kind == MediaLibraryKind.BOOKS) 128.dp else 144.dp),
                        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (kind == MediaLibraryKind.BOOKS && group == null && section == LibrarySection.BOOKS && query.isBlank() && recent != null) item(span = { GridItemSpan(maxLineSpan) }) {
                            Card(onClick = { selectedBook = recent }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    LibraryArtwork(session, recent!!, Modifier.size(72.dp, 100.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("继续阅读", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        Text(recent!!.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                                        Text("回到上次停下的地方", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Icon(Icons.Default.ArrowForward, null)
                                }
                            }
                        }
                        gridItems(items, key = { it.id ?: it.hashCode().toString() }) { item ->
                            Column(Modifier.combinedClickable(onClick = { open(item) }, onLongClickLabel = "更多操作", onLongClick = { actionItem = item })) {
                                Box {
                                    LibraryArtwork(session, item, Modifier.fillMaxWidth().aspectRatio(if (item.isBookItem() || item.type == "AudioBook") .7f else 1f))
                                    FilledTonalIconButton(onClick = { actionItem = item }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(48.dp)) {
                                        Icon(Icons.Default.MoreHoriz, "${item.name}的更多操作", Modifier.size(20.dp))
                                    }
                                    if (item.userData?.isFavorite == true) Icon(Icons.Default.Favorite, "已收藏", Modifier.align(Alignment.BottomStart).padding(8.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(4.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(8.dp)); Text(item.name ?: "未命名", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(item.librarySubtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (canLoadMore) item(span = { GridItemSpan(maxLineSpan) }) { LoadMore(loadingMore) { loadMore() } }
                    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        itemsIndexed(items, key = { index, item -> "${item.id}:$index" }) { _, item ->
                            ListItem(headlineContent = { Text(item.name ?: "未命名", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(item.librarySubtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { LibraryArtwork(session, item, Modifier.size(52.dp)) },
                                trailingContent = { if (item.isAudioItem() || item.isBookItem()) IconButton(onClick = { actionItem = item }) { Icon(Icons.Default.MoreVert, "${item.name}的更多操作") } else Icon(Icons.Default.ChevronRight, null) },
                                colors = ListItemDefaults.colors(containerColor = if (item.id == playback.item?.id && item.isAudioItem()) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                                modifier = Modifier.combinedClickable(onClick = { open(item) }, onLongClickLabel = "更多操作", onLongClick = { actionItem = item }))
                        }
                        if (canLoadMore) item { LoadMore(loadingMore) { loadMore() } }
                    }
                }
            }
            }
        }
    }
    actionItem?.takeIf { playlistName == null }?.let { item ->
        ModalBottomSheet(onDismissRequest = { actionItem = null }) {
            ListItem(headlineContent = { Text(item.name.orEmpty(), style = MaterialTheme.typography.titleLarge) },
                supportingContent = { Text(item.librarySubtitle()) }, leadingContent = { LibraryArtwork(session, item, Modifier.size(56.dp)) })
            ListItem(headlineContent = { Text(if (item.isBookItem()) "查看书籍 / 继续阅读" else if (item.isAudioItem()) "立即播放" else "打开") },
                leadingContent = { Icon(if (item.isBookItem()) Icons.Default.MenuBook else Icons.Default.PlayArrow, null) },
                modifier = Modifier.clickable { actionItem = null; open(item) })
            ListItem(headlineContent = { Text(if (item.userData?.isFavorite == true) "取消收藏" else "添加收藏") },
                leadingContent = { Icon(Icons.Default.FavoriteBorder, null) }, modifier = Modifier.clickable { scope.launch {
                    try { session.favorite(requireNotNull(item.id), item.userData?.isFavorite != true); actionItem = null; reload++ }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "收藏失败"; actionItem = null }
                } })
            if (item.isAudioItem()) ListItem(headlineContent = { Text("新建歌单并添加") },
                leadingContent = { Icon(Icons.Default.PlaylistAdd, null) }, modifier = Modifier.clickable { playlistName = "" })
            Spacer(Modifier.height(24.dp))
        }
    }
    playlistName?.let { name -> AlertDialog(onDismissRequest = { playlistName = null }, title = { Text("新建歌单") }, text = {
        OutlinedTextField(name, { playlistName = it }, singleLine = true, label = { Text("歌单名称") })
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { scope.launch {
        try { session.createPlaylist(name, listOfNotNull(actionItem?.id)); playlistName = null; actionItem = null; reload++ }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "创建歌单失败"; playlistName = null; actionItem = null }
    } }) { Text("创建") } }, dismissButton = { TextButton(onClick = { playlistName = null }) { Text("取消") } }) }
}

@Composable
internal fun LibraryArtwork(session: LibraryMediaSession?, item: BaseItemDto, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(if (item.isBookItem()) Icons.Default.MenuBook else Icons.Default.MusicNote, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(model = session?.artworkUrl(item), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

internal fun BaseItemDto.librarySubtitle(): String = when {
    isAudioItem() -> listOfNotNull(artists?.joinToString(" / ")?.takeIf { it.isNotBlank() } ?: albumArtist, runTimeTicks?.let { musicTime(it / 10_000) }).joinToString(" · ")
    isBookItem() -> listOfNotNull(people?.filter { it.type in listOf("Author", "Writer") }?.mapNotNull { it.name }?.joinToString(" / ")?.takeIf { it.isNotBlank() }, bookFormat().uppercase().takeIf { it.isNotBlank() }).joinToString(" · ")
    else -> albumArtist ?: childCount?.let { "$it 项" } ?: "查看内容"
}

@Composable private fun LoadMore(loading: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { if (loading) CircularProgressIndicator(Modifier.size(24.dp)) else TextButton(onClick = onClick) { Text("加载更多") } }
}
@Composable internal fun LibraryError(message: String, retry: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) { Text(message, color = MaterialTheme.colorScheme.onErrorContainer); TextButton(onClick = retry) { Text("重试") } }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LibraryLoadingScaffold(title: String, back: () -> Unit, error: String?, retry: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { if (error != null) LibraryError(error, retry) else CircularProgressIndicator() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BookDetailScreen(session: LibraryMediaSession, initial: BaseItemDto, onBack: () -> Unit, onRead: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var book by remember(initial.id) { mutableStateOf(initial) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailReload by remember { mutableIntStateOf(0) }
    LaunchedEffect(initial.id, detailReload) {
        try { book = session.item(requireNotNull(initial.id)); error = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "书籍详情加载失败" }
    }
    BackHandler { if (reading) reading = false else onBack() }
    if (reading) { BookReadingRoute(session, book, { reading = false }, onRead); return }
    Scaffold(topBar = { TopAppBar(title = { Text("书籍详情") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回书库") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge)
                    .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.surfaceContainerLow)))
                    .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LibraryArtwork(session, book, Modifier.width(150.dp).aspectRatio(.7f))
                    Text(book.name.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(book.librarySubtitle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    book.productionYear?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { reading = true }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Icon(Icons.Default.MenuBook, null); Spacer(Modifier.width(6.dp)); Text("阅读 / 继续阅读") }
                FilledTonalIconButton(onClick = { scope.launch {
                    try {
                        val favorite = book.userData?.isFavorite != true
                        session.favorite(requireNotNull(book.id), favorite)
                        book = book.copy(userData = (book.userData ?: UserItemDataDto()).copy(isFavorite = favorite))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "收藏失败" }
                } }) { Icon(if (book.userData?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏") }
            } }
            error?.let { item { LibraryError(it) { detailReload++ } } }
            if (!book.overview.isNullOrBlank()) item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("简介", style = MaterialTheme.typography.titleMedium)
                Text(android.text.Html.fromHtml(book.overview, android.text.Html.FROM_HTML_MODE_LEGACY).toString(), style = MaterialTheme.typography.bodyLarge)
            } }
            item { Text("支持 EPUB、PDF、CBZ 和 TXT。阅读位置自动保存在本机，重新打开即可续读。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun BookReadingRoute(session: LibraryMediaSession, item: BaseItemDto, back: () -> Unit, onRead: () -> Unit) {
    val context = LocalContext.current
    var comic by remember(session, item.id) { mutableStateOf<RemoteComicArchive?>(null) }
    var fallback by remember(session, item.id) { mutableStateOf(false) }
    var file by remember(session, item.id) { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var received by remember { mutableLongStateOf(0) }
    var length by remember { mutableStateOf<Long?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(session, item.id, retry) {
        error = null
        received = 0
        length = null
        fallback = false
        try {
            file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { session.cachedBook(item, context.cacheDir) }
            if (file == null && item.bookFormat() in setOf("cbz", "zip")) {
                try { comic = RemoteComicArchive.open(session.comicSource(item)::read) }
                catch (_: BookRangeUnsupportedException) { fallback = true }
            }
            if (comic == null && file == null) file = session.cacheBook(item, context.cacheDir) { read, size -> received = read; length = size }
            onRead()
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法打开书籍" }
    }
    val cached = file
    if (cached != null || comic != null) BookReaderScreen(cached, item.bookFormat(), item.name.orEmpty(), libraryCacheKey(session.accountKey + "|" + item.id), back, comic)
    else {
        LibraryLoadingScaffold("正在打开书籍", back, error, { retry++ })
        if (error == null) Box(Modifier.fillMaxSize().padding(top = 100.dp), contentAlignment = Alignment.Center) {
            Text((if (fallback) "服务器不支持分段读取，正在下载完整书籍\n" else if (item.bookFormat() in setOf("cbz", "zip")) "正在读取漫画目录\n" else "") + "${received / 1024} KB" + (length?.let { " / ${it / 1024} KB" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
