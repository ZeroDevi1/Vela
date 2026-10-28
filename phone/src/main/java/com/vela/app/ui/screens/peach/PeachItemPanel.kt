package com.vela.app.ui.screens.peach

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
fun PeachItemPanel(itemId: String, modifier: Modifier = Modifier, onChanged: () -> Unit = {}) {
    val assetId = PeachIds.assetId(itemId) ?: return
    val context = LocalContext.current
    val repository = remember { PeachRepository.getInstance(context) }
    var asset by remember(assetId) { mutableStateOf<PeachAsset?>(null) }
    var showResources by remember { mutableStateOf(false) }
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
        }
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
