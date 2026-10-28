package com.vela.app.ui.screens.peach

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vela.data.model.BaseItemDto
import com.vela.data.peach.formatBytes
import com.vela.data.peach.peachBool
import com.vela.data.peach.peachDouble
import com.vela.data.peach.peachInt
import com.vela.data.peach.peachLong
import com.vela.data.peach.peachObject
import com.vela.data.peach.peachObjects
import com.vela.data.peach.peachString
import com.vela.data.peach.peachText
import com.vela.shared.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject

// region 人工复核

private val REVIEW_QUEUES = listOf(
    "pending" to R.string.peach_review_pending,
    "later" to R.string.peach_review_later,
    "ignored" to R.string.peach_review_ignored
)

/** 复核类别的中文名；未知类别原样显示。 */
@Composable
private fun reviewCategoryLabel(category: String): String = when (category) {
    "metadata_fields" -> stringResource(R.string.peach_review_cat_metadata)
    "creator_tags" -> stringResource(R.string.peach_review_cat_creator_tags)
    "studio_logos" -> stringResource(R.string.peach_review_cat_studio_logos)
    "performer_avatars" -> stringResource(R.string.peach_review_cat_avatars)
    "western_identity" -> stringResource(R.string.peach_review_cat_western)
    "cover_sources" -> stringResource(R.string.peach_review_cat_covers)
    "fc2_markings", "fc2_similarity" -> stringResource(R.string.peach_review_cat_fc2)
    "video_endcards" -> stringResource(R.string.peach_review_cat_endcards)
    "code_creators" -> stringResource(R.string.peach_review_cat_code_creators)
    else -> category
}

@Composable
internal fun PeachReviewPage(onBack: () -> Unit, onOpenItem: (BaseItemDto) -> Unit) {
    val review = rememberPeachLoad { review() }
    val actions = rememberPeachActions()
    var queue by remember { mutableStateOf("pending") }
    var category by remember { mutableStateOf<String?>(null) }
    val chosenCandidates = remember { mutableStateMapOf<String, String>() }
    PeachPageScaffold(
        title = category?.let { reviewCategoryLabel(it) } ?: stringResource(R.string.peach_review_title),
        onBack = { if (category != null) category = null else onBack() },
        onRefresh = review::reload
    ) {
        peachLoadItems(review) { data ->
            val queueData = if (queue == "pending") data else data.peachObject("queues")?.peachObject(queue)
            val sections = queueData?.peachObject("sections")
            val counts = queueData?.peachObject("counts")
            data.peachObject("mirror")?.let { item { PeachHint(stringResource(R.string.peach_review_mirror)) } }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    REVIEW_QUEUES.forEach { (key, label) ->
                        FilterChip(selected = queue == key, onClick = { queue = key; category = null }, label = { Text(stringResource(label)) })
                    }
                }
            }
            val selected = category
            if (selected == null) {
                item {
                    PeachSection {
                        val keys = sections?.keys?.toList().orEmpty()
                        PeachRows(keys, stringResource(R.string.peach_review_empty)) { key ->
                            PeachRow(reviewCategoryLabel(key), value = (counts?.peachInt(key) ?: sections?.peachObjects(key)?.size ?: 0).toString(), onClick = { category = key })
                        }
                    }
                }
            } else {
                val rows = sections?.peachObjects(selected).orEmpty()
                if (rows.isEmpty()) item { PeachHint(stringResource(R.string.peach_review_empty)) }
                rows.take(REVIEW_PAGE).forEach { row ->
                    val itemKey = row.peachString("item_key") ?: return@forEach
                    item(key = itemKey) {
                        PeachSection {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val title = listOfNotNull(row.peachString("asset_code"), row.peachString("asset_name") ?: row.peachString("name") ?: itemKey)
                                    .joinToString(" ")
                                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                                listOfNotNull(
                                    row.peachString("field")?.let { field -> "$field：${row.peachString("current_value").orEmpty()}" },
                                    row.peachString("reason"),
                                    row.peachString("decision_note")
                                ).forEach { line -> Text(line, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall) }
                                val candidates = row.peachObjects("candidates")
                                candidates.forEach { candidate ->
                                    val key = candidate.peachString("candidate_key") ?: return@forEach
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = chosenCandidates[itemKey] == key, onClick = { chosenCandidates[itemKey] = key })
                                        Text(
                                            listOfNotNull(candidate.peachString("display_value") ?: candidate.peachString("value"), candidate.peachString("source"))
                                                .joinToString(" · "),
                                            color = Color.White,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    fun decide(status: String) {
                                        actions.run(onDone = review::reload) {
                                            reviewDecision(
                                                category = selected,
                                                itemKey = itemKey,
                                                status = status,
                                                candidateKey = chosenCandidates[itemKey]
                                                    ?: candidates.singleOrNull()?.peachString("candidate_key"),
                                                expectedRevision = row.peachInt("asset_mutation_revision")
                                            )
                                        }
                                    }
                                    if (queue == "pending" || queue == "later") {
                                        TextButton(
                                            enabled = !actions.isBusy && (candidates.isEmpty() || chosenCandidates[itemKey] != null || candidates.size == 1),
                                            onClick = { decide("approved") }
                                        ) { Text(stringResource(R.string.peach_review_approve)) }
                                        TextButton(enabled = !actions.isBusy, onClick = { decide("rejected") }) { Text(stringResource(R.string.peach_review_reject)) }
                                    }
                                    if (queue == "pending") {
                                        TextButton(enabled = !actions.isBusy, onClick = { decide("skipped") }) { Text(stringResource(R.string.peach_review_later)) }
                                    } else {
                                        TextButton(enabled = !actions.isBusy, onClick = { decide("restore") }) { Text(stringResource(R.string.peach_restore)) }
                                    }
                                    row.peachLong("asset_id")?.let { assetId ->
                                        TextButton(onClick = { onOpenItem(BaseItemDto(id = assetId.toString(), type = "Movie")) }) {
                                            Text(stringResource(R.string.peach_open))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (rows.size > REVIEW_PAGE) item { PeachHint(stringResource(R.string.peach_review_more, rows.size - REVIEW_PAGE)) }
            }
        }
    }
}

private const val REVIEW_PAGE = 50

// endregion

// region 重复文件

@Composable
internal fun PeachDuplicatesPage(onBack: () -> Unit, onOpenItem: (BaseItemDto) -> Unit) {
    val duplicates = rememberPeachLoad { duplicates(limit = 120) }
    val actions = rememberPeachActions()
    PeachPageScaffold(stringResource(R.string.peach_duplicates_title), onBack, onRefresh = duplicates::reload) {
        peachLoadItems(duplicates) { data ->
            item {
                PeachHint(
                    stringResource(
                        R.string.peach_duplicates_summary,
                        data.peachInt("total") ?: 0,
                        formatBytes(data.peachLong("reclaimable") ?: 0)
                    )
                )
            }
            data.peachObjects("groups").forEach { group ->
                val files = group.peachObjects("files")
                item(key = group.peachString("code") ?: files.firstOrNull()?.peachLong("id")?.toString()) {
                    PeachSection(
                        listOfNotNull(group.peachString("code"), formatBytes(group.peachLong("reclaimable") ?: 0)).joinToString(" · ")
                    ) {
                        files.forEachIndexed { index, file ->
                            if (index > 0) PeachDivider()
                            val id = file.peachLong("id") ?: return@forEachIndexed
                            val flags = listOfNotNull(
                                stringResource(R.string.peach_duplicates_largest).takeIf { file.peachBool("is_largest") == true },
                                stringResource(R.string.peach_duplicates_longest).takeIf { file.peachBool("is_longest") == true }
                            )
                            PeachRow(
                                title = file.peachString("name").orEmpty(),
                                subtitle = (listOfNotNull(file.peachString("drive"), file.peachLong("size")?.let(::formatBytes)) + flags).joinToString(" · "),
                                onClick = { onOpenItem(BaseItemDto(id = id.toString(), type = "Movie")) },
                                trailing = {
                                    TextButton(enabled = !actions.isBusy, onClick = {
                                        actions.run(onDone = duplicates::reload) { batch(listOf(id), "dispose", disposalReason = "duplicate") }
                                    }) { Text(stringResource(R.string.peach_to_trash)) }
                                }
                            )
                        }
                        val keep = files.firstOrNull { it.peachBool("is_largest") == true } ?: files.firstOrNull()
                        val others = files.filter { it !== keep }.mapNotNull { it.peachLong("id") }
                        if (others.isNotEmpty()) {
                            PeachDivider()
                            PeachRow(stringResource(R.string.peach_duplicates_keep_largest), onClick = {
                                actions.run(onDone = duplicates::reload) { batch(others, "dispose", disposalReason = "duplicate") }
                            })
                        }
                    }
                }
            }
            item { PeachHint(stringResource(R.string.peach_trash_hint)) }
        }
    }
}

// endregion

// region 高清版目标

@Composable
internal fun PeachQualityGoalsPage(onBack: () -> Unit, onOpenItem: (BaseItemDto) -> Unit) {
    val goals = rememberPeachLoad { qualityGoals(limit = 200) }
    val actions = rememberPeachActions()
    var searching by remember { mutableStateOf<String?>(null) }
    PeachPageScaffold(stringResource(R.string.peach_quality_title), onBack, onRefresh = goals::reload) {
        item { PeachHint(stringResource(R.string.peach_quality_hint)) }
        peachLoadItems(goals) { data ->
            item {
                PeachSection {
                    PeachRows(data.peachObjects("items"), stringResource(R.string.peach_empty)) { goal ->
                        val id = goal.peachLong("id") ?: return@PeachRows
                        PeachRow(
                            title = listOfNotNull(goal.peachString("code"), goal.peachString("name")).joinToString(" "),
                            subtitle = listOfNotNull(goal.peachString("reason"), goal.peachLong("size")?.let(::formatBytes)).joinToString(" · "),
                            onClick = { onOpenItem(BaseItemDto(id = id.toString(), type = "Movie")) },
                            trailing = {
                                Row {
                                    goal.peachString("code")?.let { code ->
                                        TextButton(onClick = { searching = code }) { Text(stringResource(R.string.peach_find_resources)) }
                                    }
                                    TextButton(enabled = !actions.isBusy, onClick = {
                                        actions.run(onDone = goals::reload) { setQualityGoal(id, wanted = false) }
                                    }) { Text(stringResource(R.string.peach_remove)) }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    searching?.let { code -> PeachResourceDialog(code = code, title = null, onDismiss = { searching = null }) }
}

// endregion

// region 垃圾文件

private val JUNK_KINDS = listOf("" to R.string.peach_state_all, "video" to R.string.peach_junk_video, "image" to R.string.peach_junk_image,
    "audio" to R.string.peach_junk_audio, "archive" to R.string.peach_junk_archive, "url" to R.string.peach_junk_url, "other" to R.string.peach_junk_other)

@Composable
internal fun PeachJunkPage(onBack: () -> Unit) {
    var status by remember { mutableStateOf("pending") }
    var kind by remember { mutableStateOf("") }
    val junk = rememberPeachLoadKeyed(status to kind) { junkFiles(kind.ifEmpty { null }, status, limit = 200) }
    val actions = rememberPeachActions()
    PeachPageScaffold(stringResource(R.string.peach_junk_title), onBack, onRefresh = junk::reload) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = status == "pending", onClick = { status = "pending" }, label = { Text(stringResource(R.string.peach_junk_pending)) })
                FilterChip(selected = status == "dismissed", onClick = { status = "dismissed" }, label = { Text(stringResource(R.string.peach_junk_dismissed)) })
                JUNK_KINDS.forEach { (key, label) ->
                    FilterChip(selected = kind == key, onClick = { kind = key }, label = { Text(stringResource(label)) })
                }
            }
        }
        peachLoadItems(junk) { data ->
            item {
                PeachSection {
                    PeachRows(data.peachObjects("items"), stringResource(R.string.peach_empty)) { row ->
                        val id = row.peachLong("id") ?: return@PeachRows
                        PeachRow(
                            title = row.peachString("name").orEmpty(),
                            subtitle = listOfNotNull(row.peachString("why"), row.peachLong("size")?.let(::formatBytes)).joinToString(" · "),
                            value = row.peachInt("score")?.toString(),
                            trailing = {
                                Row {
                                    if (status == "pending") {
                                        TextButton(enabled = !actions.isBusy, onClick = {
                                            actions.run(onDone = junk::reload) { batch(listOf(id), "dismiss-junk") }
                                        }) { Text(stringResource(R.string.peach_junk_not_junk)) }
                                        TextButton(enabled = !actions.isBusy, onClick = {
                                            actions.run(onDone = junk::reload) { batch(listOf(id), "dispose", disposalReason = "junk") }
                                        }) { Text(stringResource(R.string.peach_to_trash)) }
                                    } else {
                                        TextButton(enabled = !actions.isBusy, onClick = {
                                            actions.run(onDone = junk::reload) { batch(listOf(id), "reconsider-junk") }
                                        }) { Text(stringResource(R.string.peach_junk_reconsider)) }
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

// region 数据清理

@Composable
internal fun PeachCleanupPage(onBack: () -> Unit, onOpenItem: (BaseItemDto) -> Unit) {
    val orphans = rememberPeachLoad { orphanRecords() }
    val links = rememberPeachLoad { links() }
    val repair = rememberPeachLoad { mediaRepair() }
    val libraries = rememberPeachLoad { libraries() }
    var sync by remember { mutableStateOf<JsonObject?>(null) }
    var linkCheck by remember { mutableStateOf<JsonObject?>(null) }
    var confirmSync by remember { mutableStateOf(false) }
    var confirmPrune by remember { mutableStateOf(false) }
    val actions = rememberPeachActions()
    // 后台任务在前台定时轮询，离开页面即停止。
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(TOOL_POLL_MS)
            repair.reload()
        }
    }
    PeachPageScaffold(stringResource(R.string.peach_cleanup_title), onBack) {
        item {
            PeachSection(stringResource(R.string.peach_cleanup_sync)) {
                val status = sync
                PeachRow(
                    title = stringResource(R.string.peach_cleanup_sync_hint),
                    subtitle = status?.let { s ->
                        listOfNotNull(
                            s.peachString("status"),
                            s.peachInt("missing")?.let { stringResource(R.string.peach_cleanup_missing, it) },
                            s.peachInt("vanish")?.let { stringResource(R.string.peach_cleanup_vanish, it) },
                            s.peachInt("empty")?.let { stringResource(R.string.peach_cleanup_empty_dirs, it) },
                            s.peachObject("cache")?.peachLong("bytes")?.let { stringResource(R.string.peach_cleanup_cache, formatBytes(it)) },
                            s.peachInt("purged")?.let { stringResource(R.string.peach_cleanup_purged, it) },
                            s.peachString("error")?.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                    }
                )
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(enabled = !actions.isBusy, onClick = { actions.run { sync = resourceSyncScan(restart = true) } }) {
                        Text(stringResource(R.string.peach_cleanup_scan))
                    }
                    TextButton(enabled = !actions.isBusy, onClick = { actions.run { sync = resourceSyncStatus() } }) {
                        Text(stringResource(R.string.peach_refresh))
                    }
                    if (sync?.peachString("status") == "complete" && sync?.peachBool("applied") != true && sync?.peachString("scan_id") != null) {
                        TextButton(enabled = !actions.isBusy, onClick = { confirmSync = true }) { Text(stringResource(R.string.peach_cleanup_apply)) }
                    }
                }
            }
        }
        item {
            PeachSection(stringResource(R.string.peach_cleanup_orphans)) {
                when (val value = orphans.value) {
                    is PeachLoad.Ready -> PeachRows(value.value.peachObjects("items"), stringResource(R.string.peach_empty)) { orphan ->
                        val id = orphan.peachLong("id") ?: return@PeachRows
                        val candidate = orphan.peachObjects("candidates").firstOrNull()
                        PeachRow(
                            title = listOfNotNull(orphan.peachString("code"), orphan.peachString("name")).joinToString(" "),
                            subtitle = candidate?.let { stringResource(R.string.peach_cleanup_candidate, it.peachString("name").orEmpty()) },
                            trailing = {
                                Row {
                                    candidate?.peachLong("id")?.let { target ->
                                        TextButton(enabled = !actions.isBusy, onClick = {
                                            actions.run(onDone = orphans::reload) { attachOrphan(id, target) }
                                        }) { Text(stringResource(R.string.peach_cleanup_attach)) }
                                    }
                                    TextButton(enabled = !actions.isBusy, onClick = {
                                        actions.run(onDone = orphans::reload) { batch(listOf(id), "delete") }
                                    }) { Text(stringResource(R.string.peach_remove)) }
                                }
                            }
                        )
                    }
                    is PeachLoad.Failed -> PeachRow(value.error.message.orEmpty())
                    PeachLoad.Loading -> PeachRow(stringResource(R.string.peach_loading))
                }
            }
        }
        item {
            PeachSection(stringResource(R.string.peach_cleanup_links)) {
                val total = (links.value as? PeachLoad.Ready)?.value?.peachInt("total")
                PeachRow(stringResource(R.string.peach_cleanup_links_total), value = total?.toString())
                linkCheck?.let { check ->
                    PeachDivider()
                    PeachRow(
                        title = listOfNotNull(check.peachString("status"), "${check.peachInt("checked") ?: 0}/${check.peachInt("total") ?: 0}").joinToString(" "),
                        subtitle = stringResource(
                            R.string.peach_cleanup_links_result,
                            check.peachObjects("gone").size,
                            check.peachObjects("unclear").size
                        )
                    )
                    check.peachObjects("gone").take(30).forEach { link ->
                        PeachRow(link.peachString("label") ?: link.peachString("url").orEmpty(), subtitle = link.peachString("entity"))
                    }
                }
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(enabled = !actions.isBusy, onClick = { actions.run { linkCheck = checkLinks(restart = true) } }) {
                        Text(stringResource(R.string.peach_cleanup_links_check))
                    }
                    TextButton(enabled = !actions.isBusy, onClick = { actions.run { linkCheck = linkCheckStatus() } }) {
                        Text(stringResource(R.string.peach_refresh))
                    }
                    if (linkCheck?.peachObjects("gone")?.isNotEmpty() == true && linkCheck?.peachString("check_id") != null) {
                        TextButton(enabled = !actions.isBusy, onClick = { confirmPrune = true }) { Text(stringResource(R.string.peach_cleanup_links_prune)) }
                    }
                }
            }
        }
        item {
            PeachSection(stringResource(R.string.peach_cleanup_repair)) {
                val state = (repair.value as? PeachLoad.Ready)?.value
                PeachRow(
                    title = state?.peachString("status") ?: stringResource(R.string.peach_idle),
                    subtitle = state?.let { s ->
                        listOfNotNull(
                            s.peachString("message"),
                            "${s.peachInt("checked") ?: 0}/${s.peachInt("total") ?: 0}",
                            s.peachInt("repaired")?.let { stringResource(R.string.peach_cleanup_repaired, it) },
                            s.peachString("error")
                        ).joinToString(" · ")
                    }
                )
                if (state?.peachString("status") == "running") {
                    TextButton(enabled = !actions.isBusy, onClick = { actions.run(onDone = repair::reload) { stopMediaRepair() } }) {
                        Text(stringResource(R.string.peach_cancel))
                    }
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                        (libraries.value as? PeachLoad.Ready)?.value?.forEach { library ->
                            TextButton(enabled = !actions.isBusy, onClick = {
                                actions.run(onDone = repair::reload) { startMediaRepair(library.id) }
                            }) { Text(library.name) }
                        }
                    }
                }
            }
        }
        item { PeachHint(stringResource(R.string.peach_cleanup_hint)) }
    }
    if (confirmSync) {
        val scanId = sync?.peachString("scan_id").orEmpty()
        AlertDialog(
            onDismissRequest = { confirmSync = false },
            title = { Text(stringResource(R.string.peach_cleanup_apply)) },
            text = { Text(stringResource(R.string.peach_cleanup_apply_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSync = false
                    actions.run { sync = resourceSyncApply(scanId) }
                }) { Text(stringResource(R.string.peach_cleanup_apply), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmSync = false }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
    if (confirmPrune) {
        val checkId = linkCheck?.peachString("check_id").orEmpty()
        AlertDialog(
            onDismissRequest = { confirmPrune = false },
            title = { Text(stringResource(R.string.peach_cleanup_links_prune)) },
            text = { Text(stringResource(R.string.peach_cleanup_links_prune_confirm, linkCheck?.peachObjects("gone")?.size ?: 0)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPrune = false
                    actions.run(onDone = links::reload) { linkCheck = pruneLinks(checkId) }
                }) { Text(stringResource(R.string.peach_cleanup_links_prune), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmPrune = false }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
}

private const val TOOL_POLL_MS = 4_000L

// endregion

// region 统计与口味

@Composable
internal fun PeachStatsPage(onBack: () -> Unit) {
    val stats = rememberPeachLoad { stats() }
    PeachPageScaffold(stringResource(R.string.peach_stats_title), onBack, onRefresh = stats::reload) {
        peachLoadItems(stats) { data ->
            data.peachObject("consumption")?.let { c ->
                item {
                    PeachSection(stringResource(R.string.peach_stats_consumption)) {
                        PeachRow(stringResource(R.string.peach_stats_played), value = c.peachInt("played")?.toString())
                        PeachDivider()
                        PeachRow(stringResource(R.string.peach_stats_play_time), value = formatDuration(c.peachDouble("play_seconds")))
                        PeachDivider()
                        PeachRow(stringResource(R.string.peach_stats_liked), value = c.peachInt("liked")?.toString())
                        PeachDivider()
                        PeachRow("O", value = c.peachInt("o_total")?.toString())
                        PeachDivider()
                        PeachRow(stringResource(R.string.peach_trash_title), value = c.peachInt("trash")?.toString())
                    }
                }
            }
            data.peachObject("attribution")?.let { a ->
                item {
                    PeachSection(stringResource(R.string.peach_stats_attribution)) {
                        val total = a.peachInt("videos") ?: 0
                        listOf("creator", "code", "studio", "thumb", "duration").forEachIndexed { index, key ->
                            if (index > 0) PeachDivider()
                            PeachRow(key, value = "${a.peachInt(key) ?: 0} / $total")
                        }
                    }
                }
            }
            listOf(
                Triple("by_library", R.string.peach_stats_by_library, "name"),
                Triple("by_quality", R.string.peach_stats_by_quality, "k"),
                Triple("by_length", R.string.peach_stats_by_length, "k"),
                Triple("by_loc", R.string.peach_stats_by_location, "k"),
                Triple("top_tags", R.string.peach_stats_top_tags, "k")
            ).forEach { (key, label, nameKey) ->
                val rows = data.peachObjects(key)
                if (rows.isNotEmpty()) {
                    item {
                        PeachSection(stringResource(label)) {
                            rows.take(30).forEachIndexed { index, row ->
                                if (index > 0) PeachDivider()
                                PeachRow(
                                    row.peachString(nameKey) ?: row.peachString("k").orEmpty(),
                                    value = listOfNotNull(
                                        (row.peachInt("videos") ?: row.peachInt("n"))?.toString(),
                                        row.peachLong("bytes")?.let(::formatBytes)
                                    ).joinToString(" · ")
                                )
                            }
                        }
                    }
                }
            }
            data.peachObject("storage_summary")?.let { storage ->
                item {
                    PeachSection(stringResource(R.string.peach_configuration_storage)) {
                        PeachRow(
                            stringResource(R.string.peach_configuration_free_space),
                            value = listOfNotNull(storage.peachLong("free")?.let(::formatBytes), storage.peachLong("total")?.let(::formatBytes)).joinToString(" / ")
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Double?): String? {
    val total = seconds?.toLong() ?: return null
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

@Composable
internal fun PeachTastePage(onBack: () -> Unit) {
    var window by remember { mutableStateOf("all") }
    val taste = rememberPeachLoadKeyed(window) { taste(window) }
    PeachPageScaffold(stringResource(R.string.peach_taste_title), onBack, onRefresh = taste::reload) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to R.string.peach_taste_all, "90" to R.string.peach_taste_90, "30" to R.string.peach_taste_30).forEach { (key, label) ->
                    FilterChip(selected = window == key, onClick = { window = key }, label = { Text(stringResource(label)) })
                }
            }
        }
        peachLoadItems(taste) { data ->
            data.peachString("error")?.let { item { PeachHint(it) } }
            data.peachObject("summary")?.let { summary ->
                item {
                    PeachSection {
                        PeachRow(stringResource(R.string.peach_taste_items), value = summary.peachInt("peach_items")?.toString())
                        PeachDivider()
                        PeachRow(stringResource(R.string.peach_stats_play_time), value = formatDuration(summary.peachDouble("peach_seconds")))
                        PeachDivider()
                        PeachRow(stringResource(R.string.peach_taste_history), value = summary.peachInt("history_visits")?.toString())
                    }
                }
            }
            val rankings = data.peachObject("rankings")
            listOf(
                "peach_tags" to R.string.peach_roster_tags,
                "peach_performers" to R.string.peach_roster_performers,
                "peach_creators" to R.string.peach_roster_creators,
                "peach_studios" to R.string.peach_roster_studios,
                "peach_series" to R.string.peach_roster_series
            ).forEach { (key, label) ->
                val rows = rankings?.peachObjects(key).orEmpty()
                if (rows.isNotEmpty()) {
                    item {
                        PeachSection(stringResource(label)) {
                            rows.forEachIndexed { index, row ->
                                if (index > 0) PeachDivider()
                                val score = row.peachDouble("score") ?: 0.0
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(row.peachString("name").orEmpty(), color = Color.White)
                                    LinearProgressIndicator(
                                        progress = { (score / (rows.first().peachDouble("score") ?: 1.0).coerceAtLeast(0.0001)).toFloat().coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            data.peachObject("analysis")?.let { analysis ->
                analysis.keys.mapNotNull { analysis[it].peachText() }.takeIf { it.isNotEmpty() }?.let { lines ->
                    item { PeachHint(lines.joinToString("\n")) }
                }
            }
        }
    }
}

// endregion
