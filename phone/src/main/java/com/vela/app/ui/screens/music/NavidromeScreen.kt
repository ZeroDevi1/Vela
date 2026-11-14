package com.vela.app.ui.screens.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.vela.app.ui.screens.library.LibraryArtwork
import com.vela.app.ui.screens.library.LibraryBrowser
import com.vela.app.ui.screens.library.LibraryError
import com.vela.app.ui.screens.library.MediaLibraryKind
import com.vela.app.ui.screens.library.MusicLibraryTab
import com.vela.app.ui.screens.library.librarySubtitle
import com.vela.data.model.BaseItemDto
import com.vela.data.model.MediaLibraryQuery
import com.vela.data.navidrome.NavidromeSession
import com.vela.data.navidrome.NavidromeStore
import com.vela.shared.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

private enum class MusicTab(val label: Int, val icon: ImageVector, val libraryTab: MusicLibraryTab? = null) {
    HOME(R.string.navidrome_home, Icons.Rounded.Home),
    LIBRARY(R.string.navidrome_library, Icons.Rounded.Album, MusicLibraryTab.LIBRARY),
    MINE(R.string.navidrome_mine, Icons.Rounded.FavoriteBorder, MusicLibraryTab.MINE),
    SEARCH(R.string.search, Icons.Rounded.Search, MusicLibraryTab.SEARCH),
    SETTINGS(R.string.settings, Icons.Rounded.Settings)
}

internal class NavidromeHomeViewModel : ViewModel() {
    var albums by mutableStateOf<List<BaseItemDto>?>(null)
    var tracks by mutableStateOf<List<BaseItemDto>?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
internal fun NavidromeScreen(
    accountKey: String,
    onOpenPlayer: () -> Unit,
    onSwitchServer: () -> Unit,
    onAbout: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { NavidromeStore.getInstance(context) }
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val session = sessions.firstOrNull { it.accountKey == accountKey }
    if (session == null) {
        LaunchedEffect(accountKey) { onSwitchServer() }
        return
    }

    var tab by rememberSaveable(accountKey) { mutableStateOf(MusicTab.HOME) }
    var albumToOpen by remember { mutableStateOf<BaseItemDto?>(null) }
    val tabStates = rememberSaveableStateHolder()
    val playback by MusicPlayback.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (tab == MusicTab.HOME) R.string.music else tab.label),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    TextButton(onClick = onSwitchServer) {
                        Icon(Icons.Rounded.SwapHoriz, stringResource(R.string.settings_switch_server))
                        Spacer(Modifier.width(6.dp))
                        Text(session.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 128.dp))
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (playback.item != null && playback.session?.accountKey == accountKey) {
                        MusicMiniPlayer(
                            onOpen = onOpenPlayer,
                            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                .widthIn(max = 600.dp)
                        )
                    }
                    ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        MusicTab.entries.forEach { page ->
                            ShortNavigationBarItem(
                                selected = tab == page,
                                onClick = { tab = page },
                                icon = { Icon(page.icon, null) },
                                label = { Text(stringResource(page.label)) }
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        tabStates.SaveableStateProvider(tab.name) {
            when (tab) {
                MusicTab.HOME -> NavidromeHome(
                    session, content, onOpenPlayer, onBrowse = { tab = MusicTab.LIBRARY },
                    onAlbum = { album ->
                        albumToOpen = album
                        tab = MusicTab.LIBRARY
                    }
                )
                MusicTab.SETTINGS -> NavidromeSettings(session, store, content, onSwitchServer, onAbout)
                else -> LibraryBrowser(
                    session, MediaLibraryKind.MUSIC, libraryId = null, initialItemId = null,
                    onOpenPlayer = onOpenPlayer, onBack = onSwitchServer, musicTab = tab.libraryTab,
                    modifier = content,
                    openAlbum = albumToOpen.takeIf { tab == MusicTab.LIBRARY },
                    onAlbumOpened = { albumToOpen = null }
                )
            }
        }
    }
}

@UnstableApi
@Composable
private fun NavidromeHome(
    session: NavidromeSession,
    modifier: Modifier,
    onOpenPlayer: () -> Unit,
    onBrowse: () -> Unit,
    onAlbum: (BaseItemDto) -> Unit,
    discovery: NavidromeHomeViewModel = viewModel(key = session.accountKey)
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val albums = discovery.albums.orEmpty()
    val tracks = discovery.tracks.orEmpty()
    var loading by remember(session) { mutableStateOf(discovery.albums == null || discovery.tracks == null) }
    var reload by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val loadFailed = stringResource(R.string.connection_failed)

    LaunchedEffect(session, reload, discovery) {
        if (discovery.albums != null && discovery.tracks != null) return@LaunchedEffect
        loading = true
        error = null
        supervisorScope {
            val newest = async {
                discovery.albums ?: session.items(MediaLibraryQuery(includeItemTypes = "MusicAlbum", limit = 10, sortOrder = "Descending")).items.orEmpty()
            }
            val random = async { discovery.tracks ?: session.randomTracks(10) }
            try { discovery.albums = newest.await() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: loadFailed }
            try { discovery.tracks = random.await() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: loadFailed }
        }
        loading = false
    }

    fun play(items: List<BaseItemDto>, index: Int = 0) {
        if (items.isEmpty() || opening) return
        opening = true
        scope.launch {
            try {
                MusicPlayback.play(context, session, items, index)
                onOpenPlayer()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: loadFailed }
            finally { opening = false }
        }
    }

    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.navidrome_discover), style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = { play(tracks.shuffled()) }, enabled = tracks.isNotEmpty() && !opening) {
                        Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.navidrome_shuffle))
                    }
                }
            }
        }
        if (loading) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        error?.let { message -> item { LibraryError(message) { reload++ } } }
        if (albums.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.navidrome_recent_albums), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onBrowse) { Text(stringResource(R.string.navidrome_library)) }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(albums) { album ->
                        Column(
                            Modifier.width(132.dp).clickable { onAlbum(album) },
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LibraryArtwork(session, album, Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(album.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            album.albumArtist?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
        if (tracks.isNotEmpty()) {
            item {
                Text(stringResource(R.string.navidrome_discover_tracks), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
            }
            itemsIndexed(tracks) { index, item ->
                MusicTrackRow(session, item, onClick = { play(tracks, index) })
            }
        }
    }
}

@Composable
internal fun MusicTrackRow(session: NavidromeSession, item: BaseItemDto, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(item.librarySubtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { LibraryArtwork(session, item, Modifier.size(48.dp)) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun NavidromeSettings(
    session: NavidromeSession,
    store: NavidromeStore,
    modifier: Modifier,
    onSwitchServer: () -> Unit,
    onAbout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var disconnect by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            ListItem(
                headlineContent = { Text(session.displayName) },
                supportingContent = { Text("${session.username} · ${session.baseUrl}") },
                leadingContent = { Icon(Icons.Rounded.Album, null) }
            )
        }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.settings_switch_server)) },
                leadingContent = { Icon(Icons.Rounded.SwapHoriz, null) }, modifier = Modifier.clickable(onClick = onSwitchServer))
        }
        item {
            ListItem(headlineContent = { Text(stringResource(R.string.about_title)) },
                leadingContent = { Icon(Icons.Rounded.Info, null) }, modifier = Modifier.clickable(onClick = onAbout))
        }
        item {
            TextButton(onClick = { error = false; disconnect = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.settings_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (disconnect) AlertDialog(
        onDismissRequest = { if (!busy) disconnect = false },
        title = { Text(stringResource(R.string.navidrome_manage)) },
        text = { Text(if (error) stringResource(R.string.navidrome_disconnect_failed) else session.displayName) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { store.disconnect(session.accountKey) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.settings_remove)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { disconnect = false }) { Text(stringResource(R.string.cancel)) } }
    )
}
