package com.vela.app.ui.screens.peach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vela.app.ui.screens.auth.rememberCertificateAuthorityImport
import com.vela.data.network.ServerTrust
import com.vela.data.peach.PeachApiException
import com.vela.data.peach.PeachCredentialStatus
import com.vela.data.peach.PeachPreferences
import com.vela.data.peach.PeachScrapingCheck
import com.vela.data.peach.formatBytes
import com.vela.shared.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 「Peach 管理」的各个分页，路由参数取 [route]。 */
enum class PeachSection(val route: String) {
    TASKS("tasks"),
    DOWNLOADS("downloads"),
    FEEDS("feeds"),
    FOLLOW("follow"),
    WANTS("wants"),
    SOURCES("sources"),
    PREFERENCES("preferences"),
    CONFIGURATION("configuration"),
    TRASH("trash"),
    CERTIFICATES("certificates"),
    REVIEW("review"),
    DUPLICATES("duplicates"),
    QUALITY("quality"),
    JUNK("junk"),
    CLEANUP("cleanup"),
    ORGANIZE("organize"),
    TAGS("tags"),
    METADATA_SOURCES("metadata-sources"),
    STATS("stats"),
    TASTE("taste"),
    CONFIG_LIBRARIES("config-libraries"),
    CONFIG_DOWNLOADS("config-downloads"),
    CONFIG_INDEXERS("config-indexers"),
    CONFIG_PROXIES("config-proxies"),
    CONFIG_UPDATES("config-updates"),
    CONFIG_STORAGE("config-storage"),
    CONFIG_NETWORK("config-network"),
    CONFIG_PROCESSING("config-processing"),
    CONFIG_PRIVACY("config-privacy"),
    CONFIG_DIAGNOSTICS("config-diagnostics"),
    ISSUES("issues"),
    FEED_LOOKUP("feed-lookup"),
    FOLLOW_RESOLVE("follow-resolve"),
    FOLLOW_AUTHORS("follow-authors");

    companion object {
        fun fromRoute(route: String?): PeachSection? = entries.firstOrNull { it.route == route }
    }
}

/**
 * Peach 管理入口。服务器配置等只允许本机的部分按实际响应降级，不假设可写。
 * 「订阅与关注」是 Peach 服务器上的追更来源，与 Vela 自己的 MoviePilot / Bangumi 订阅日历无关。
 */
@Composable
fun PeachManageScreen(
    section: String?,
    onBack: () -> Unit,
    onOpenSection: (PeachSection) -> Unit,
    onOpenWeb: (String) -> Unit,
    onOpenBrowse: () -> Unit = {},
    onOpenItem: (com.vela.data.model.BaseItemDto) -> Unit = {}
) {
    when (PeachSection.fromRoute(section)) {
        null -> PeachManageHome(onBack, onOpenSection, onOpenWeb, onOpenBrowse)
        PeachSection.TASKS -> PeachTasksPage(onBack)
        PeachSection.DOWNLOADS -> PeachDownloadsPage(onBack)
        PeachSection.FEEDS -> PeachFeedsPage(onBack, onOpenSection)
        PeachSection.FOLLOW -> PeachFollowPage(onBack, onOpenSection)
        PeachSection.WANTS -> PeachWantsPage(onBack)
        PeachSection.SOURCES -> PeachSourcesPage(onBack)
        PeachSection.PREFERENCES -> PeachPreferencesPage(onBack)
        PeachSection.CONFIGURATION -> PeachConfigurationHome(onBack, onOpenSection, onOpenWeb)
        PeachSection.TRASH -> PeachTrashPage(onBack)
        PeachSection.CERTIFICATES -> PeachCertificatesPage(onBack)
        PeachSection.REVIEW -> PeachReviewPage(onBack, onOpenItem)
        PeachSection.DUPLICATES -> PeachDuplicatesPage(onBack, onOpenItem)
        PeachSection.QUALITY -> PeachQualityGoalsPage(onBack, onOpenItem)
        PeachSection.JUNK -> PeachJunkPage(onBack)
        PeachSection.CLEANUP -> PeachCleanupPage(onBack, onOpenItem)
        PeachSection.ORGANIZE -> PeachOrganizePage(onBack)
        PeachSection.TAGS -> PeachTagManagementPage(onBack)
        PeachSection.METADATA_SOURCES -> PeachMetadataSourcesPage(onBack)
        PeachSection.STATS -> PeachStatsPage(onBack)
        PeachSection.TASTE -> PeachTastePage(onBack)
        PeachSection.CONFIG_LIBRARIES -> PeachLibrariesConfigPage(onBack, onOpenWeb)
        PeachSection.CONFIG_DOWNLOADS -> PeachDownloadsConfigPage(onBack, onOpenWeb)
        PeachSection.CONFIG_INDEXERS -> PeachIndexersConfigPage(onBack, onOpenWeb)
        PeachSection.CONFIG_PROXIES -> PeachProxiesConfigPage(onBack, onOpenWeb)
        PeachSection.CONFIG_UPDATES -> PeachUpdatesConfigPage(onBack, onOpenWeb)
        PeachSection.CONFIG_STORAGE -> PeachStorageConfigPage(onBack, onOpenWeb)
        PeachSection.CONFIG_NETWORK -> PeachNetworkConfigPage(onBack, onOpenWeb)
        PeachSection.CONFIG_PROCESSING -> PeachProcessingConfigPage(onBack, onOpenWeb)
        PeachSection.CONFIG_PRIVACY -> PeachPrivacyConfigPage(onBack, onOpenWeb)
        PeachSection.CONFIG_DIAGNOSTICS -> PeachDiagnosticsPage(onBack, onOpenWeb)
        PeachSection.ISSUES -> PeachIssuesPage(onBack, onOpenWeb)
        PeachSection.FEED_LOOKUP -> PeachFeedLookupPage(onBack)
        PeachSection.FOLLOW_RESOLVE -> PeachFollowResolvePage(onBack)
        PeachSection.FOLLOW_AUTHORS -> PeachFollowAuthorsPage(onBack)
    }
}

@Composable
private fun PeachManageHome(
    onBack: () -> Unit,
    onOpenSection: (PeachSection) -> Unit,
    onOpenWeb: (String) -> Unit,
    onOpenBrowse: () -> Unit
) {
    val version = rememberPeachLoad { serverVersion() }
    val feeds = rememberPeachLoad { feeds() }
    val unread = (feeds.value as? PeachLoad.Ready)?.value?.unread ?: 0
    PeachPageScaffold(title = stringResource(R.string.peach_manage_title), onBack = onBack) {
        item {
            PeachSection {
                PeachRow(stringResource(R.string.peach_browse_title), stringResource(R.string.peach_browse_subtitle), onClick = onOpenBrowse)
            }
        }
        item {
            PeachSection(stringResource(R.string.peach_section_activity)) {
                PeachRow(stringResource(R.string.peach_tasks_title), stringResource(R.string.peach_tasks_subtitle), onClick = { onOpenSection(PeachSection.TASKS) })
                PeachDivider()
                PeachRow(stringResource(R.string.peach_downloads_title), stringResource(R.string.peach_downloads_subtitle), onClick = { onOpenSection(PeachSection.DOWNLOADS) })
            }
        }
        item {
            PeachSection(stringResource(R.string.peach_section_tracking)) {
                PeachRow(
                    stringResource(R.string.peach_feeds_title),
                    stringResource(R.string.peach_feeds_subtitle),
                    value = unread.takeIf { it > 0 }?.toString(),
                    onClick = { onOpenSection(PeachSection.FEEDS) }
                )
                PeachDivider()
                PeachRow(stringResource(R.string.peach_follow_title), stringResource(R.string.peach_follow_subtitle), onClick = { onOpenSection(PeachSection.FOLLOW) })
                PeachDivider()
                PeachRow(stringResource(R.string.peach_wants_title), stringResource(R.string.peach_wants_subtitle), onClick = { onOpenSection(PeachSection.WANTS) })
            }
        }
        item {
            PeachSection(stringResource(R.string.peach_section_library)) {
                listOf(
                    Triple(PeachSection.REVIEW, R.string.peach_review_title, R.string.peach_review_subtitle),
                    Triple(PeachSection.ORGANIZE, R.string.peach_organize_title, R.string.peach_organize_subtitle),
                    Triple(PeachSection.TAGS, R.string.peach_tags_title, R.string.peach_tags_subtitle),
                    Triple(PeachSection.DUPLICATES, R.string.peach_duplicates_title, R.string.peach_duplicates_subtitle),
                    Triple(PeachSection.QUALITY, R.string.peach_quality_title, R.string.peach_quality_subtitle),
                    Triple(PeachSection.JUNK, R.string.peach_junk_title, R.string.peach_junk_subtitle),
                    Triple(PeachSection.CLEANUP, R.string.peach_cleanup_title, R.string.peach_cleanup_subtitle),
                    Triple(PeachSection.METADATA_SOURCES, R.string.peach_metadata_sources_title, R.string.peach_metadata_sources_subtitle),
                    Triple(PeachSection.STATS, R.string.peach_stats_title, R.string.peach_stats_subtitle),
                    Triple(PeachSection.TASTE, R.string.peach_taste_title, R.string.peach_taste_subtitle)
                ).forEachIndexed { index, (section, title, subtitle) ->
                    if (index > 0) PeachDivider()
                    PeachRow(stringResource(title), stringResource(subtitle), onClick = { onOpenSection(section) })
                }
            }
        }
        item {
            PeachSection(stringResource(R.string.peach_section_settings)) {
                PeachRow(stringResource(R.string.peach_sources_title), stringResource(R.string.peach_sources_subtitle), onClick = { onOpenSection(PeachSection.SOURCES) })
                PeachDivider()
                PeachRow(stringResource(R.string.peach_preferences_title), stringResource(R.string.peach_preferences_subtitle), onClick = { onOpenSection(PeachSection.PREFERENCES) })
                PeachDivider()
                PeachRow(stringResource(R.string.peach_configuration_title), stringResource(R.string.peach_configuration_subtitle), onClick = { onOpenSection(PeachSection.CONFIGURATION) })
                PeachDivider()
                PeachRow(stringResource(R.string.peach_trash_title), stringResource(R.string.peach_trash_subtitle), onClick = { onOpenSection(PeachSection.TRASH) })
                PeachDivider()
                PeachRow(stringResource(R.string.peach_certificates_title), stringResource(R.string.peach_certificates_subtitle), onClick = { onOpenSection(PeachSection.CERTIFICATES) })
            }
        }
        item {
            PeachSection {
                PeachRow(stringResource(R.string.peach_open_web), stringResource(R.string.peach_open_web_subtitle), onClick = { onOpenWeb("/") })
            }
        }
        item {
            val versionText = (version.value as? PeachLoad.Ready)?.value?.version
            if (versionText != null) PeachHint(stringResource(R.string.peach_server_version, versionText))
        }
    }
}

// region 任务中心

@Composable
private fun PeachTasksPage(onBack: () -> Unit) {
    val tasks = rememberPeachLoad { tasks() }
    val processing = rememberPeachLoad { libraryProcessing() }
    val actions = rememberPeachActions()
    // 前台定时刷新，离开页面随组合一起停止。
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(TASK_POLL_MS)
            tasks.reload()
            processing.reload()
        }
    }
    PeachPageScaffold(stringResource(R.string.peach_tasks_title), onBack, onRefresh = { tasks.reload(); processing.reload() }) {
        item {
            PeachSection(stringResource(R.string.peach_library_processing)) {
                when (val value = processing.value) {
                    is PeachLoad.Ready -> {
                        val state = value.value
                        val status = state["status"]?.toString()?.trim('"')
                        val jobId = state["job_id"]?.toString()?.trim('"')?.takeIf { it != "null" }
                        val checked = state["checked"]?.toString()?.toIntOrNull()
                        val total = state["total"]?.toString()?.toIntOrNull()
                        PeachRow(
                            title = status ?: stringResource(R.string.peach_idle),
                            subtitle = if (checked != null && total != null && total > 0) "$checked / $total" else null
                        )
                        if (checked != null && total != null && total > 0) {
                            LinearProgressIndicator(
                                progress = { (checked.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val running = status == "running" || status == "pausing"
                            if (jobId != null && running) {
                                TextButton(onClick = { actions.run(onDone = processing::reload) { controlLibraryProcessing(jobId, "pause") } }, enabled = !actions.isBusy) {
                                    Text(stringResource(R.string.peach_pause))
                                }
                                TextButton(onClick = { actions.run(onDone = processing::reload) { controlLibraryProcessing(jobId, "cancel") } }, enabled = !actions.isBusy) {
                                    Text(stringResource(R.string.peach_cancel))
                                }
                            } else if (jobId != null && status == "paused") {
                                TextButton(onClick = { actions.run(onDone = processing::reload) { controlLibraryProcessing(jobId, "resume") } }, enabled = !actions.isBusy) {
                                    Text(stringResource(R.string.peach_resume))
                                }
                            } else {
                                TextButton(onClick = { actions.run(onDone = processing::reload) { startLibraryProcessing() } }, enabled = !actions.isBusy) {
                                    Text(stringResource(R.string.peach_process_new))
                                }
                            }
                        }
                    }
                    is PeachLoad.Failed -> PeachErrorCard(value.error, processing::reload)
                    PeachLoad.Loading -> PeachRow(stringResource(R.string.peach_loading))
                }
            }
        }
        peachLoadItems(tasks) { value ->
            item {
                PeachSection(stringResource(R.string.peach_tasks_running)) {
                    PeachRows(value.running, stringResource(R.string.peach_tasks_none_running)) { run ->
                        Column {
                            PeachRow(run.label, listOfNotNull(run.progressLabel, run.startedAt).joinToString(" · "), value = run.status)
                            run.progressFraction?.let { fraction ->
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }
            item {
                PeachSection(stringResource(R.string.peach_tasks_finished)) {
                    PeachRows(value.finished, stringResource(R.string.peach_tasks_none_finished)) { run ->
                        PeachRow(run.label, run.error?.takeIf { it.isNotBlank() } ?: run.finishedAt, value = run.status)
                    }
                }
            }
            value.message?.let { item { PeachHint(it) } }
        }
    }
}

// endregion

// region 云下载

@Composable
private fun PeachDownloadsPage(onBack: () -> Unit) {
    val downloads = rememberPeachLoad { downloads() }
    val actions = rememberPeachActions()
    var magnet by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(TASK_POLL_MS)
            downloads.reload()
        }
    }
    PeachPageScaffold(stringResource(R.string.peach_downloads_title), onBack, onRefresh = downloads::reload) {
        peachLoadItems(downloads) { value ->
            item {
                PeachSection(stringResource(R.string.peach_downloads_channels)) {
                    PeachRows(value.channels, stringResource(R.string.peach_downloads_no_channel)) { channel ->
                        PeachRow(
                            channel.label,
                            value = stringResource(if (channel.configured) R.string.peach_configured else R.string.peach_not_configured)
                        )
                    }
                    value.problems.forEach { problem ->
                        PeachDivider()
                        PeachRow(problem)
                    }
                }
            }
            if (value.configuredChannels.isEmpty()) {
                item { PeachHint(stringResource(R.string.peach_downloads_configure_on_host)) }
            } else {
                item {
                    PeachSection(stringResource(R.string.peach_downloads_submit)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = magnet,
                                onValueChange = { magnet = it },
                                label = { Text(stringResource(R.string.peach_magnet_link)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                value.configuredChannels.forEach { channel ->
                                    OutlinedButton(
                                        enabled = magnet.startsWith("magnet:", ignoreCase = true) && !actions.isBusy,
                                        onClick = {
                                            val link = magnet.trim()
                                            actions.run(onDone = { magnet = ""; downloads.reload() }) {
                                                submitMagnet(link, channel.key, code = null, title = null)
                                            }
                                        }
                                    ) { Text(channel.label) }
                                }
                            }
                        }
                    }
                }
            }
            item {
                PeachSection(stringResource(R.string.peach_downloads_tasks)) {
                    PeachRows(value.tasks, stringResource(R.string.peach_downloads_empty)) { task ->
                        Column {
                            PeachRow(
                                title = listOfNotNull(task.code, task.title.takeIf { it != task.code }).joinToString(" "),
                                subtitle = listOfNotNull(task.providerLabel, task.failureLabel, task.failureDetail, task.postprocessLabel)
                                    .joinToString(" · "),
                                value = task.stateLabel
                            )
                            task.progress?.takeIf { task.inProgress }?.let { progress ->
                                LinearProgressIndicator(
                                    progress = { (if (progress > 1) progress / 100 else progress).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                            if (task.cancellable || task.postprocessRetryable) {
                                Row(Modifier.padding(horizontal = 8.dp)) {
                                    if (task.cancellable) {
                                        TextButton(enabled = !actions.isBusy, onClick = { actions.run(onDone = downloads::reload) { cancelDownload(task.id) } }) {
                                            Text(stringResource(R.string.peach_cancel))
                                        }
                                    }
                                    if (task.postprocessRetryable) {
                                        TextButton(enabled = !actions.isBusy, onClick = { actions.run(onDone = downloads::reload) { retryPostprocess(task.id) } }) {
                                            Text(stringResource(R.string.peach_retry_postprocess))
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
}

// endregion

// region 订阅源

@Composable
private fun PeachFeedsPage(onBack: () -> Unit, onOpenSection: (PeachSection) -> Unit) {
    val feeds = rememberPeachLoad { feeds() }
    val discoveries = rememberPeachLoad { discoveries() }
    val schedule = rememberPeachLoad { feedSchedule() }
    val actions = rememberPeachActions()
    var resourceCode by remember { mutableStateOf<String?>(null) }
    val reloadAll = { feeds.reload(); discoveries.reload(); schedule.reload() }
    PeachPageScaffold(stringResource(R.string.peach_feeds_title), onBack, onRefresh = reloadAll) {
        item {
            PeachSection(stringResource(R.string.peach_schedule)) {
                when (val value = schedule.value) {
                    is PeachLoad.Ready -> {
                        val s = value.value
                        PeachRow(
                            stringResource(if (s.enabled) R.string.peach_schedule_on else R.string.peach_schedule_off),
                            subtitle = listOfNotNull(s.nextRunAt?.let { stringResource(R.string.peach_next_run, it) }, s.lastError).joinToString("\n"),
                            value = stringResource(R.string.peach_interval_minutes, s.intervalMinutes)
                        )
                    }
                    is PeachLoad.Failed -> PeachRow(value.error.message ?: "")
                    PeachLoad.Loading -> PeachRow(stringResource(R.string.peach_loading))
                }
                PeachDivider()
                PeachRow(stringResource(R.string.peach_check_now), onClick = {
                    actions.run(successMessage = null, onDone = reloadAll) { checkFeeds() }
                })
            }
        }
        peachLoadItems(discoveries) { items ->
            item {
                PeachSection(stringResource(R.string.peach_discoveries)) {
                    PeachRows(items, stringResource(R.string.peach_discoveries_empty)) { discovery ->
                        Column {
                            PeachRow(
                                title = "${discovery.code} ${discovery.title.orEmpty()}".trim(),
                                subtitle = listOfNotNull(discovery.performers, discovery.studio, discovery.releaseDate).joinToString(" · "),
                                value = if (discovery.wanted) stringResource(R.string.peach_wanted) else null
                            )
                            Row(Modifier.padding(horizontal = 8.dp)) {
                                if (!discovery.read) {
                                    TextButton(enabled = !actions.isBusy, onClick = { actions.run(onDone = discoveries::reload) { markDiscoveries(listOf(discovery.id), "read") } }) {
                                        Text(stringResource(R.string.peach_mark_read))
                                    }
                                }
                                TextButton(enabled = !actions.isBusy, onClick = {
                                    actions.run(onDone = discoveries::reload) { markDiscoveries(listOf(discovery.id), if (discovery.wanted) "unwant" else "want") }
                                }) { Text(stringResource(if (discovery.wanted) R.string.peach_unwant else R.string.peach_want)) }
                                TextButton(onClick = { resourceCode = discovery.code }) { Text(stringResource(R.string.peach_find_resources)) }
                                TextButton(enabled = !actions.isBusy, onClick = { actions.run(onDone = discoveries::reload) { markDiscoveries(listOf(discovery.id), "ignore") } }) {
                                    Text(stringResource(R.string.peach_ignore))
                                }
                            }
                        }
                    }
                }
            }
        }
        peachLoadItems(feeds) { value ->
            item {
                PeachSection(stringResource(R.string.peach_feed_sources)) {
                    PeachRows(value.sources, stringResource(R.string.peach_feed_sources_empty)) { source ->
                        PeachRow(
                            title = source.name,
                            subtitle = listOfNotNull(source.kindLabel, source.lastError, source.lastNewCount?.takeIf { it > 0 }?.let { "+$it" })
                                .joinToString(" · "),
                            trailing = {
                                Row {
                                    androidx.compose.material3.Switch(
                                        checked = source.enabled,
                                        enabled = !actions.isBusy,
                                        onCheckedChange = { enabled -> actions.run(onDone = feeds::reload) { setFeedEnabled(source.id, enabled) } }
                                    )
                                    TextButton(enabled = !actions.isBusy, onClick = { actions.run(onDone = feeds::reload) { removeFeed(source.id) } }) {
                                        Text(stringResource(R.string.peach_remove))
                                    }
                                }
                            }
                        )
                    }
                }
            }
            item { PeachHint(stringResource(R.string.peach_feeds_hint)) }
            item { PeachFollowToolLinks(onOpenSection) }
        }
    }
    resourceCode?.let { code -> PeachResourceDialog(code = code, title = null, onDismiss = { resourceCode = null }) }
}

// endregion

// region 关注

@Composable
private fun PeachFollowPage(onBack: () -> Unit, onOpenSection: (PeachSection) -> Unit) {
    val sources = rememberPeachLoad { followSources() }
    val schedule = rememberPeachLoad { followSchedule() }
    val actions = rememberPeachActions()
    var url by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("") }
    PeachPageScaffold(stringResource(R.string.peach_follow_title), onBack, onRefresh = { sources.reload(); schedule.reload() }) {
        item { PeachFollowToolLinks(onOpenSection) }
        item {
            PeachSection(stringResource(R.string.peach_follow_add)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.peach_follow_url)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Button(
                        enabled = url.startsWith("http", ignoreCase = true) && !actions.isBusy,
                        onClick = {
                            val link = url
                            actions.run(onDone = { url = ""; sources.reload() }) { addFollowSource(link) }
                        }
                    ) { Text(stringResource(R.string.peach_add)) }
                    PeachHint(stringResource(R.string.peach_follow_add_hint))
                }
            }
        }
        item {
            PeachSection(stringResource(R.string.peach_schedule)) {
                when (val value = schedule.value) {
                    is PeachLoad.Ready -> {
                        val s = value.value
                        PeachSwitchRow(
                            title = stringResource(R.string.peach_schedule_auto_check),
                            subtitle = listOfNotNull(s.nextRunAt?.let { stringResource(R.string.peach_next_run, it) }, s.lastError).joinToString("\n"),
                            checked = s.enabled,
                            enabled = s.available && !actions.isBusy,
                            onCheckedChange = { enabled -> actions.run(onDone = schedule::reload) { setFollowSchedule(enabled, s.intervalMinutes) } }
                        )
                        PeachDivider()
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = interval.ifEmpty { s.intervalMinutes.toString() },
                                onValueChange = { interval = it.filter(Char::isDigit) },
                                label = { Text(stringResource(R.string.peach_interval_label)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            TextButton(
                                enabled = interval.toIntOrNull() != null && !actions.isBusy,
                                onClick = {
                                    val minutes = interval.toInt()
                                    actions.run(onDone = { interval = ""; schedule.reload() }) { setFollowSchedule(s.enabled, minutes) }
                                }
                            ) { Text(stringResource(R.string.peach_save)) }
                        }
                    }
                    is PeachLoad.Failed -> PeachRow(value.error.message ?: "")
                    PeachLoad.Loading -> PeachRow(stringResource(R.string.peach_loading))
                }
                PeachDivider()
                PeachRow(stringResource(R.string.peach_check_now), onClick = { actions.run(onDone = sources::reload) { checkFollow() } })
            }
        }
        peachLoadItems(sources) { items ->
            item {
                PeachSection(stringResource(R.string.peach_follow_sources)) {
                    PeachRows(items, stringResource(R.string.peach_follow_sources_empty)) { source ->
                        PeachRow(
                            title = source.label,
                            subtitle = listOfNotNull(source.providerLabel, source.lastError ?: source.lastCheckedAt).joinToString(" · "),
                            trailing = {
                                Row {
                                    androidx.compose.material3.Switch(
                                        checked = source.enabled,
                                        enabled = !actions.isBusy,
                                        onCheckedChange = { enabled -> actions.run(onDone = sources::reload) { setFollowSourceEnabled(source.id, enabled) } }
                                    )
                                    TextButton(enabled = !actions.isBusy, onClick = { actions.run(onDone = sources::reload) { removeFollowSource(source.id) } }) {
                                        Text(stringResource(R.string.peach_remove))
                                    }
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

// region 想要清单

@Composable
private fun PeachWantsPage(onBack: () -> Unit) {
    val wants = rememberPeachLoad { wants() }
    val actions = rememberPeachActions()
    var code by remember { mutableStateOf("") }
    var resourceCode by remember { mutableStateOf<String?>(null) }
    PeachPageScaffold(stringResource(R.string.peach_wants_title), onBack, onRefresh = wants::reload) {
        item {
            PeachSection {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.peach_want_code)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        enabled = code.isNotBlank() && !actions.isBusy,
                        onClick = {
                            val value = code
                            actions.run(onDone = { code = ""; wants.reload() }) { addWant(value) }
                        }
                    ) { Text(stringResource(R.string.peach_add)) }
                }
            }
        }
        item { PeachHint(stringResource(R.string.peach_wants_hint)) }
        peachLoadItems(wants) { items ->
            item {
                PeachSection {
                    PeachRows(items, stringResource(R.string.peach_wants_empty)) { want ->
                        PeachRow(
                            title = listOfNotNull(want.code, want.title).joinToString(" "),
                            subtitle = want.lastSearchOutcome,
                            value = want.phase,
                            trailing = {
                                Row {
                                    want.code?.let { wantCode ->
                                        TextButton(onClick = { resourceCode = wantCode }) { Text(stringResource(R.string.peach_find_resources)) }
                                    }
                                    TextButton(enabled = !actions.isBusy, onClick = { actions.run(onDone = wants::reload) { removeWants(listOf(want.id)) } }) {
                                        Text(stringResource(R.string.peach_remove))
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    resourceCode?.let { value -> PeachResourceDialog(code = value, title = null, onDismiss = { resourceCode = null }) }
}

// endregion

// region 来源与凭据

@Composable
private fun PeachSourcesPage(onBack: () -> Unit) {
    val sources = rememberPeachLoad { scrapingSources() }
    val credentials = rememberPeachLoad { credentials() }
    val actions = rememberPeachActions()
    val checkResults = remember { mutableStateMapOf<String, List<PeachScrapingCheck>>() }
    var editing by remember { mutableStateOf<PeachCredentialStatus?>(null) }
    PeachPageScaffold(stringResource(R.string.peach_sources_title), onBack, onRefresh = { sources.reload(); credentials.reload() }) {
        peachLoadItems(sources) { items ->
            item {
                PeachSection(stringResource(R.string.peach_scraping_sources)) {
                    PeachRows(items, stringResource(R.string.peach_scraping_empty)) { source ->
                        Column {
                            val notes = buildList {
                                source.network?.let { add(it) }
                                if (source.acceptsCookie) {
                                    add(stringResource(if (source.cookieSaved) R.string.peach_cookie_saved else R.string.peach_cookie_missing))
                                }
                                if (source.needsBrowser) add(stringResource(R.string.peach_needs_browser))
                            }
                            PeachRow(
                                title = source.label,
                                subtitle = notes.joinToString(" · "),
                                trailing = {
                                    TextButton(enabled = !actions.isBusy, onClick = {
                                        actions.run {
                                            checkResults[source.source] = checkScrapingSource(source.source)
                                        }
                                    }) { Text(stringResource(R.string.peach_check)) }
                                }
                            )
                            checkResults[source.source]?.forEach { result ->
                                Text(
                                    text = (if (result.ok) "✓ " else "✗ ") + listOfNotNull(result.label, result.reason).joinToString("："),
                                    color = if (result.ok) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        peachLoadItems(credentials) { items ->
            item {
                PeachSection(stringResource(R.string.peach_credentials)) {
                    PeachRows(items, stringResource(R.string.peach_credentials_empty)) { credential ->
                        PeachRow(
                            title = credential.label,
                            subtitle = if (credential.missing.isNotEmpty()) {
                                stringResource(R.string.peach_credential_missing, credential.missing.joinToString("、"))
                            } else {
                                credential.requirement
                            },
                            value = stringResource(if (credential.present) R.string.peach_saved else R.string.peach_not_saved),
                            onClick = if (credential.fields.isNotEmpty()) ({ editing = credential }) else null
                        )
                    }
                }
            }
            item { PeachHint(stringResource(R.string.peach_credentials_hint)) }
        }
    }
    editing?.let { credential ->
        PeachCredentialDialog(
            credential = credential,
            busy = actions.isBusy,
            onSave = { values ->
                actions.run(successMessage = null, onDone = { editing = null; credentials.reload() }) {
                    saveCredential(credential.provider, values)
                }
            },
            onDismiss = { editing = null }
        )
    }
}

/** 凭据输入：密码样式，不回显已保存的值；对话框关闭即丢弃输入，不在 Vela 本地留存。 */
@Composable
private fun PeachCredentialDialog(
    credential: PeachCredentialStatus,
    busy: Boolean,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val values = remember(credential.provider) { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(credential.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.peach_credentials_stored_on_host), style = MaterialTheme.typography.bodySmall)
                credential.howto?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                credential.fields.forEach { field ->
                    OutlinedTextField(
                        value = values[field].orEmpty(),
                        onValueChange = { values[field] = it },
                        label = { Text(field) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && values.values.any { it.isNotBlank() },
                onClick = {
                    val submitted = values.toMap()
                    values.clear()
                    onSave(submitted)
                }
            ) { Text(stringResource(R.string.peach_save)) }
        },
        dismissButton = {
            TextButton(onClick = { values.clear(); onDismiss() }) { Text(stringResource(R.string.peach_cancel)) }
        }
    )
}

// endregion

// region 内容偏好

@Composable
private fun PeachPreferencesPage(onBack: () -> Unit) {
    val preferences = rememberPeachLoad { preferences() }
    val actions = rememberPeachActions()
    PeachPageScaffold(stringResource(R.string.peach_preferences_title), onBack, onRefresh = preferences::reload) {
        peachLoadItems(preferences) { prefs ->
            item {
                PeachSection(stringResource(R.string.peach_follow_title)) {
                    PeachRow(
                        title = stringResource(R.string.peach_pref_follow_initial_days),
                        subtitle = stringResource(R.string.peach_pref_follow_initial_days_hint)
                    )
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        PeachPreferences.FOLLOW_INITIAL_DAY_OPTIONS.forEach { days ->
                            val selected = prefs.followInitialDays == days
                            TextButton(enabled = !actions.isBusy && !selected, onClick = {
                                actions.run(onDone = preferences::reload) { updatePreference(PeachPreferences.KEY_FOLLOW_INITIAL_DAYS, days) }
                            }) {
                                Text(if (selected) "● $days" else days.toString())
                            }
                        }
                    }
                    PeachDivider()
                    PeachSwitchRow(stringResource(R.string.peach_pref_hide_group), checked = prefs.feedHideGroupCompilations, enabled = !actions.isBusy) { value ->
                        actions.run(onDone = preferences::reload) { updatePreference(PeachPreferences.KEY_HIDE_GROUP, value) }
                    }
                    PeachDivider()
                    PeachSwitchRow(stringResource(R.string.peach_pref_hide_solo), checked = prefs.feedHideSoloCompilations, enabled = !actions.isBusy) { value ->
                        actions.run(onDone = preferences::reload) { updatePreference(PeachPreferences.KEY_HIDE_SOLO, value) }
                    }
                    PeachDivider()
                    PeachSwitchRow(stringResource(R.string.peach_pref_hide_excerpts), checked = prefs.feedHideExcerpts, enabled = !actions.isBusy) { value ->
                        actions.run(onDone = preferences::reload) { updatePreference(PeachPreferences.KEY_HIDE_EXCERPTS, value) }
                    }
                }
            }
            item {
                PeachSection(stringResource(R.string.peach_pref_search)) {
                    PeachRow(stringResource(R.string.peach_pref_search_history_limit), value = prefs.searchHistoryLimit?.toString() ?: "—")
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        listOf(0, 10, 20, 50).forEach { limit ->
                            TextButton(enabled = !actions.isBusy && prefs.searchHistoryLimit != limit, onClick = {
                                actions.run(onDone = preferences::reload) { updatePreference(PeachPreferences.KEY_SEARCH_HISTORY_LIMIT, limit) }
                            }) { Text(limit.toString()) }
                        }
                    }
                }
            }
            item { PeachHint(stringResource(R.string.peach_preferences_hint)) }
        }
    }
}

// endregion



// region 回收站

@Composable
private fun PeachTrashPage(onBack: () -> Unit) {
    val summary = rememberPeachLoad { trashSummary() }
    val actions = rememberPeachActions()
    var confirming by remember { mutableStateOf(false) }
    PeachPageScaffold(stringResource(R.string.peach_trash_title), onBack, onRefresh = summary::reload) {
        peachLoadItems(summary) { (count, bytes) ->
            item {
                PeachSection {
                    PeachRow(stringResource(R.string.peach_trash_count), value = count.toString())
                    PeachDivider()
                    PeachRow(stringResource(R.string.peach_trash_reclaimable), value = formatBytes(bytes))
                }
            }
            item {
                Button(enabled = count > 0 && !actions.isBusy, onClick = { confirming = true }) {
                    Text(stringResource(R.string.peach_trash_empty))
                }
            }
            item { PeachHint(stringResource(R.string.peach_trash_hint)) }
            if (confirming) {
                item {
                    AlertDialog(
                        onDismissRequest = { confirming = false },
                        title = { Text(stringResource(R.string.peach_trash_empty)) },
                        text = { Text(stringResource(R.string.peach_trash_confirm, count, formatBytes(bytes))) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirming = false
                                actions.run(onDone = summary::reload) { emptyTrash() }
                            }) { Text(stringResource(R.string.peach_trash_empty), color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.peach_cancel)) } }
                    )
                }
            }
        }
    }
}

// endregion

// region 证书

@Composable
private fun PeachCertificatesPage(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var authorities by remember { mutableStateOf(ServerTrust.trustedAuthorities()) }
    val importedText = stringResource(R.string.peach_certificates_imported)
    val importAuthority = rememberCertificateAuthorityImport { _ ->
        authorities = ServerTrust.trustedAuthorities()
        android.widget.Toast.makeText(context, importedText, android.widget.Toast.LENGTH_SHORT).show()
    }
    PeachPageScaffold(stringResource(R.string.peach_certificates_title), onBack) {
        item {
            PeachSection(stringResource(R.string.peach_certificates_authorities)) {
                PeachRows(authorities, stringResource(R.string.peach_certificates_none)) { authority ->
                    PeachRow(
                        title = authority.subject,
                        subtitle = "SHA-256 ${authority.sha256}\n" + stringResource(R.string.auth_ca_valid_until, authority.notAfter),
                        trailing = {
                            TextButton(onClick = {
                                ServerTrust.removeAuthority(authority.sha256)
                                authorities = ServerTrust.trustedAuthorities()
                            }) { Text(stringResource(R.string.peach_remove)) }
                        }
                    )
                }
            }
        }
        item { Button(onClick = importAuthority) { Text(stringResource(R.string.peach_certificates_import)) } }
        item { PeachHint(stringResource(R.string.peach_certificates_hint)) }
    }
}

// endregion

private const val TASK_POLL_MS = 5_000L
