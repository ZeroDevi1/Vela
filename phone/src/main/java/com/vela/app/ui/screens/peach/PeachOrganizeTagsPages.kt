package com.vela.app.ui.screens.peach

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vela.data.peach.PeachRepository
import com.vela.data.peach.peachBool
import com.vela.data.peach.peachInt
import com.vela.data.peach.peachObject
import com.vela.data.peach.peachObjects
import com.vela.data.peach.peachString
import com.vela.data.peach.peachStrings
import com.vela.data.peach.peachText
import com.vela.shared.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject

// region 文件整理

/**
 * 文件整理严格按「预览 → 确认 → 执行」进行；执行后可回滚最近一批。
 * 预览用的模板、来源与路径原样用于执行，不在两步之间变化。
 */
@Composable
internal fun PeachOrganizePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { PeachRepository.getInstance(context) }
    val state = rememberPeachLoad { organize() }
    val actions = rememberPeachActions()
    var location by remember { mutableStateOf<String?>(null) }
    var fileTemplate by remember { mutableStateOf("") }
    var dirTemplate by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var example by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<JsonObject?>(null) }
    var confirmApply by remember { mutableStateOf(false) }
    var confirmRollback by remember { mutableStateOf(false) }
    val data = (state.value as? PeachLoad.Ready)?.value
    val running = data?.peachString("status") in setOf("running", "pausing", "stopping")

    LaunchedEffect(data, location) {
        val loc = location ?: return@LaunchedEffect
        val templates = data?.peachObject("templates")?.peachObject(loc) ?: return@LaunchedEffect
        if (fileTemplate.isEmpty()) fileTemplate = templates.peachString("file").orEmpty()
        if (dirTemplate.isEmpty()) dirTemplate = templates.peachString("dir").orEmpty()
    }
    LaunchedEffect(fileTemplate, dirTemplate) {
        if (fileTemplate.isBlank() && dirTemplate.isBlank()) return@LaunchedEffect
        delay(400)
        example = runCatching {
            val result = repository.organizeExample(fileTemplate, dirTemplate)
            result.peachString("example") ?: result.peachString("error")
        }.getOrNull()
        preview = null
    }
    LaunchedEffect(running) {
        while (running && isActive) {
            delay(3_000)
            state.reload()
        }
    }

    PeachPageScaffold(stringResource(R.string.peach_organize_title), onBack, onRefresh = state::reload) {
        item { PeachHint(stringResource(R.string.peach_organize_hint)) }
        peachLoadItems(state) { snapshot ->
            item {
                PeachSection(stringResource(R.string.peach_organize_status)) {
                    PeachRow(
                        title = snapshot.peachString("status") ?: stringResource(R.string.peach_idle),
                        subtitle = listOfNotNull(
                            snapshot.peachString("stage"),
                            snapshot.peachInt("moved")?.let { stringResource(R.string.peach_organize_moved, it) },
                            snapshot.peachInt("failed")?.takeIf { it > 0 }?.let { stringResource(R.string.peach_organize_failed, it) },
                            snapshot.peachInt("restored")?.let { stringResource(R.string.peach_organize_restored, it) },
                            snapshot.peachString("error")?.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                    )
                    if (!snapshot.peachString("last_batch").isNullOrBlank()) {
                        PeachDivider()
                        PeachRow(
                            stringResource(R.string.peach_organize_rollback),
                            subtitle = snapshot.peachString("last_batch"),
                            onClick = if (!running) ({ confirmRollback = true }) else null
                        )
                    }
                }
            }
            item {
                PeachSection(stringResource(R.string.peach_organize_source)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        snapshot.peachObjects("locations").forEach { entry ->
                            val loc = entry.peachString("location") ?: return@forEach
                            FilterChip(
                                selected = location == loc,
                                onClick = {
                                    location = loc
                                    fileTemplate = ""
                                    dirTemplate = ""
                                    path = ""
                                    preview = null
                                },
                                label = { Text(loc) }
                            )
                        }
                    }
                }
            }
            val loc = location
            if (loc != null) {
                item {
                    PeachSection(stringResource(R.string.peach_organize_templates)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                snapshot.peachObjects("presets").forEach { preset ->
                                    TextButton(onClick = {
                                        fileTemplate = preset.peachString("file").orEmpty()
                                        dirTemplate = preset.peachString("dir").orEmpty()
                                    }) { Text(preset.peachString("label").orEmpty()) }
                                }
                            }
                            OutlinedTextField(fileTemplate, { fileTemplate = it }, label = { Text(stringResource(R.string.peach_organize_file_template)) }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(dirTemplate, { dirTemplate = it }, label = { Text(stringResource(R.string.peach_organize_dir_template)) }, modifier = Modifier.fillMaxWidth())
                            val placeholders = snapshot.peachObjects("placeholders").joinToString("  ") { "{${it.peachString("key")}} ${it.peachString("label").orEmpty()}" }
                            if (placeholders.isNotBlank()) PeachHint(placeholders)
                            example?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                snapshot.peachObjects("locations").firstOrNull { it.peachString("location") == loc }
                                    ?.peachStrings("roots")?.forEach { root ->
                                        FilterChip(selected = path == root, onClick = { path = if (path == root) "" else root }, label = { Text(root) })
                                    }
                            }
                            OutlinedTextField(path, { path = it }, label = { Text(stringResource(R.string.peach_organize_path)) }, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = !actions.isBusy && !running, onClick = {
                                    actions.run {
                                        preview = organizePreview(loc, fileTemplate.ifBlank { null }, dirTemplate.ifBlank { null }, path.ifBlank { null })
                                    }
                                }) { Text(stringResource(R.string.peach_organize_preview)) }
                                Button(
                                    enabled = !actions.isBusy && !running && (preview?.peachObject("counts")?.peachInt("change") ?: 0) > 0,
                                    onClick = { confirmApply = true }
                                ) { Text(stringResource(R.string.peach_organize_apply)) }
                            }
                        }
                    }
                }
            }
            preview?.let { result ->
                val counts = result.peachObject("counts")
                item {
                    PeachSection(stringResource(R.string.peach_organize_preview_result)) {
                        PeachRow(
                            stringResource(
                                R.string.peach_organize_counts,
                                counts?.peachInt("total") ?: 0,
                                counts?.peachInt("change") ?: 0,
                                counts?.peachInt("unchanged") ?: 0,
                                counts?.peachInt("skip") ?: 0
                            )
                        )
                        result.peachObject("reasons")?.let { reasons ->
                            reasons.keys.forEach { reason -> PeachRow(reason, value = reasons[reason].peachText()) }
                        }
                    }
                }
                result.peachObjects("rows").forEach { row ->
                    item {
                        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                            Text(row.peachString("current_path").orEmpty(), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                            Text("→ " + row.peachString("target_path").orEmpty(), color = Color.White, style = MaterialTheme.typography.bodySmall)
                            row.peachString("reason")?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (result.peachBool("truncated") == true) item { PeachHint(stringResource(R.string.peach_organize_truncated)) }
            }
        }
    }
    if (confirmApply) {
        val change = preview?.peachObject("counts")?.peachInt("change") ?: 0
        AlertDialog(
            onDismissRequest = { confirmApply = false },
            title = { Text(stringResource(R.string.peach_organize_apply)) },
            text = { Text(stringResource(R.string.peach_organize_apply_confirm, change)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmApply = false
                    val loc = location ?: return@TextButton
                    actions.run(onDone = { preview = null; state.reload() }) {
                        organizeApply(loc, fileTemplate.ifBlank { null }, dirTemplate.ifBlank { null }, path.ifBlank { null })
                    }
                }) { Text(stringResource(R.string.peach_organize_apply), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmApply = false }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
    if (confirmRollback) {
        AlertDialog(
            onDismissRequest = { confirmRollback = false },
            title = { Text(stringResource(R.string.peach_organize_rollback)) },
            text = { Text(stringResource(R.string.peach_organize_rollback_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRollback = false
                    actions.run(onDone = state::reload) { organizeRollback() }
                }) { Text(stringResource(R.string.peach_organize_rollback)) }
            },
            dismissButton = { TextButton(onClick = { confirmRollback = false }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
}

// endregion

// region 标签管理

private data class TagOperation(val operation: String, val name: String? = null, val targetId: Any? = null)

/** 标签改名、合并、隐藏与恢复（在线标签另有屏蔽）。先预览影响范围，确认后凭预览令牌执行。 */
@Composable
internal fun PeachTagManagementPage(onBack: () -> Unit) {
    var scope by remember { mutableStateOf("local") }
    var stateFilter by remember { mutableStateOf("active") }
    var query by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    val reloadKey = remember { mutableStateOf(0) }
    val pager = rememberPeachPager(listOf(scope, stateFilter, applied, reloadKey.value)) { offset ->
        val json = tagManagement(scope, stateFilter, applied, offset)
        json.peachObjects("items") to (json.peachBool("has_more") == true)
    }
    val selected = remember(scope, stateFilter) { mutableStateListOf<Any>() }
    val actions = rememberPeachActions()
    var pending by remember { mutableStateOf<TagOperation?>(null) }
    var previewResult by remember { mutableStateOf<JsonObject?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }

    fun idOf(tag: JsonObject): Any? = if (scope == "local") tag.peachInt("entity_id") else tag.peachString("id")

    fun preview(operation: TagOperation) {
        pending = operation
        actions.run { previewResult = tagPreview(scope, operation.operation, selected.toList(), operation.name, operation.targetId) }
    }

    PeachPageScaffold(stringResource(R.string.peach_tags_title), onBack) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = scope == "local", onClick = { scope = "local"; stateFilter = "active" }, label = { Text(stringResource(R.string.peach_tags_local)) })
                FilterChip(selected = scope == "online", onClick = { scope = "online"; stateFilter = "active" }, label = { Text(stringResource(R.string.peach_tags_online)) })
                listOfNotNull(
                    "active" to R.string.peach_tags_active,
                    "hidden" to R.string.peach_tags_hidden,
                    ("blocked" to R.string.peach_tags_blocked).takeIf { scope == "online" }
                ).forEach { (key, label) ->
                    FilterChip(selected = stateFilter == key, onClick = { stateFilter = key }, label = { Text(stringResource(label)) })
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.peach_search)) },
                trailingIcon = { TextButton(onClick = { applied = query.trim() }) { Text(stringResource(R.string.peach_search)) } },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (selected.isNotEmpty()) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.peach_tags_selected, selected.size), color = Color.White, modifier = Modifier.align(Alignment.CenterVertically))
                    if (selected.size == 1) TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.peach_rename)) }
                    if (selected.size >= 2) TextButton(onClick = { merging = true }) { Text(stringResource(R.string.peach_tags_merge)) }
                    if (stateFilter == "active") TextButton(onClick = { preview(TagOperation("hide")) }) { Text(stringResource(R.string.peach_tags_hide)) }
                    if (stateFilter == "hidden") TextButton(onClick = { preview(TagOperation("restore")) }) { Text(stringResource(R.string.peach_restore)) }
                    if (scope == "online" && stateFilter != "blocked") TextButton(onClick = { preview(TagOperation("block")) }) { Text(stringResource(R.string.peach_tags_block)) }
                    if (scope == "online" && stateFilter == "blocked") TextButton(onClick = { preview(TagOperation("unblock")) }) { Text(stringResource(R.string.peach_tags_unblock)) }
                    TextButton(onClick = { selected.clear() }) { Text(stringResource(R.string.peach_cancel)) }
                }
            }
        }
        item {
            PeachSection {
                PeachRows(pager.items.toList(), if (pager.loading) stringResource(R.string.peach_loading) else stringResource(R.string.peach_empty)) { tag ->
                    val id = idOf(tag) ?: return@PeachRows
                    PeachRow(
                        title = tag.peachString("k").orEmpty(),
                        subtitle = listOfNotNull(tag.peachString("cat"), tag.peachStrings("aliases").takeIf { it.isNotEmpty() }?.joinToString("、")).joinToString(" · "),
                        value = tag.peachInt("n")?.toString(),
                        onClick = { if (id in selected) selected.remove(id) else selected.add(id) },
                        trailing = {
                            Checkbox(checked = id in selected, onCheckedChange = { checked -> if (checked) selected.add(id) else selected.remove(id) })
                        }
                    )
                }
            }
        }
        if (!pager.ended && pager.items.isNotEmpty()) {
            item { TextButton(onClick = pager::loadMore) { Text(stringResource(R.string.peach_next_page)) } }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.peach_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { renaming = false; preview(TagOperation("rename", name = name.trim())) }) {
                    Text(stringResource(R.string.peach_organize_preview))
                }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
    if (merging) {
        AlertDialog(
            onDismissRequest = { merging = false },
            title = { Text(stringResource(R.string.peach_tags_merge_target)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    pager.items.filter { idOf(it) in selected }.forEach { tag ->
                        TextButton(onClick = {
                            merging = false
                            preview(TagOperation("merge", targetId = idOf(tag)))
                        }) { Text(tag.peachString("k").orEmpty()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = false }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
    val operation = pending
    val result = previewResult
    if (operation != null && result != null) {
        AlertDialog(
            onDismissRequest = { pending = null; previewResult = null },
            title = { Text(stringResource(R.string.peach_tags_confirm)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.peach_tags_affects, result.peachInt("assets") ?: 0))
                    Text(result.peachObjects("tags").joinToString("、") { it.peachString("k").orEmpty() }, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val token = result.peachString("token").orEmpty()
                    val ids = selected.toList()
                    pending = null
                    previewResult = null
                    actions.run(onDone = { selected.clear(); reloadKey.value++ }) {
                        tagApply(scope, operation.operation, ids, token, operation.name, operation.targetId)
                    }
                }) { Text(stringResource(R.string.peach_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pending = null; previewResult = null }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
}

// endregion

// region 自定义元数据来源

private val METADATA_FIELDS = listOf("code", "title", "studio", "series", "release_date", "performers", "tags")

@Composable
internal fun PeachMetadataSourcesPage(onBack: () -> Unit) {
    val sources = rememberPeachLoad { customMetadataSources() }
    val actions = rememberPeachActions()
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    PeachPageScaffold(stringResource(R.string.peach_metadata_sources_title), onBack, onRefresh = sources::reload) {
        item { PeachHint(stringResource(R.string.peach_metadata_sources_hint)) }
        item { OutlinedButton(onClick = { creating = true }) { Text(stringResource(R.string.peach_add)) } }
        peachLoadItems(sources) { items ->
            item {
                PeachSection {
                    PeachRows(items, stringResource(R.string.peach_empty)) { source ->
                        PeachRow(
                            title = source.peachString("name").orEmpty(),
                            subtitle = source.peachString("endpoint"),
                            value = stringResource(if (source.peachBool("enabled") == true) R.string.peach_enabled else R.string.peach_disabled),
                            onClick = { editing = source }
                        )
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        PeachMetadataSourceDialog(
            source = editing,
            busy = actions.isBusy,
            onSave = { values ->
                actions.run(onDone = { creating = false; editing = null; sources.reload() }) { saveCustomMetadataSource(values) }
            },
            onDelete = editing?.peachString("id")?.let { id ->
                { actions.run(onDone = { editing = null; sources.reload() }) { deleteCustomMetadataSource(id) } }
            },
            onDismiss = { creating = false; editing = null }
        )
    }
}

@Composable
private fun PeachMetadataSourceDialog(
    source: JsonObject?,
    busy: Boolean,
    onSave: (Map<String, Any?>) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { PeachRepository.getInstance(context) }
    var name by remember { mutableStateOf(source?.peachString("name").orEmpty()) }
    var endpoint by remember { mutableStateOf(source?.peachString("endpoint").orEmpty()) }
    var enabled by remember { mutableStateOf(source?.peachBool("enabled") != false) }
    var viaPeachProxy by remember { mutableStateOf(source?.peachString("network") == "peach_proxy") }
    val fields = remember {
        mutableStateMapOf<String, String>().apply {
            METADATA_FIELDS.forEach { key -> put(key, source?.peachObject("fields")?.peachString(key).orEmpty()) }
        }
    }
    var testCode by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    fun values(): Map<String, Any?> = buildMap {
        source?.peachString("id")?.let { put("id", it) }
        put("name", name.trim())
        put("endpoint", endpoint.trim())
        put("enabled", enabled)
        put("network", if (viaPeachProxy) "peach_proxy" else "direct")
        put("code_format", source?.peachString("code_format") ?: "original")
        put("fields", fields.filterValues { it.isNotBlank() })
    }
    LaunchedEffect(testing) {
        if (!testing) return@LaunchedEffect
        testResult = runCatching {
            val result = repository.previewCustomMetadataSource(testCode.trim(), values())
            result.peachObject("record")?.toString() ?: result.toString()
        }.getOrElse { it.message }
        testing = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.peach_metadata_source)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.peach_name)) }, singleLine = true)
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(R.string.peach_metadata_endpoint)) })
                PeachHint(stringResource(R.string.peach_metadata_fields_hint))
                METADATA_FIELDS.forEach { key ->
                    OutlinedTextField(fields[key].orEmpty(), { fields[key] = it }, label = { Text(key) }, singleLine = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.peach_enabled), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.peach_metadata_via_proxy), modifier = Modifier.weight(1f))
                    Switch(checked = viaPeachProxy, onCheckedChange = { viaPeachProxy = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(testCode, { testCode = it }, label = { Text(stringResource(R.string.peach_want_code)) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = testCode.isNotBlank() && !testing, onClick = { testing = true }) { Text(stringResource(R.string.peach_check)) }
                }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank() && endpoint.contains("{code}"), onClick = { onSave(values()) }) {
                Text(stringResource(R.string.peach_save))
            }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text(stringResource(R.string.peach_remove), color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.peach_cancel)) }
            }
        }
    )
}

// endregion

/** 字段名的通用展示：粗体标题加数值。 */
@Composable
internal fun PeachKeyValue(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
        Text(value ?: "—", color = Color.White, fontWeight = FontWeight.Medium)
    }
}
