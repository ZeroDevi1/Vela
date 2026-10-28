package com.vela.app.ui.screens.peach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vela.data.peach.PeachRepository
import com.vela.data.peach.peachBool
import com.vela.data.peach.peachInt
import com.vela.data.peach.peachObject
import com.vela.data.peach.peachObjects
import com.vela.data.peach.peachString
import com.vela.data.peach.peachStrings
import com.vela.shared.R
import kotlinx.serialization.json.JsonObject

// region 按名字订阅女优

/** 按名字在 JavDB 查演员候选，勾选后订阅其新作（服务端自行拼来源地址）。 */
@Composable
internal fun PeachFeedLookupPage(onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JsonObject?>(null) }
    val chosen = remember { mutableStateListOf<String>() }
    val actions = rememberPeachActions()
    val doneText = stringResource(R.string.peach_feed_followed)
    PeachPageScaffold(stringResource(R.string.peach_feed_lookup_title), onBack) {
        item { PeachHint(stringResource(R.string.peach_feed_lookup_hint)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.peach_name)) }, modifier = Modifier.weight(1f))
                TextButton(enabled = name.isNotBlank() && !actions.isBusy, onClick = {
                    chosen.clear()
                    actions.run {
                        val lookup = lookupFeed(name.trim())
                        result = lookup
                        chosen.addAll(lookup.peachStrings("suggested"))
                    }
                }) { Text(stringResource(R.string.peach_search)) }
            }
        }
        result?.let { lookup ->
            lookup.peachObject("known")?.let { known ->
                item { PeachHint(stringResource(R.string.peach_feed_lookup_known, known.peachString("name").orEmpty())) }
            }
            item {
                PeachSection {
                    PeachRows(lookup.peachObjects("candidates"), stringResource(R.string.peach_empty)) { candidate ->
                        val id = candidate.peachString("id") ?: return@PeachRows
                        PeachRow(
                            title = candidate.peachStrings("names").joinToString(" / ").ifBlank { id },
                            subtitle = candidate.peachObject("held_by")?.peachString("name")?.let { stringResource(R.string.peach_feed_lookup_held, it) },
                            onClick = { if (id in chosen) chosen.remove(id) else chosen.add(id) },
                            trailing = { Checkbox(checked = id in chosen, onCheckedChange = { if (it) chosen.add(id) else chosen.remove(id) }) }
                        )
                    }
                }
            }
            item {
                Button(enabled = chosen.isNotEmpty() && !actions.isBusy, onClick = {
                    val ids = chosen.toList()
                    actions.run(successMessage = doneText, onDone = { result = null; chosen.clear() }) { followFeedByName(name.trim(), ids) }
                }) { Text(stringResource(R.string.peach_follow_performer)) }
            }
        }
    }
}

// endregion

// region 批量添加关注

/** 一次粘贴多行链接或名字，解析出可关注的来源，勾选后逐个登记。 */
@Composable
internal fun PeachFollowResolvePage(onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    val chosen = remember { mutableStateListOf<String>() }
    val actions = rememberPeachActions()
    val doneText = stringResource(R.string.peach_follow_added)
    PeachPageScaffold(stringResource(R.string.peach_follow_resolve_title), onBack) {
        item { PeachHint(stringResource(R.string.peach_follow_resolve_hint)) }
        item {
            OutlinedTextField(text, { text = it }, minLines = 4, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.peach_follow_resolve_input)) })
        }
        item {
            TextButton(enabled = text.isNotBlank() && !actions.isBusy, onClick = {
                chosen.clear()
                actions.run {
                    results = resolveFollowLines(text.lines().map { it.trim() }.filter { it.isNotEmpty() }).peachObjects("results")
                }
            }) { Text(stringResource(R.string.peach_follow_resolve_parse)) }
        }
        results.forEach { line ->
            item {
                PeachSection(line.peachString("line")) {
                    line.peachString("error")?.let { PeachRow(it) }
                    PeachRows(line.peachObjects("candidates"), stringResource(R.string.peach_empty)) { candidate ->
                        val url = candidate.peachString("url") ?: return@PeachRows
                        PeachRow(
                            title = listOfNotNull(candidate.peachString("label"), candidate.peachString("author")).joinToString(" · "),
                            subtitle = listOfNotNull(candidate.peachString("provider_label"), url).joinToString(" · "),
                            value = if (candidate.peachBool("known") == true) stringResource(R.string.peach_follow_known) else null,
                            onClick = { if (url in chosen) chosen.remove(url) else chosen.add(url) },
                            trailing = {
                                Checkbox(
                                    checked = url in chosen,
                                    enabled = candidate.peachBool("known") != true,
                                    onCheckedChange = { if (it) chosen.add(url) else chosen.remove(url) }
                                )
                            }
                        )
                    }
                }
            }
        }
        if (chosen.isNotEmpty()) {
            item {
                Button(enabled = !actions.isBusy, onClick = {
                    val urls = chosen.toList()
                    actions.run(successMessage = doneText, onDone = { chosen.clear(); results = emptyList(); text = "" }) {
                        urls.forEach { addFollowSource(it) }
                    }
                }) { Text(stringResource(R.string.peach_follow_add_selected, chosen.size)) }
            }
        }
    }
}

// endregion

// region 作者与别名

/** 关注作者：同一人在不同站点的名字合并为一个规范名。 */
@Composable
internal fun PeachFollowAuthorsPage(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    val authors = rememberPeachLoadKeyed(applied) { followAuthors(applied) }
    val actions = rememberPeachActions()
    var editing by remember { mutableStateOf<String?>(null) }
    PeachPageScaffold(stringResource(R.string.peach_follow_authors_title), onBack, onRefresh = authors::reload) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.peach_search)) }, modifier = Modifier.weight(1f))
                TextButton(onClick = { applied = query.trim() }) { Text(stringResource(R.string.peach_search)) }
            }
        }
        peachLoadItems(authors) { data ->
            item {
                PeachSection {
                    PeachRows(data.peachObjects("items"), stringResource(R.string.peach_empty)) { author ->
                        val name = author.peachString("k") ?: return@PeachRows
                        PeachRow(
                            title = name,
                            subtitle = author.peachStrings("providers").joinToString(" · "),
                            value = author.peachInt("n")?.toString(),
                            onClick = { editing = name }
                        )
                    }
                }
            }
        }
    }
    editing?.let { canonical ->
        var alias by remember(canonical) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(canonical) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.peach_follow_alias_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(alias, { alias = it }, singleLine = true, label = { Text(stringResource(R.string.peach_alias)) })
                }
            },
            confirmButton = {
                TextButton(enabled = alias.isNotBlank() && !actions.isBusy, onClick = {
                    val value = alias.trim()
                    actions.run(onDone = { editing = null; authors.reload() }) { addAuthorAlias(canonical, value) }
                }) { Text(stringResource(R.string.peach_add)) }
            },
            dismissButton = {
                Row {
                    TextButton(enabled = alias.isNotBlank() && !actions.isBusy, onClick = {
                        val value = alias.trim()
                        actions.run(onDone = { editing = null; authors.reload() }) { removeAuthorAlias(value) }
                    }) { Text(stringResource(R.string.peach_remove)) }
                    TextButton(onClick = { editing = null }) { Text(stringResource(R.string.peach_cancel)) }
                }
            }
        )
    }
}

// endregion

// region 实体改名与别名

/** 实体的别名：可添加、移除自己加的别名，或把某个别名设为规范名。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PeachEntityAliasDialog(kind: String, entityId: Long, onDismiss: () -> Unit, onRenamed: (String) -> Unit) {
    val context = LocalContext.current
    val repository = remember { PeachRepository.getInstance(context) }
    var entity by remember { mutableStateOf<JsonObject?>(null) }
    var alias by remember { mutableStateOf("") }
    val actions = rememberPeachActions()
    LaunchedEffect(entityId) { entity = runCatching { repository.entity(kind, entityId) }.getOrNull() }
    val current = entity
    val name = current?.peachString("canonical_name").orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name.ifBlank { stringResource(R.string.peach_loading) }) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current != null) {
                    val userAliases = current.peachStrings("user_aliases").toSet()
                    Text(stringResource(R.string.peach_entity_aliases), color = Color.White.copy(alpha = 0.7f))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        current.peachStrings("aliases").filter { it != name }.forEach { item ->
                            AssistChip(
                                enabled = !actions.isBusy,
                                onClick = {
                                    actions.run {
                                        renameEntity(kind, name, item)
                                        entity = entity(kind, entityId)
                                        onRenamed(item)
                                    }
                                },
                                label = { Text(item) }
                            )
                        }
                    }
                    Text(stringResource(R.string.peach_entity_rename_hint), style = MaterialTheme.typography.bodySmall)
                    if (userAliases.isNotEmpty()) {
                        Text(stringResource(R.string.peach_entity_user_aliases), color = Color.White.copy(alpha = 0.7f))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            userAliases.forEach { item ->
                                AssistChip(
                                    enabled = !actions.isBusy,
                                    onClick = {
                                        actions.run {
                                            editEntityAlias(kind, name, item, remove = true)
                                            entity = entity(kind, entityId)
                                        }
                                    },
                                    label = { Text("$item ×") }
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(alias, { alias = it }, singleLine = true, label = { Text(stringResource(R.string.peach_alias)) }, modifier = Modifier.weight(1f))
                        TextButton(enabled = alias.isNotBlank() && !actions.isBusy, onClick = {
                            val value = alias.trim()
                            alias = ""
                            actions.run {
                                editEntityAlias(kind, name, value, remove = false)
                                entity = entity(kind, entityId)
                            }
                        }) { Text(stringResource(R.string.peach_add)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.peach_close)) } }
    )
}

// endregion

/** 关注与订阅页底部的工具入口。 */
@Composable
internal fun PeachFollowToolLinks(onOpenSection: (PeachSection) -> Unit) {
    PeachSection(stringResource(R.string.peach_follow_tools)) {
        PeachRow(stringResource(R.string.peach_feed_lookup_title), onClick = { onOpenSection(PeachSection.FEED_LOOKUP) })
        PeachDivider()
        PeachRow(stringResource(R.string.peach_follow_resolve_title), onClick = { onOpenSection(PeachSection.FOLLOW_RESOLVE) })
        PeachDivider()
        PeachRow(stringResource(R.string.peach_follow_authors_title), onClick = { onOpenSection(PeachSection.FOLLOW_AUTHORS) })
    }
}
