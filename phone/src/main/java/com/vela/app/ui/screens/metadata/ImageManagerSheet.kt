package com.vela.app.ui.screens.metadata

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vela.data.model.BaseItemDto
import com.vela.data.model.ItemImageInfo
import com.vela.data.model.RemoteImageInfo
import com.vela.data.model.RemoteImageResult
import com.vela.data.model.RemoteImageType
import com.vela.data.repository.MediaRepository
import com.vela.shared.R
import com.vela.shared.playback.UserDataRefreshSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 单张图片超过该大小（字节）不上传，避免 Base64 后请求体过大。 */
private const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024

/** “图片”分区可选的类型；背景图单独成区（可有多张）。 */
private val SingleImageTypes = listOf(
    RemoteImageType.Primary,
    RemoteImageType.Thumb,
    RemoteImageType.Logo,
    RemoteImageType.Banner,
    RemoteImageType.Art,
    RemoteImageType.Disc
)

/** 面板内的二级页：远程搜索某一组类型的图片。 */
private data class RemoteBrowse(val types: List<RemoteImageType>, val initial: RemoteImageType)

/**
 * 修改图片，对应 Emby/Jellyfin 网页端的“修改图片”页：
 * - 按“图片”“背景图”分区列出条目当前的图片（尺寸、类型），每张可远程搜索替换或删除；
 * - 分区标题旁可远程搜索（各提供方，按类型/提供方/语言筛选）或上传本地图片。
 *
 * 所有改动成功后重新读取图片列表，并广播刷新信号让详情页更新。
 */
@Composable
fun ImageManagerSheet(
    item: BaseItemDto,
    mediaRepository: MediaRepository,
    onDismiss: () -> Unit
) {
    val itemId = item.id?.takeIf { it.isNotBlank() } ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var browse by remember { mutableStateOf<RemoteBrowse?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var images by remember { mutableStateOf<List<ItemImageInfo>?>(null) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ItemImageInfo?>(null) }
    var choosingUploadType by remember { mutableStateOf(false) }
    var uploadType by remember { mutableStateOf(RemoteImageType.Primary) }
    val updatedMessage = stringResource(R.string.metadata_images_updated)
    val tooLargeMessage = stringResource(R.string.metadata_images_too_large)
    val failedTemplate = stringResource(R.string.metadata_error_generic, "%s")

    fun onMutated(result: Result<Unit>) {
        scope.launch {
            result.onSuccess {
                UserDataRefreshSignals.notifyUserDataChanged(itemId)
                reloadKey++
                snackbar.showSnackbar(updatedMessage)
            }.onFailure {
                snackbar.showSnackbar(failedTemplate.replace("%s", it.message.orEmpty()))
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val type = uploadType
        busy = true
        scope.launch {
            val payload = withContext(Dispatchers.IO) { readImage(context, uri) }
            if (payload == null) {
                busy = false
                snackbar.showSnackbar(tooLargeMessage)
                return@launch
            }
            val result = mediaRepository.uploadItemImage(itemId, type.apiValue, payload.first, payload.second)
            busy = false
            onMutated(result)
        }
    }

    fun startUpload(type: RemoteImageType) {
        uploadType = type
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(reloadKey) {
        mediaRepository.getItemImages(itemId)
            .onSuccess {
                images = it
                loadError = null
            }
            .onFailure { loadError = it }
    }

    val currentBrowse = browse
    MetadataSheetScaffold(
        title = stringResource(
            if (currentBrowse == null) R.string.item_action_edit_images else R.string.metadata_images_search_title
        ),
        onDismiss = onDismiss,
        onBack = currentBrowse?.let { { browse = null } },
        actions = {
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
            }
        }
    ) {
        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = currentBrowse,
                transitionSpec = {
                    val forward = targetState != null
                    (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn())
                        .togetherWith(slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                },
                label = "imageManagerPage"
            ) { page ->
                if (page == null) {
                    CurrentImagesPage(
                        itemId = itemId,
                        images = images,
                        loadError = loadError,
                        mediaRepository = mediaRepository,
                        onSearch = { types, initial -> browse = RemoteBrowse(types, initial) },
                        onUploadSingle = { choosingUploadType = true },
                        onUploadBackdrop = { startUpload(RemoteImageType.Backdrop) },
                        onDelete = { pendingDelete = it }
                    )
                } else {
                    RemoteImagesPage(
                        itemId = itemId,
                        browse = page,
                        mediaRepository = mediaRepository,
                        onApplied = { result ->
                            if (result.isSuccess) browse = null
                            onMutated(result)
                        }
                    )
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    pendingDelete?.let { image ->
        val type = remoteImageTypeOf(image.imageType)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.metadata_images_delete_title)) },
            text = { Text(stringResource(R.string.metadata_images_delete_message, type?.label() ?: image.imageType.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    busy = true
                    scope.launch {
                        val result = mediaRepository.deleteItemImage(
                            itemId = itemId,
                            imageType = image.imageType.orEmpty(),
                            imageIndex = image.imageIndex
                        )
                        busy = false
                        onMutated(result)
                    }
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (choosingUploadType) {
        var selected by remember { mutableStateOf(RemoteImageType.Primary) }
        AlertDialog(
            onDismissRequest = { choosingUploadType = false },
            title = { Text(stringResource(R.string.metadata_images_upload_type)) },
            text = {
                Column {
                    SingleImageTypes.forEach { type ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .selectable(
                                    selected = selected == type,
                                    role = Role.RadioButton,
                                    onClick = { selected = type }
                                )
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(selected = selected == type, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(type.label())
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    choosingUploadType = false
                    startUpload(selected)
                }) { Text(stringResource(R.string.metadata_images_choose_file)) }
            },
            dismissButton = {
                TextButton(onClick = { choosingUploadType = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/** 读取本地图片；超过 [MAX_UPLOAD_BYTES] 或读取失败时返回 null。返回（字节, MIME）。 */
private fun readImage(context: Context, uri: Uri): Pair<ByteArray, String>? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
    return runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.isEmpty() || bytes.size > MAX_UPLOAD_BYTES) null else bytes to mime
        }
    }.getOrNull()
}

private fun remoteImageTypeOf(apiValue: String?): RemoteImageType? =
    RemoteImageType.entries.firstOrNull { it.apiValue.equals(apiValue, ignoreCase = true) }

@Composable
private fun RemoteImageType.label(): String = stringResource(
    when (this) {
        RemoteImageType.Primary -> R.string.metadata_image_primary
        RemoteImageType.Backdrop -> R.string.metadata_image_backdrop
        RemoteImageType.Logo -> R.string.metadata_image_logo
        RemoteImageType.Thumb -> R.string.metadata_image_thumb
        RemoteImageType.Banner -> R.string.metadata_image_banner
        RemoteImageType.Art -> R.string.metadata_image_art
        RemoteImageType.Disc -> R.string.metadata_image_disc
    }
)

/** 远程图片网格的宽高比。 */
private val RemoteImageType.gridAspect: Float
    get() = when (this) {
        RemoteImageType.Primary -> 2f / 3f
        RemoteImageType.Backdrop, RemoteImageType.Thumb, RemoteImageType.Art -> 16f / 9f
        RemoteImageType.Logo -> 16f / 7f
        RemoteImageType.Banner -> 5.4f
        RemoteImageType.Disc -> 1f
    }

private fun RemoteImageType.minCellWidth(landscape: Boolean): Dp = when (this) {
    RemoteImageType.Primary, RemoteImageType.Disc -> if (landscape) 130.dp else 104.dp
    RemoteImageType.Banner -> 300.dp
    else -> if (landscape) 240.dp else 168.dp
}

private fun dimensionText(width: Int?, height: Int?): String? =
    if ((width ?: 0) > 0 && (height ?: 0) > 0) "$width × $height" else null

@Composable
private fun CurrentImagesPage(
    itemId: String,
    images: List<ItemImageInfo>?,
    loadError: Throwable?,
    mediaRepository: MediaRepository,
    onSearch: (List<RemoteImageType>, RemoteImageType) -> Unit,
    onUploadSingle: () -> Unit,
    onUploadBackdrop: () -> Unit,
    onDelete: (ItemImageInfo) -> Unit
) {
    when {
        images == null && loadError != null -> {
            CenteredBox { StatusText(errorText(loadError)) }
            return
        }
        images == null -> {
            CenteredBox { CircularProgressIndicator() }
            return
        }
    }
    val all = images.orEmpty()
    val backdrops = all.filter { it.imageType.equals(RemoteImageType.Backdrop.apiValue, true) }
        .sortedBy { it.imageIndex ?: 0 }
    val singles = all.filterNot { it.imageType.equals(RemoteImageType.Backdrop.apiValue, true) }
        .sortedBy { type -> SingleImageTypes.indexOfFirst { it.apiValue.equals(type.imageType, true) }.let { if (it < 0) 99 else it } }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (landscape) 180.dp else 150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "header:images", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                title = stringResource(R.string.metadata_images_section_images),
                onSearch = { onSearch(SingleImageTypes, RemoteImageType.Primary) },
                onUpload = onUploadSingle
            )
        }
        if (singles.isEmpty()) {
            item(key = "empty:images", span = { GridItemSpan(maxLineSpan) }) {
                StatusText(stringResource(R.string.metadata_images_none))
            }
        }
        items(singles, key = { "img:${it.imageType}:${it.imageIndex}:${it.imageTag}" }) { image ->
            val type = remoteImageTypeOf(image.imageType)
            CurrentImageCard(
                itemId = itemId,
                image = image,
                label = type?.label() ?: image.imageType.orEmpty(),
                mediaRepository = mediaRepository,
                onSearch = type?.let { { onSearch(SingleImageTypes, it) } },
                onDelete = { onDelete(image) }
            )
        }
        item(key = "header:backdrops", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                title = stringResource(R.string.metadata_image_backdrop),
                onSearch = { onSearch(listOf(RemoteImageType.Backdrop), RemoteImageType.Backdrop) },
                onUpload = onUploadBackdrop,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        if (backdrops.isEmpty()) {
            item(key = "empty:backdrops", span = { GridItemSpan(maxLineSpan) }) {
                StatusText(stringResource(R.string.metadata_images_none))
            }
        }
        items(backdrops, key = { "bd:${it.imageIndex}:${it.imageTag}" }) { image ->
            CurrentImageCard(
                itemId = itemId,
                image = image,
                label = stringResource(R.string.metadata_image_backdrop),
                mediaRepository = mediaRepository,
                onSearch = { onSearch(listOf(RemoteImageType.Backdrop), RemoteImageType.Backdrop) },
                onDelete = { onDelete(image) }
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    onSearch: () -> Unit,
    onUpload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(top = 8.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(4.dp))
        FilledTonalIconButton(onClick = onSearch) {
            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.metadata_images_search_title))
        }
        FilledTonalIconButton(onClick = onUpload) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.metadata_images_upload_type))
        }
    }
}

/**
 * 当前图片卡片。统一 3:4 方框内按比例完整显示，竖封面和横向背景图在网格里对齐。
 * 图片地址带 tag，替换后 tag 变化即自动换图。
 */
@Composable
private fun CurrentImageCard(
    itemId: String,
    image: ItemImageInfo,
    label: String,
    mediaRepository: MediaRepository,
    onSearch: (() -> Unit)?,
    onDelete: () -> Unit
) {
    val path = buildString {
        append(image.imageType.orEmpty())
        image.imageIndex?.let { append('/').append(it) }
    }
    val url by produceState<String?>(null, itemId, path, image.imageTag) {
        value = mediaRepository.getImageUrlString(
            itemId = itemId,
            imageType = path,
            width = 480,
            quality = 85,
            imageTag = image.imageTag
        )
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                url?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            dimensionText(image.width, image.height)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                if (onSearch != null) {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.metadata_images_search_title))
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        }
    }
}

/**
 * 远程图片搜索页：按类型、提供方、是否包含所有语言筛选，点选后由服务端下载替换。
 * 与网页端一致，默认只返回条目语言和无语言的图片，打开“所有语言”后返回全部。
 */
@Composable
private fun RemoteImagesPage(
    itemId: String,
    browse: RemoteBrowse,
    mediaRepository: MediaRepository,
    onApplied: (Result<Unit>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    var imageType by remember(browse) { mutableStateOf(browse.initial) }
    var provider by remember(browse) { mutableStateOf<String?>(null) }
    var allLanguages by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RemoteImageResult?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var loading by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<RemoteImageInfo?>(null) }
    var applying by remember { mutableStateOf(false) }

    LaunchedEffect(imageType, provider, allLanguages) {
        loading = true
        error = null
        mediaRepository.getRemoteImages(itemId, imageType, provider, allLanguages)
            .onSuccess { result = it }
            .onFailure {
                result = null
                error = it
            }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (browse.types.size > 1) {
            ChipRow {
                browse.types.forEach { type ->
                    FilterChip(
                        selected = imageType == type,
                        onClick = {
                            if (imageType != type) {
                                imageType = type
                                provider = null
                            }
                        },
                        label = { Text(type.label()) }
                    )
                }
            }
        }
        ChipRow(modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(
                selected = allLanguages,
                onClick = { allLanguages = !allLanguages },
                label = { Text(stringResource(R.string.metadata_images_all_languages)) }
            )
            FilterChip(
                selected = provider == null,
                onClick = { provider = null },
                label = { Text(stringResource(R.string.metadata_images_all_providers)) }
            )
            result?.providers.orEmpty().forEach { name ->
                FilterChip(
                    selected = provider == name,
                    onClick = { provider = name },
                    label = { Text(name) }
                )
            }
        }

        val images = result?.images.orEmpty().filter { !it.url.isNullOrBlank() }
        when {
            loading -> CenteredBox { CircularProgressIndicator() }
            error != null -> CenteredBox { StatusText(errorText(error!!)) }
            images.isEmpty() -> CenteredBox { StatusText(stringResource(R.string.metadata_images_empty)) }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(imageType.minCellWidth(landscape)),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    items = images,
                    key = { index, image -> "${image.url}#$index" },
                    contentType = { _, _ -> imageType }
                ) { _, image ->
                    RemoteImageCell(
                        image = image,
                        aspect = imageType.gridAspect,
                        fitContent = imageType == RemoteImageType.Logo,
                        onClick = { pending = image }
                    )
                }
            }
        }
    }

    pending?.let { image ->
        AlertDialog(
            onDismissRequest = { if (!applying) pending = null },
            title = { Text(stringResource(R.string.metadata_images_set_title, imageType.label())) },
            text = {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(imageType.gridAspect.coerceIn(0.6f, 2.4f))
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        AsyncImage(
                            model = image.url,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Text(
                        text = imageMetaLine(image),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !applying,
                    onClick = {
                        applying = true
                        val type = imageType
                        scope.launch {
                            val outcome = mediaRepository.downloadRemoteImage(itemId, type, image)
                            applying = false
                            pending = null
                            onApplied(outcome)
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

@Composable
private fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

private fun imageMetaLine(image: RemoteImageInfo): String = listOfNotNull(
    image.providerName?.takeIf { it.isNotBlank() },
    dimensionText(image.width, image.height),
    image.language?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT),
    image.communityRating?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "★ %.1f", it) }
).joinToString(" · ")

/**
 * 单张远程图片。优先加载提供方的缩略图地址，Coil 按格子尺寸解码，滚动时不会加载原图。
 * Logo 透明底用 Fit 完整显示，其余类型 Crop 铺满。
 */
@Composable
private fun RemoteImageCell(
    image: RemoteImageInfo,
    aspect: Float,
    fitContent: Boolean,
    onClick: () -> Unit
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
        ) {
            AsyncImage(
                model = image.thumbnailUrl?.takeIf { it.isNotBlank() } ?: image.url,
                contentDescription = null,
                contentScale = if (fitContent) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (fitContent) 8.dp else 0.dp)
            )
        }
        Text(
            text = imageMetaLine(image),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp)
        )
    }
}
