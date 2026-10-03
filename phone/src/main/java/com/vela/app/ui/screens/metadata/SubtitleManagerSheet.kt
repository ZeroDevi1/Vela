package com.vela.app.ui.screens.metadata

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vela.data.model.BaseItemDto
import com.vela.data.model.CultureInfo
import com.vela.data.model.MediaStream
import com.vela.data.model.RemoteSubtitleInfo
import com.vela.data.repository.MediaRepository
import com.vela.shared.R
import com.vela.shared.playback.UserDataRefreshSignals
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 修改字幕：列出当前字幕（外挂字幕可删除），按语言向服务端的字幕提供方（如 OpenSubtitles）搜索并下载。
 * 搜索与下载走 `Items/{Id}/RemoteSearch/Subtitles`，Emby 与 Jellyfin 通用；下载后重新读取条目刷新列表。
 */
@Composable
fun SubtitleManagerSheet(
    item: BaseItemDto,
    mediaRepository: MediaRepository,
    onDismiss: () -> Unit
) {
    val itemId = item.id?.takeIf { it.isNotBlank() } ?: return
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var reloadKey by remember { mutableIntStateOf(0) }
    var mediaSourceId by remember { mutableStateOf(item.mediaSources?.firstOrNull()?.id) }
    var currentSubtitles by remember {
        mutableStateOf(item.mediaSources?.firstOrNull()?.mediaStreams.orEmpty().subtitleStreams())
    }
    var cultures by remember { mutableStateOf<List<CultureInfo>>(emptyList()) }
    var language by remember { mutableStateOf<CultureInfo?>(null) }
    var choosingLanguage by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(SheetContentState.Idle) }
    var results by remember { mutableStateOf<List<RemoteSubtitleInfo>>(emptyList()) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var pendingDownload by remember { mutableStateOf<RemoteSubtitleInfo?>(null) }
    var pendingDelete by remember { mutableStateOf<MediaStream?>(null) }
    var busy by remember { mutableStateOf(false) }
    val updatedMessage = stringResource(R.string.metadata_subtitles_updated)
    val failedTemplate = stringResource(R.string.metadata_error_generic, "%s")

    LaunchedEffect(Unit) {
        mediaRepository.getCultures().onSuccess { list ->
            val usable = list.filter { !it.threeLetterIsoLanguageName.isNullOrBlank() }
                .sortedBy { it.displayName.orEmpty() }
            cultures = usable
            val deviceLanguage = Locale.getDefault().language
            language = usable.firstOrNull { it.twoLetterIsoLanguageName.equals(deviceLanguage, true) }
                ?: usable.firstOrNull { it.twoLetterIsoLanguageName.equals("en", true) }
                ?: usable.firstOrNull()
        }
    }

    LaunchedEffect(reloadKey) {
        if (reloadKey == 0) return@LaunchedEffect
        mediaRepository.getItemById(itemId).onSuccess { fresh ->
            val source = fresh.mediaSources?.firstOrNull { it.id == mediaSourceId } ?: fresh.mediaSources?.firstOrNull()
            mediaSourceId = source?.id
            currentSubtitles = source?.mediaStreams.orEmpty().subtitleStreams()
        }
    }

    fun onMutated(result: Result<Unit>) {
        scope.launch {
            result.onSuccess {
                reloadKey++
                UserDataRefreshSignals.notifyUserDataChanged(itemId)
                snackbar.showSnackbar(updatedMessage)
            }.onFailure {
                snackbar.showSnackbar(failedTemplate.replace("%s", it.message.orEmpty()))
            }
        }
    }

    fun search() {
        val code = language?.threeLetterIsoLanguageName ?: return
        state = SheetContentState.Loading
        scope.launch {
            mediaRepository.searchRemoteSubtitles(itemId, code, mediaSourceId)
                .onSuccess {
                    results = it
                    state = if (it.isEmpty()) SheetContentState.Empty else SheetContentState.Content
                }
                .onFailure {
                    error = it
                    state = SheetContentState.Error
                }
        }
    }

    MetadataSheetScaffold(
        title = stringResource(R.string.item_action_edit_subtitles),
        onDismiss = onDismiss,
        actions = {
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
            }
        }
    ) {
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "current") {
                    SectionTitle(
                        stringResource(R.string.metadata_subtitles_current),
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }
                if (currentSubtitles.isEmpty()) {
                    item(key = "current:empty") { StatusText(stringResource(R.string.metadata_subtitles_none)) }
                }
                items(currentSubtitles, key = { "stream:${it.index}" }) { stream ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stream.displayTitle ?: stream.title ?: stream.language.orEmpty(),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    stream.language?.takeIf { it.isNotBlank() },
                                    stream.codec?.uppercase(Locale.ROOT),
                                    stringResource(
                                        if (stream.isExternal == true) R.string.metadata_subtitles_external else R.string.metadata_subtitles_embedded
                                    )
                                ).joinToString(" · ")
                            )
                        },
                        trailingContent = if (stream.isExternal == true && stream.index != null) {
                            {
                                IconButton(onClick = { pendingDelete = stream }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                                }
                            }
                        } else {
                            null
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                    )
                }
                item(key = "search") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        SectionTitle(
                            stringResource(R.string.metadata_subtitles_search),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { choosingLanguage = true },
                                enabled = cultures.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = language?.displayName ?: stringResource(R.string.metadata_subtitles_language),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = ::search,
                                enabled = language != null && state != SheetContentState.Loading
                            ) {
                                Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.metadata_search))
                            }
                        }
                    }
                }
                item(key = "state") {
                    when (state) {
                        SheetContentState.Loading -> CenteredBox { CircularProgressIndicator() }
                        SheetContentState.Error -> CenteredBox { StatusText(errorText(error ?: return@CenteredBox)) }
                        SheetContentState.Empty -> CenteredBox { StatusText(stringResource(R.string.metadata_no_results)) }
                        else -> Unit
                    }
                }
                if (state == SheetContentState.Content) {
                    itemsIndexed(results, key = { index, result -> "remote:${result.id}:$index" }) { _, result ->
                        RemoteSubtitleRow(result = result, onClick = { pendingDownload = result })
                    }
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    if (choosingLanguage) {
        AlertDialog(
            onDismissRequest = { choosingLanguage = false },
            title = { Text(stringResource(R.string.metadata_subtitles_language)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(cultures, key = { it.threeLetterIsoLanguageName.orEmpty() + it.name.orEmpty() }) { culture ->
                        Text(
                            text = culture.displayName ?: culture.name.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (culture == language) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickableRow {
                                    language = culture
                                    choosingLanguage = false
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosingLanguage = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    pendingDownload?.let { subtitle ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text(stringResource(R.string.metadata_subtitles_download_title)) },
            text = { Text(subtitle.name.orEmpty()) },
            confirmButton = {
                Button(onClick = {
                    val id = subtitle.id ?: return@Button
                    pendingDownload = null
                    busy = true
                    scope.launch {
                        val result = mediaRepository.downloadRemoteSubtitle(itemId, id, mediaSourceId)
                        busy = false
                        onMutated(result)
                    }
                }) { Text(stringResource(R.string.metadata_subtitles_download)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownload = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    pendingDelete?.let { stream ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.metadata_subtitles_delete_title)) },
            text = { Text(stream.displayTitle ?: stream.title.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    val index = stream.index ?: return@TextButton
                    pendingDelete = null
                    busy = true
                    scope.launch {
                        val result = mediaRepository.deleteSubtitle(itemId, mediaSourceId, index)
                        busy = false
                        onMutated(result)
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private fun List<MediaStream>.subtitleStreams(): List<MediaStream> =
    filter { it.type.equals("Subtitle", ignoreCase = true) }

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.then(Modifier.clip(RoundedCornerShape(8.dp))).then(Modifier.clickable(onClick = onClick))

@Composable
private fun RemoteSubtitleRow(result: RemoteSubtitleInfo, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.name.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    result.providerName?.takeIf { it.isNotBlank() }?.let { ProviderBadge(it) }
                    if (result.isHashMatch == true) {
                        ProviderBadge(stringResource(R.string.metadata_subtitles_hash_match))
                    }
                    Text(
                        text = listOfNotNull(
                            result.format?.uppercase(Locale.ROOT),
                            result.downloadCount?.let { "↓ $it" },
                            result.communityRating?.takeIf { it > 0 }?.let { String.format(Locale.US, "★ %.1f", it) },
                            result.dateCreated?.take(10)
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = stringResource(R.string.metadata_subtitles_download),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
