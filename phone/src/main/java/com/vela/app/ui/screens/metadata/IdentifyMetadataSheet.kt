package com.vela.app.ui.screens.metadata

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vela.data.model.BaseItemDto
import com.vela.data.model.ExternalIdInfo
import com.vela.data.model.RemoteSearchInfo
import com.vela.data.model.RemoteSearchResult
import com.vela.data.model.remoteSearchTypeFor
import com.vela.data.repository.MediaRepository
import com.vela.shared.R
import com.vela.shared.playback.UserDataRefreshSignals
import kotlinx.coroutines.launch

/**
 * 识别目标。季、集没有独立的识别接口，改为识别所属剧集；[viaSeries] 用于在界面上说明这一点。
 */
private data class IdentifyTarget(
    val itemId: String,
    val searchType: String,
    val seriesName: String?,
    val path: String?,
    val viaSeries: Boolean
)

private fun identifyTargetFor(item: BaseItemDto): IdentifyTarget? {
    val searchType = remoteSearchTypeFor(item.type) ?: return null
    val isChild = item.type.equals("Episode", true) || item.type.equals("Season", true)
    return if (isChild) {
        IdentifyTarget(
            itemId = item.seriesId?.takeIf { it.isNotBlank() } ?: return null,
            searchType = searchType,
            seriesName = item.seriesName,
            path = null,
            viaSeries = true
        )
    } else {
        IdentifyTarget(
            itemId = item.id?.takeIf { it.isNotBlank() } ?: return null,
            searchType = searchType,
            seriesName = null,
            path = item.path?.takeIf { it.isNotBlank() }
                ?: item.mediaSources?.firstOrNull()?.path?.takeIf { it.isNotBlank() },
            viaSeries = false
        )
    }
}

/**
 * 识别（刮削元数据），对应 Emby/Jellyfin 网页端的“识别”对话框：
 * - 需要识别时通常是服务端识别错了，所以表单一律从空白开始，不预填当前名称和 ID；
 * - 外部 ID 输入框来自 `ExternalIdInfos`，插件提供方（如 MetaTube）也能按 ID 搜索；
 * - 选中候选后调用 RemoteSearch/Apply，可选择同时替换图片。
 *
 * 应用成功后广播刷新信号，详情页会重新拉取条目。
 */
@Composable
fun IdentifyMetadataSheet(
    item: BaseItemDto,
    mediaRepository: MediaRepository,
    onDismiss: () -> Unit
) {
    val target = remember(item.id) { identifyTargetFor(item) } ?: return
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    val externalIds = remember { mutableStateListOf<ExternalIdInfo>() }
    val providerInputs = remember { mutableStateMapOf<String, String>() }
    var state by remember { mutableStateOf(SheetContentState.Idle) }
    var results by remember { mutableStateOf<List<RemoteSearchResult>>(emptyList()) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var pending by remember { mutableStateOf<RemoteSearchResult?>(null) }
    var replaceImages by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(target.itemId) {
        mediaRepository.getExternalIdInfos(target.itemId).onSuccess { infos ->
            externalIds.clear()
            // ProviderIds 以 Key 为键，同一 Key 只保留一个输入框；Emby 明确不支持识别的 ID 不展示。
            externalIds.addAll(
                infos.filter { !it.key.isNullOrBlank() && it.isSupportedAsIdentifier != false }
                    .distinctBy { it.key!!.lowercase() }
            )
        }
    }

    fun search() {
        // 点搜索（按钮或输入法的搜索键）后收起输入法，结果列表不被键盘挡住。
        keyboardController?.hide()
        focusManager.clearFocus()
        if (state == SheetContentState.Loading) return
        val providerIds = providerInputs
            .mapValues { it.value.trim() }
            .filterValues { it.isNotEmpty() }
        val trimmedName = name.trim()
        val parsedYear = year.trim().toIntOrNull()
        if (trimmedName.isEmpty() && parsedYear == null && providerIds.isEmpty()) {
            state = SheetContentState.Idle
            return
        }
        state = SheetContentState.Loading
        scope.launch {
            mediaRepository.searchRemoteMetadata(
                itemId = target.itemId,
                searchType = target.searchType,
                searchInfo = RemoteSearchInfo(
                    name = trimmedName.takeIf { it.isNotEmpty() },
                    year = parsedYear,
                    providerIds = providerIds.takeIf { it.isNotEmpty() }
                )
            ).onSuccess {
                results = it
                state = if (it.isEmpty()) SheetContentState.Empty else SheetContentState.Content
            }.onFailure {
                error = it
                state = SheetContentState.Error
            }
        }
    }

    MetadataSheetScaffold(
        title = stringResource(R.string.item_action_identify),
        onDismiss = onDismiss
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "intro") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.metadata_identify_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (target.viaSeries) {
                        Text(
                            text = stringResource(R.string.metadata_identify_series_hint, target.seriesName.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    target.path?.let { path ->
                        Text(
                            text = stringResource(R.string.metadata_path),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item(key = "name") {
                SearchField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.metadata_field_name),
                    onSearch = ::search
                )
            }
            item(key = "year") {
                SearchField(
                    value = year,
                    onValueChange = { year = it.filter(Char::isDigit).take(4) },
                    label = stringResource(R.string.metadata_field_year),
                    keyboardType = KeyboardType.Number,
                    onSearch = ::search
                )
            }
            externalIds.forEach { info ->
                val key = info.key ?: return@forEach
                item(key = "id:$key") {
                    SearchField(
                        value = providerInputs[key].orEmpty(),
                        onValueChange = { providerInputs[key] = it },
                        label = externalIdLabel(info),
                        keyboardType = KeyboardType.Ascii,
                        onSearch = ::search
                    )
                }
            }
            item(key = "search") {
                Button(
                    onClick = ::search,
                    enabled = state != SheetContentState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.metadata_search))
                }
            }
            item(key = "state") {
                AnimatedContent(
                    targetState = state,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "identifyState"
                ) { current ->
                    when (current) {
                        SheetContentState.Idle, SheetContentState.Content -> Spacer(Modifier.height(4.dp))
                        SheetContentState.Loading -> CenteredBox { CircularProgressIndicator() }
                        SheetContentState.Error -> CenteredBox { StatusText(errorText(error ?: return@CenteredBox)) }
                        SheetContentState.Empty -> CenteredBox {
                            StatusText(stringResource(R.string.metadata_no_results))
                        }
                    }
                }
            }
            if (state == SheetContentState.Content) {
                itemsIndexed(
                    items = results,
                    key = { index, result -> "result:${result.searchProviderName}:$index" }
                ) { _, result ->
                    RemoteSearchResultRow(result = result, onClick = { pending = result })
                }
            }
        }
    }

    pending?.let { result ->
        AlertDialog(
            onDismissRequest = { if (!applying) pending = null },
            title = { Text(stringResource(R.string.metadata_apply_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.metadata_apply_message,
                            listOfNotNull(result.name, result.productionYear?.toString()).joinToString(" · ")
                        )
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !applying) { replaceImages = !replaceImages }
                    ) {
                        Checkbox(checked = replaceImages, onCheckedChange = null, enabled = !applying)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.metadata_replace_images))
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !applying,
                    onClick = {
                        applying = true
                        scope.launch {
                            mediaRepository.applyRemoteMetadata(
                                itemId = target.itemId,
                                result = result,
                                replaceAllImages = replaceImages
                            ).onSuccess {
                                UserDataRefreshSignals.notifyUserDataChanged(target.itemId)
                                if (item.id != null && item.id != target.itemId) {
                                    UserDataRefreshSignals.notifyUserDataChanged(item.id)
                                }
                                pending = null
                                onDismiss()
                            }.onFailure {
                                error = it
                                state = SheetContentState.Error
                                pending = null
                            }
                            applying = false
                        }
                    }
                ) {
                    if (applying) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Text(stringResource(R.string.metadata_apply))
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !applying, onClick = { pending = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/** 与网页端一致：Jellyfin 的 ID 带所属类型时显示为“名称 类型 Id”。 */
@Composable
private fun externalIdLabel(info: ExternalIdInfo): String {
    val base = info.name?.takeIf { it.isNotBlank() } ?: info.key.orEmpty()
    val typed = info.type?.takeIf { it.isNotBlank() }?.let { "$base $it" } ?: base
    return stringResource(R.string.metadata_external_id_label, typed)
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onSearch: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RemoteSearchResultRow(result: RemoteSearchResult, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                if (!result.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = result.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listOfNotNull(result.name, result.productionYear?.let { "($it)" }).joinToString(" "),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val ids = result.providerIds.orEmpty().entries
                    .filter { it.value.isNotBlank() }
                    .joinToString("  ") { "${it.key} ${it.value}" }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    result.searchProviderName?.takeIf { it.isNotBlank() }?.let { ProviderBadge(it) }
                    if (ids.isNotEmpty()) {
                        Text(
                            text = ids,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                result.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}
