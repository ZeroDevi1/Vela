package com.vela.app.ui.screens.metadata

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vela.data.model.BaseItemDto
import com.vela.data.model.remoteSearchTypeFor
import com.vela.data.network.HttpStatusException
import com.vela.data.repository.MediaRepository
import com.vela.shared.R

/** 溢出菜单里由哪个元数据面板接替显示。 */
enum class MetadataEditor { None, EditMetadata, Images, Subtitles, Identify }

/** 条目是否支持“识别”（季、集会改为识别所属剧集）。 */
fun BaseItemDto.supportsIdentify(): Boolean {
    if (remoteSearchTypeFor(type) == null) return false
    val isChild = type.equals("Episode", true) || type.equals("Season", true)
    return !isChild || !seriesId.isNullOrBlank()
}

/** 只有视频条目有字幕可管理。 */
fun BaseItemDto.supportsSubtitleSearch(): Boolean = when (type?.lowercase()) {
    "movie", "episode", "video", "musicvideo", "trailer" -> true
    else -> false
}

/**
 * 当前用户是否管理员；识别、改图、改字幕、编辑元数据都需要该权限。
 * 查询失败按非管理员处理，入口不显示。
 */
@Composable
fun rememberIsAdministrator(mediaRepository: MediaRepository): Boolean {
    var isAdministrator by remember(mediaRepository) { mutableStateOf(false) }
    LaunchedEffect(mediaRepository) {
        isAdministrator = mediaRepository.getCurrentUser().getOrNull()?.policy?.isAdministrator == true
    }
    return isAdministrator
}

/**
 * 元数据编辑类面板的公共外壳：92% 高度的底部面板 + 标题栏。
 * [onBack] 非空时左侧显示返回（面板内二级页），否则显示关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MetadataSheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
            ) {
                IconButton(onClick = onBack ?: onDismiss) {
                    Icon(
                        imageVector = if (onBack != null) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Close,
                        contentDescription = stringResource(if (onBack != null) R.string.cd_back_button else R.string.cancel)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                )
                actions()
            }
            content()
        }
    }
}

/**
 * 显示 [editor] 对应的面板；溢出菜单选中某项后用它接替自身显示，父级无需额外状态。
 */
@Composable
fun MetadataEditorSheet(
    editor: MetadataEditor,
    item: BaseItemDto,
    mediaRepository: MediaRepository,
    onDismiss: () -> Unit
) {
    when (editor) {
        MetadataEditor.EditMetadata -> EditMetadataSheet(item, mediaRepository, onDismiss)
        MetadataEditor.Images -> ImageManagerSheet(item, mediaRepository, onDismiss)
        MetadataEditor.Subtitles -> SubtitleManagerSheet(item, mediaRepository, onDismiss)
        MetadataEditor.Identify -> IdentifyMetadataSheet(item, mediaRepository, onDismiss)
        MetadataEditor.None -> Unit
    }
}

internal enum class SheetContentState { Idle, Loading, Error, Empty, Content }

@Composable
internal fun errorText(error: Throwable): String {
    val status = (error as? HttpStatusException)?.statusCode
    return if (status == 401 || status == 403) {
        stringResource(R.string.metadata_error_forbidden)
    } else {
        stringResource(R.string.metadata_error_generic, error.message.orEmpty())
    }
}

@Composable
internal fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
internal fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp)
    )
}

@Composable
internal fun ProviderBadge(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}
