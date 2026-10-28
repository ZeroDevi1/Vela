package com.vela.app.ui.screens.peach

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vela.data.peach.PeachApiException
import com.vela.data.peach.formatBytes
import com.vela.data.peach.peachBool
import com.vela.data.peach.peachDouble
import com.vela.data.peach.peachInt
import com.vela.data.peach.peachLong
import com.vela.data.peach.peachObject
import com.vela.data.peach.peachObjects
import com.vela.data.peach.peachString
import com.vela.data.peach.peachStrings
import com.vela.data.peach.peachText
import com.vela.shared.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject

/*
 * 服务器配置。Peach 只允许运行它的电脑（Docker 部署另含私有网段）写配置；其余情况一律 403，
 * 这里按实际响应降级为说明，不假设可写。访问密码、公网隧道、存储清理与迁移、卸载等可能让
 * 本应用断开或依赖主机桌面的操作只读展示。
 */

/** 配置首页：先读一次 `/api/configuration` 判断可写性，再列出各分区。 */
@Composable
internal fun PeachConfigurationHome(
    onBack: () -> Unit,
    onOpenSection: (PeachSection) -> Unit,
    onOpenWeb: (String) -> Unit
) {
    val configuration = rememberPeachLoad { rawConfiguration() }
    PeachPageScaffold(stringResource(R.string.peach_configuration_title), onBack, onRefresh = configuration::reload) {
        when (val value = configuration.value) {
            PeachLoad.Loading -> item { PeachHint(stringResource(R.string.peach_loading)) }
            is PeachLoad.Failed -> item { PeachConfigurationUnavailable(value.error, onOpenWeb) }
            is PeachLoad.Ready -> {
                val config = value.value
                item {
                    PeachSection {
                        PeachRow(
                            stringResource(R.string.peach_configuration_editable),
                            subtitle = config.peachString("notice"),
                            value = stringResource(if (config.peachBool("editable") == true) R.string.peach_yes else R.string.peach_no)
                        )
                        if (config.peachBool("ledger_read_only") == true) {
                            PeachDivider()
                            PeachRow(stringResource(R.string.peach_read_only_title))
                        }
                    }
                }
                config.peachObject("section_errors")?.let { errors ->
                    if (errors.isNotEmpty()) {
                        item {
                            PeachSection(stringResource(R.string.peach_configuration_section_errors)) {
                                errors.keys.forEach { key ->
                                    val error = errors.peachObject(key)
                                    PeachRow(error?.peachString("title") ?: key, subtitle = error?.peachString("message"))
                                }
                            }
                        }
                    }
                }
                item {
                    PeachSection {
                        listOf(
                            PeachSection.CONFIG_LIBRARIES to R.string.peach_config_libraries,
                            PeachSection.CONFIG_DOWNLOADS to R.string.peach_config_downloads,
                            PeachSection.CONFIG_INDEXERS to R.string.peach_config_indexers,
                            PeachSection.CONFIG_PROXIES to R.string.peach_config_proxies,
                            PeachSection.CONFIG_UPDATES to R.string.peach_configuration_updates,
                            PeachSection.CONFIG_STORAGE to R.string.peach_configuration_storage,
                            PeachSection.CONFIG_NETWORK to R.string.peach_config_network,
                            PeachSection.CONFIG_PROCESSING to R.string.peach_config_processing,
                            PeachSection.CONFIG_PRIVACY to R.string.peach_config_privacy,
                            PeachSection.CONFIG_DIAGNOSTICS to R.string.peach_config_diagnostics,
                            PeachSection.ISSUES to R.string.peach_config_issues
                        ).forEachIndexed { index, (section, label) ->
                            if (index > 0) PeachDivider()
                            PeachRow(stringResource(label), onClick = { onOpenSection(section) })
                        }
                    }
                }
                item { PeachHint(stringResource(R.string.peach_configuration_hint)) }
            }
        }
    }
}

@Composable
private fun PeachConfigurationUnavailable(error: Throwable, onOpenWeb: (String) -> Unit) {
    val localOnly = (error as? PeachApiException)?.kind == PeachApiException.Kind.LOCAL_ONLY
    if (!localOnly) {
        PeachErrorCard(error, onRetry = null)
        return
    }
    PeachSection {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.peach_configuration_local_only), color = Color.White)
            Text(stringResource(R.string.peach_configuration_local_only_detail), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onOpenWeb("/settings/library") }) { Text(stringResource(R.string.peach_open_web)) }
        }
    }
}

/** 配置分页的公共外壳：加载失败（多为仅本机）时统一降级。 */
@Composable
private fun <T> PeachConfigPage(
    title: String,
    onBack: () -> Unit,
    onOpenWeb: (String) -> Unit,
    load: PeachLoader<T>,
    content: androidx.compose.foundation.lazy.LazyListScope.(T) -> Unit
) {
    PeachPageScaffold(title, onBack, onRefresh = load::reload) {
        when (val value = load.value) {
            PeachLoad.Loading -> item { PeachHint(stringResource(R.string.peach_loading)) }
            is PeachLoad.Failed -> item { PeachConfigurationUnavailable(value.error, onOpenWeb) }
            is PeachLoad.Ready -> content(value.value)
        }
    }
}

private fun JsonObject.primitiveMap(): MutableMap<String, Any?> =
    keys.associateWithTo(mutableMapOf()) { key ->
        val element = this[key]
        when {
            element is kotlinx.serialization.json.JsonPrimitive && element.isString -> element.content
            element is kotlinx.serialization.json.JsonPrimitive -> peachBool(key) ?: peachDouble(key)?.let { if (it % 1.0 == 0.0) it.toLong() else it }
            else -> null
        }
    }.filterValues { it != null }.toMutableMap()

// region 媒体库与根目录

private val MEDIA_LOCATIONS = listOf("local", "115", "pikpak", "guangya", "onedrive", "mounted")
private const val SOURCE_KEY_FIELDS = "location,path,root,root_id,library_id,library,library_icon,library_color,library_policy,library_stages,cost,transport,smb_username,smb_encrypt"

@Composable
internal fun PeachLibrariesConfigPage(onBack: () -> Unit, onOpenWeb: (String) -> Unit) {
    val configuration = rememberPeachLoad { rawConfiguration() }
    val actions = rememberPeachActions()
    val rows = remember { mutableStateListOf<MutableMap<String, Any?>>() }
    var revision by remember { mutableStateOf<String?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(configuration.value) {
        val config = (configuration.value as? PeachLoad.Ready)?.value ?: return@LaunchedEffect
        revision = config.peachString("revision")
        rows.clear()
        val keep = SOURCE_KEY_FIELDS.split(',').toSet()
        config.peachObjects("media_sources").forEach { source -> rows += source.primitiveMap().filterKeys { it in keep }.toMutableMap() }
    }
    PeachConfigPage(stringResource(R.string.peach_config_libraries), onBack, onOpenWeb, configuration) { config ->
        val editable = config.peachBool("editable") == true
        if (!editable) item { PeachHint(stringResource(R.string.peach_config_read_only)) }
        item {
            PeachSection {
                PeachRows(rows.toList(), stringResource(R.string.peach_empty)) { row ->
                    val index = rows.indexOf(row)
                    PeachRow(
                        title = (row["library"] as? String) ?: (row["path"] as? String).orEmpty(),
                        subtitle = listOfNotNull(row["location"] as? String, row["path"] as? String, row["cost"] as? String).joinToString(" · "),
                        onClick = if (editable) ({ editingIndex = index }) else null
                    )
                }
            }
        }
        errors?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (editable) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.peach_config_add_folder)) }
                    Button(enabled = !actions.isBusy && revision != null, onClick = {
                        val rev = revision ?: return@Button
                        errors = null
                        actions.run(onDone = configuration::reload) {
                            runCatching { saveMediaSources(rev, rows.map { it.toMap() }) }
                                .onFailure { error -> errors = error.message; throw error }
                        }
                    }) { Text(stringResource(R.string.peach_save)) }
                }
            }
            item { PeachHint(stringResource(R.string.peach_config_libraries_hint)) }
        }
    }
    editingIndex?.let { index ->
        PeachMediaSourceDialog(
            initial = rows.getOrNull(index) ?: return@let,
            libraries = rows.mapNotNull { (it["library_id"] as? String)?.let { id -> id to (it["library"] as? String ?: id) } }.distinct(),
            onSave = { updated ->
                // 同一媒体库的名称与图标必须一致：改名时同步到同库的其他目录。
                val libraryId = updated["library_id"]
                rows.forEachIndexed { i, other ->
                    if (i != index && libraryId != null && other["library_id"] == libraryId) {
                        other["library"] = updated["library"]
                        other["library_icon"] = updated["library_icon"]
                    }
                }
                rows[index] = updated
                editingIndex = null
            },
            onRemove = { rows.removeAt(index); editingIndex = null },
            onDismiss = { editingIndex = null }
        )
    }
    if (adding) {
        PeachMediaSourceDialog(
            initial = mutableMapOf("location" to "local", "path" to "", "cost" to "free"),
            libraries = rows.mapNotNull { (it["library_id"] as? String)?.let { id -> id to (it["library"] as? String ?: id) } }.distinct(),
            onSave = { rows.add(it); adding = false },
            onRemove = null,
            onDismiss = { adding = false }
        )
    }
}

@Composable
private fun PeachMediaSourceDialog(
    initial: Map<String, Any?>,
    libraries: List<Pair<String, String>>,
    onSave: (MutableMap<String, Any?>) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val isNew = initial["root_id"] == null
    var location by remember { mutableStateOf(initial["location"] as? String ?: "local") }
    var path by remember { mutableStateOf(initial["path"] as? String ?: "") }
    var library by remember { mutableStateOf(initial["library"] as? String ?: "") }
    var libraryId by remember { mutableStateOf(initial["library_id"] as? String) }
    var metered by remember { mutableStateOf(initial["cost"] == "metered") }
    var smbUser by remember { mutableStateOf(initial["smb_username"] as? String ?: "") }
    var smbPassword by remember { mutableStateOf("") }
    var smbResult by remember { mutableStateOf<String?>(null) }
    val actions = rememberPeachActions()
    val isSmb = path.startsWith("smb://", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.peach_config_add_folder else R.string.peach_config_edit_folder)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MEDIA_LOCATIONS.forEach { loc -> FilterChip(selected = location == loc, onClick = { location = loc }, label = { Text(loc) }) }
                }
                OutlinedTextField(path, { path = it }, label = { Text(stringResource(R.string.peach_config_folder_path)) }, singleLine = true)
                OutlinedTextField(library, { library = it.take(80) }, label = { Text(stringResource(R.string.peach_config_library_name)) }, singleLine = true)
                if (isNew && libraries.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = libraryId == null, onClick = { libraryId = null }, label = { Text(stringResource(R.string.peach_config_new_library)) })
                        libraries.forEach { (id, name) ->
                            FilterChip(selected = libraryId == id, onClick = { libraryId = id; library = name }, label = { Text(name) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.peach_config_metered), modifier = Modifier.weight(1f))
                    Switch(checked = metered, onCheckedChange = { metered = it })
                }
                if (isSmb) {
                    OutlinedTextField(smbUser, { smbUser = it }, label = { Text(stringResource(R.string.peach_config_smb_user)) }, singleLine = true)
                    OutlinedTextField(
                        smbPassword,
                        { smbPassword = it },
                        label = { Text(stringResource(R.string.peach_config_smb_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    TextButton(enabled = !actions.isBusy, onClick = {
                        actions.run {
                            val result = testSmb(path, smbUser.ifBlank { null }, smbPassword.ifBlank { null }, encrypt = false, rootId = initial["root_id"] as? String)
                            smbResult = result.peachStrings("directories").take(20).joinToString("\n").ifBlank { result.toString() }
                        }
                    }) { Text(stringResource(R.string.peach_check)) }
                    smbResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (!isNew && path != initial["path"]) PeachHint(stringResource(R.string.peach_config_relocate_hint))
            }
        },
        confirmButton = {
            TextButton(enabled = path.isNotBlank(), onClick = {
                val updated = initial.toMutableMap()
                updated["location"] = location
                updated["path"] = path.trim()
                updated["library"] = library.trim().ifBlank { null }
                updated["cost"] = if (metered) "metered" else "free"
                if (isNew) {
                    updated.remove("root_id")
                    if (libraryId != null) updated["library_id"] = libraryId else updated.remove("library_id")
                } else if (path != initial["path"]) {
                    updated["change_mode"] = "relocate"
                }
                if (isSmb) {
                    updated["transport"] = "smb"
                    updated["smb_username"] = smbUser.ifBlank { null }
                    if (smbPassword.isNotBlank()) updated["smb_password"] = smbPassword
                }
                onSave(updated.filterValues { it != null }.toMutableMap())
            }) { Text(stringResource(R.string.peach_save)) }
        },
        dismissButton = {
            Row {
                onRemove?.let { TextButton(onClick = it) { Text(stringResource(R.string.peach_remove), color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.peach_cancel)) }
            }
        }
    )
}

// endregion

// region 下载渠道

@Composable
internal fun PeachDownloadsConfigPage(onBack: () -> Unit, onOpenWeb: (String) -> Unit) {
    val configuration = rememberPeachLoad { rawConfiguration().peachObject("downloads") ?: JsonObject(emptyMap()) }
    val actions = rememberPeachActions()
    var address by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf("") }
    var pikpakRoot by remember { mutableStateOf<String?>(null) }
    var waitHours by remember { mutableStateOf<String?>(null) }
    val targets = remember { mutableStateMapOf<String, String>() }
    var report by remember { mutableStateOf<JsonObject?>(null) }
    var pikpakUser by remember { mutableStateOf("") }
    var pikpakPassword by remember { mutableStateOf("") }
    var pikpakMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(configuration.value) {
        val config = (configuration.value as? PeachLoad.Ready)?.value?.peachObject("config") ?: return@LaunchedEffect
        address = config.peachString("clouddrive_address").orEmpty()
        pikpakRoot = config.peachString("pikpak_root").orEmpty()
        waitHours = config.peachInt("wait_hours")?.toString().orEmpty()
        targets.clear()
        config.peachObject("targets")?.let { saved -> saved.keys.forEach { key -> targets[key] = saved[key].peachText().orEmpty() } }
    }
    PeachConfigPage(stringResource(R.string.peach_config_downloads), onBack, onOpenWeb, configuration) { downloads ->
        val locations = downloads.peachStrings("media_sources")
        downloads.peachStrings("problems").takeIf { it.isNotEmpty() }?.let { problems ->
            item {
                PeachSection(stringResource(R.string.peach_downloads_channels)) { problems.forEach { PeachRow(it) } }
            }
        }
        item {
            PeachSection("CloudDrive2 · 115") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(address.orEmpty(), { address = it }, label = { Text(stringResource(R.string.peach_config_clouddrive_address)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        token,
                        { token = it },
                        label = {
                            Text(stringResource(if (downloads.peachBool("token_set") == true) R.string.peach_config_token_saved else R.string.peach_config_token))
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    locations.forEach { location ->
                        OutlinedTextField(
                            targets[location].orEmpty(),
                            { targets[location] = it },
                            label = { Text(stringResource(R.string.peach_config_download_target, location)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
        item {
            PeachSection("PikPak") {
                val pikpak = downloads.peachObject("pikpak")
                PeachRow(
                    stringResource(if (pikpak?.peachBool("logged_in") == true) R.string.peach_config_logged_in else R.string.peach_config_logged_out),
                    subtitle = pikpak?.peachString("username")
                )
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pikpakRoot.orEmpty(), { pikpakRoot = it }, label = { Text(stringResource(R.string.peach_config_pikpak_root)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (pikpak?.peachBool("logged_in") == true) {
                        OutlinedButton(enabled = !actions.isBusy, onClick = { actions.run(onDone = configuration::reload) { pikpakLogout() } }) {
                            Text(stringResource(R.string.peach_config_logout))
                        }
                    } else {
                        OutlinedTextField(pikpakUser, { pikpakUser = it }, label = { Text(stringResource(R.string.peach_config_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            pikpakPassword,
                            { pikpakPassword = it },
                            label = { Text(stringResource(R.string.password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(enabled = !actions.isBusy && pikpakUser.isNotBlank() && pikpakPassword.isNotBlank(), onClick = {
                            val user = pikpakUser
                            val password = pikpakPassword
                            pikpakPassword = ""
                            actions.run(onDone = configuration::reload) {
                                val result = pikpakLogin(user, password, remember = true)
                                pikpakMessage = if (result.peachBool("ok") == true) null
                                else result.peachString("message") ?: result.peachString("captcha_url")
                            }
                        }) { Text(stringResource(R.string.peach_config_login)) }
                        PeachHint(stringResource(R.string.peach_config_pikpak_browser_hint))
                    }
                    pikpakMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            PeachSection {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        waitHours.orEmpty(),
                        { waitHours = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.peach_config_wait_hours)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !actions.isBusy, onClick = {
                            val body = buildMap<String, Any?> {
                                put("clouddrive_address", address.orEmpty())
                                put("targets", targets.toMap())
                                put("pikpak_root", pikpakRoot.orEmpty())
                                put("wait_hours", waitHours?.toIntOrNull()?.coerceIn(1, 1440) ?: 24)
                                if (token.isNotBlank()) put("token", token)
                            }
                            token = ""
                            actions.run(onDone = configuration::reload) { report = saveDownloadSettings(body).peachObject("report") }
                        }) { Text(stringResource(R.string.peach_save)) }
                        OutlinedButton(enabled = !actions.isBusy, onClick = { actions.run { report = checkDownloadSettings() } }) {
                            Text(stringResource(R.string.peach_check))
                        }
                    }
                }
            }
        }
        report?.let { result ->
            item {
                PeachSection(stringResource(R.string.peach_config_check_result)) {
                    PeachRow(stringResource(if (result.peachBool("ok") == true) R.string.peach_config_check_ok else R.string.peach_config_check_failed))
                    result.peachStrings("missing").takeIf { it.isNotEmpty() }?.let { PeachRow(stringResource(R.string.peach_credential_missing, it.joinToString("、"))) }
                    result.peachStrings("problems").forEach { PeachRow(it) }
                    result.peachObject("quota")?.let { quota ->
                        PeachRow(stringResource(R.string.peach_config_quota), value = "${quota.peachLong("used")?.let(::formatBytes)} / ${quota.peachLong("total")?.let(::formatBytes)}")
                    }
                }
            }
        }
    }
}

// endregion

// region 索引器与 CloudDrive 代理

@Composable
internal fun PeachIndexersConfigPage(onBack: () -> Unit, onOpenWeb: (String) -> Unit) {
    val indexers = rememberPeachLoad { indexers() }
    val actions = rememberPeachActions()
    val rows = remember { mutableStateListOf<MutableMap<String, Any?>>() }
    LaunchedEffect(indexers.value) {
        val data = (indexers.value as? PeachLoad.Ready)?.value ?: return@LaunchedEffect
        rows.clear()
        data.peachObjects("indexers").forEach { row -> rows += row.primitiveMap() }
    }
    PeachConfigPage(stringResource(R.string.peach_config_indexers), onBack, onOpenWeb, indexers) { data ->
        val max = data.peachInt("max_indexers") ?: 4
        rows.forEachIndexed { index, row ->
            item(key = "indexer-$index") {
                PeachSection {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        var name by remember(index) { mutableStateOf(row["name"] as? String ?: "") }
                        var url by remember(index) { mutableStateOf(row["url"] as? String ?: "") }
                        var apiKey by remember(index) { mutableStateOf("") }
                        var enabled by remember(index) { mutableStateOf(row["enabled"] as? Boolean ?: true) }
                        OutlinedTextField(name, { name = it; row["name"] = it }, label = { Text(stringResource(R.string.peach_name)) }, singleLine = true)
                        OutlinedTextField(url, { url = it; row["url"] = it }, label = { Text("URL") }, singleLine = true)
                        OutlinedTextField(
                            apiKey,
                            { apiKey = it; if (it.isNotBlank()) row["api_key"] = it else row.remove("api_key") },
                            label = { Text(stringResource(if (row["api_key_set"] == true) R.string.peach_config_token_saved else R.string.peach_config_api_key)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.peach_enabled), modifier = Modifier.weight(1f))
                            Switch(checked = enabled, onCheckedChange = { enabled = it; row["enabled"] = it })
                            TextButton(onClick = { rows.removeAt(index) }) { Text(stringResource(R.string.peach_remove)) }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rows.size < max) {
                    OutlinedButton(onClick = { rows.add(mutableMapOf("key" to "vela${System.currentTimeMillis() % 100000}", "name" to "", "url" to "", "enabled" to true)) }) {
                        Text(stringResource(R.string.peach_add))
                    }
                }
                Button(enabled = !actions.isBusy, onClick = {
                    val body = rows.map { row -> row.filterKeys { it in setOf("key", "name", "url", "enabled", "api_key") } }
                    actions.run(onDone = indexers::reload) { saveIndexers(body) }
                }) { Text(stringResource(R.string.peach_save)) }
            }
        }
    }
}

private val PROXY_MODES = listOf("system", "direct", "http", "socks5")

@Composable
internal fun PeachProxiesConfigPage(onBack: () -> Unit, onOpenWeb: (String) -> Unit) {
    val proxies = rememberPeachLoad { cloudProxies() }
    val actions = rememberPeachActions()
    PeachConfigPage(stringResource(R.string.peach_config_proxies), onBack, onOpenWeb, proxies) { data ->
        if (data.peachBool("configured") == false) item { PeachHint(stringResource(R.string.peach_config_proxies_unconfigured)) }
        data.peachObjects("accounts").forEach { account ->
            val id = account.peachString("id") ?: return@forEach
            item(key = id) {
                PeachSection(listOfNotNull(account.peachString("name"), account.peachString("cloud")).joinToString(" · ")) {
                    listOf("api" to R.string.peach_config_proxy_api, "data" to R.string.peach_config_proxy_data).forEach { (kind, label) ->
                        val proxy = account.peachObject(kind)
                        var mode by remember(id, kind) { mutableStateOf(proxy?.peachString("mode") ?: "system") }
                        var host by remember(id, kind) { mutableStateOf(proxy?.peachString("host").orEmpty()) }
                        var port by remember(id, kind) { mutableStateOf(proxy?.peachInt("port")?.toString().orEmpty()) }
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(label), color = Color.White)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PROXY_MODES.forEach { option -> FilterChip(selected = mode == option, onClick = { mode = option }, label = { Text(option) }) }
                            }
                            if (mode == "http" || mode == "socks5") {
                                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.peach_config_host)) }, singleLine = true)
                                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.peach_config_port)) }, singleLine = true)
                            }
                            TextButton(enabled = !actions.isBusy && account.peachBool("writable") != false, onClick = {
                                val value = mapOf("mode" to mode, "host" to host, "port" to (port.toIntOrNull() ?: 0), "username" to proxy?.peachString("username").orEmpty())
                                actions.run(onDone = proxies::reload) {
                                    saveCloudProxy(id, account.peachString("revision").orEmpty(), if (kind == "api") value else null, if (kind == "data") value else null)
                                }
                            }) { Text(stringResource(R.string.peach_save)) }
                        }
                    }
                }
            }
        }
    }
}

// endregion

// region 更新与存储

@Composable
internal fun PeachUpdatesConfigPage(onBack: () -> Unit, onOpenWeb: (String) -> Unit) {
    val configuration = rememberPeachLoad { rawConfiguration() }
    val actions = rememberPeachActions()
    var release by remember { mutableStateOf<JsonObject?>(null) }
    var job by remember { mutableStateOf<JsonObject?>(null) }
    var confirmRestart by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { com.vela.data.peach.PeachRepository.getInstance(context) }
    val updating = job?.peachString("state") !in setOf(null, "idle", "error")
    LaunchedEffect(configuration.value) {
        (configuration.value as? PeachLoad.Ready)?.value?.peachObject("update_job")?.let { job = it }
    }
    // 更新与重启期间轮询状态；重启时请求会短暂失败，保留上一次的状态继续等。
    LaunchedEffect(updating) {
        while (updating && isActive) {
            delay(3_000)
            job = runCatching { repository.updateStatus() }.getOrNull() ?: job
        }
    }
    PeachConfigPage(stringResource(R.string.peach_configuration_updates), onBack, onOpenWeb, configuration) { config ->
        val updates = release ?: config.peachObject("updates")
        val automatic = config.peachObject("automatic_updates")
        item {
            PeachSection {
                PeachRow(stringResource(R.string.peach_configuration_current_version), value = updates?.peachString("current_version"))
                PeachDivider()
                PeachRow(stringResource(R.string.peach_configuration_latest_version), subtitle = updates?.peachString("message"), value = updates?.peachString("latest_version") ?: updates?.peachString("state"))
                job?.let { status ->
                    PeachDivider()
                    PeachRow(status.peachString("state").orEmpty(), subtitle = status.peachString("message"))
                    status.peachDouble("progress")?.let { progress ->
                        LinearProgressIndicator(progress = { (if (progress > 1) progress / 100 else progress).toFloat() }, modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !actions.isBusy, onClick = { actions.run { release = checkUpdates() } }) { Text(stringResource(R.string.peach_config_check_updates)) }
                if (updates?.peachString("state") == "available") {
                    OutlinedButton(enabled = !actions.isBusy && !updating, onClick = { actions.run { job = installUpdate(restart = false) } }) {
                        Text(stringResource(R.string.peach_config_download_update))
                    }
                    Button(enabled = !actions.isBusy && !updating, onClick = { confirmRestart = true }) { Text(stringResource(R.string.peach_config_update_restart)) }
                }
            }
        }
        automatic?.let { auto ->
            item {
                PeachSection(stringResource(R.string.peach_config_auto_updates)) {
                    val mode = auto.peachString("mode") ?: "off"
                    val interval = auto.peachInt("interval_hours") ?: 24
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("off" to R.string.peach_config_auto_off, "check" to R.string.peach_config_auto_check, "download" to R.string.peach_config_auto_download).forEach { (key, label) ->
                            FilterChip(selected = mode == key, onClick = { actions.run(onDone = configuration::reload) { setAutomaticUpdates(key, interval) } }, label = { Text(stringResource(label)) })
                        }
                    }
                    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(6, 24, 168).forEach { hours ->
                            FilterChip(selected = interval == hours, onClick = { actions.run(onDone = configuration::reload) { setAutomaticUpdates(mode, hours) } }, label = { Text("${hours}h") })
                        }
                    }
                }
            }
        }
    }
    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text(stringResource(R.string.peach_config_update_restart)) },
            text = { Text(stringResource(R.string.peach_config_update_restart_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestart = false
                    actions.run { job = installUpdate(restart = true) }
                }) { Text(stringResource(R.string.peach_config_update_restart)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
}

@Composable
internal fun PeachStorageConfigPage(onBack: () -> Unit, onOpenWeb: (String) -> Unit) {
    val storage = rememberPeachLoad { rawConfiguration().peachObject("storage") ?: JsonObject(emptyMap()) }
    val actions = rememberPeachActions()
    PeachConfigPage(stringResource(R.string.peach_configuration_storage), onBack, onOpenWeb, storage) { data ->
        item {
            PeachSection {
                PeachRow(stringResource(R.string.peach_configuration_data_root), subtitle = data.peachString("data_root"))
                PeachDivider()
                PeachRow(
                    stringResource(R.string.peach_configuration_free_space),
                    value = listOfNotNull(data.peachLong("free_bytes")?.let(::formatBytes), data.peachLong("total_bytes")?.let(::formatBytes)).joinToString(" / ")
                )
            }
        }
        data.peachObject("usage")?.let { usage ->
            item {
                PeachSection(stringResource(R.string.peach_config_storage_usage)) {
                    usage.peachObjects("categories").forEachIndexed { index, category ->
                        if (index > 0) PeachDivider()
                        PeachRow(
                            category.peachString("label").orEmpty(),
                            subtitle = category.peachString("description"),
                            value = category.peachLong("bytes")?.let(::formatBytes)
                        )
                    }
                }
            }
        }
        item {
            OutlinedButton(enabled = !actions.isBusy, onClick = { actions.run(onDone = storage::reload) { measureStorage() } }) {
                Text(stringResource(R.string.peach_config_measure))
            }
        }
        item { PeachHint(stringResource(R.string.peach_config_storage_hint)) }
    }
}

// endregion

// region 网络、处理、隐私、诊断与后台问题

@Composable
internal fun PeachNetworkConfigPage(onBack: () -> Unit, onOpenWeb: (String) -> Unit) {
    val configuration = rememberPeachLoad { rawConfiguration() }
    val actions = rememberPeachActions()
    var status by remember { mutableStateOf<JsonObject?>(null) }
    PeachConfigPage(stringResource(R.string.peach_config_network), onBack, onOpenWeb, configuration) { config ->
        val network = config.peachObject("network")
        val access = config.peachObject("access")
        val tunnel = config.peachObject("tunnel")
        item {
            PeachSection {
                PeachRow(stringResource(R.string.peach_config_access_mode), value = access?.peachString("mode"))
                PeachDivider()
                PeachRow(stringResource(R.string.peach_config_lan_url), subtitle = listOfNotNull(network?.peachString("lan_url"), network?.peachString("ip_url")).joinToString("\n"))
                PeachDivider()
                PeachRow(stringResource(R.string.peach_config_tunnel), subtitle = tunnel?.peachString("url") ?: tunnel?.peachString("error"), value = tunnel?.peachString("state"))
                PeachDivider()
                PeachRow(
                    stringResource(R.string.peach_config_internet),
                    value = (status ?: JsonObject(emptyMap())).peachString("state"),
                    onClick = { actions.run { status = networkStatus(check = true) } }
                )
            }
        }
        config.peachObject("peach_proxy")?.let { proxy ->
            item {
                PeachSection(stringResource(R.string.peach_config_peach_proxy)) {
                    var mode by remember { mutableStateOf(proxy.peachString("mode") ?: "environment") }
                    var address by remember { mutableStateOf("") }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("environment", "direct", "proxy").forEach { option ->
                                FilterChip(selected = mode == option, onClick = { mode = option }, label = { Text(option) })
                            }
                        }
                        if (mode == "proxy") OutlinedTextField(address, { address = it }, label = { Text("http(s)/socks://host:port") }, singleLine = true)
                        TextButton(enabled = !actions.isBusy, onClick = {
                            actions.run(onDone = configuration::reload) { setPeachProxy(mode, address.ifBlank { null }) }
                        }) { Text(stringResource(R.string.peach_save)) }
                    }
                }
            }
        }
        config.peachObject("entry_links")?.peachObjects("sites")?.takeIf { it.isNotEmpty() }?.let { sites ->
            item {
                PeachSection(stringResource(R.string.peach_config_entry_links)) {
                    val hosts = remember { mutableStateMapOf<String, String>().apply { sites.forEach { put(it.peachString("key").orEmpty(), it.peachString("host").orEmpty()) } } }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sites.forEach { site ->
                            val key = site.peachString("key") ?: return@forEach
                            OutlinedTextField(
                                hosts[key].orEmpty(),
                                { hosts[key] = it },
                                label = { Text(site.peachString("label") ?: key) },
                                placeholder = { Text(site.peachString("default_host").orEmpty()) },
                                singleLine = true
                            )
                        }
                        TextButton(enabled = !actions.isBusy, onClick = { actions.run(onDone = configuration::reload) { setEntryLinks(hosts.toMap()) } }) {
                            Text(stringResource(R.string.peach_save))
                        }
                    }
                }
            }
        }
        item { PeachHint(stringResource(R.string.peach_config_network_hint)) }
    }
}

@Composable
internal fun PeachProcessingConfigPage(onBack: () -> Unit, onOpenWeb: (String) -> Unit) {
    val processing = rememberPeachLoad { mediaProcessing() }
    val thumbnails = rememberPeachLoad { thumbnailJobs() }
    val actions = rememberPeachActions()
    PeachConfigPage(stringResource(R.string.peach_config_processing), onBack, onOpenWeb, processing) { data ->
        item {
            PeachSection {
                PeachSwitchRow(
                    stringResource(R.string.peach_config_metadata_only),
                    checked = data.peachBool("metadata_only") == true,
                    enabled = !actions.isBusy
                ) { value -> actions.run(onDone = { processing.reload(); thumbnails.reload() }) { setMediaProcessing(mapOf("metadata_only" to value)) } }
                listOf("preview_acceleration" to R.string.peach_config_preview_accel, "playback_acceleration" to R.string.peach_config_playback_accel).forEach { (key, label) ->
                    PeachDivider()
                    PeachSwitchRow(
                        stringResource(label),
                        checked = data.peachString(key) != "software",
                        enabled = !actions.isBusy
                    ) { value -> actions.run(onDone = processing::reload) { setMediaProcessing(mapOf(key to if (value) "auto" else "software")) } }
                }
            }
        }
        (thumbnails.value as? PeachLoad.Ready)?.value?.let { jobs ->
            item {
                PeachSection(stringResource(R.string.peach_config_thumbnails)) {
                    PeachRow(
                        jobs.peachString("status") ?: stringResource(R.string.peach_idle),
                        subtitle = listOfNotNull(jobs.peachString("message"), jobs.peachInt("done")?.let { "$it/${jobs.peachInt("total") ?: 0}" }).joinToString(" · ")
                    )
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("off" to R.string.peach_config_auto_off, "coarse" to R.string.peach_config_thumb_coarse, "precise" to R.string.peach_config_thumb_precise).forEach { (key, label) ->
                            FilterChip(
                                selected = jobs.peachString("mode") == key,
                                onClick = { actions.run(onDone = thumbnails::reload) { setThumbnailMode(key) } },
                                label = { Text(stringResource(label)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PeachPrivacyConfigPage(onBack: () -> Unit, onOpenWeb: (String) -> Unit) {
    val configuration = rememberPeachLoad { rawConfiguration() }
    val actions = rememberPeachActions()
    var sharingPreview by remember { mutableStateOf<JsonObject?>(null) }
    PeachConfigPage(stringResource(R.string.peach_config_privacy), onBack, onOpenWeb, configuration) { config ->
        config.peachObject("remote_reporting")?.let { reporting ->
            item {
                PeachSection {
                    PeachSwitchRow(
                        stringResource(R.string.peach_config_remote_reporting),
                        subtitle = reporting.peachString("notice"),
                        checked = reporting.peachBool("usage") == true,
                        enabled = !actions.isBusy && reporting.peachBool("available") != false
                    ) { value -> actions.run(onDone = configuration::reload) { setRemoteReporting(value) } }
                }
            }
        }
        config.peachObject("library_sharing")?.let { sharing ->
            item {
                PeachSection {
                    PeachSwitchRow(
                        stringResource(R.string.peach_config_library_sharing),
                        subtitle = sharing.peachString("notice"),
                        checked = sharing.peachBool("enabled") == true,
                        enabled = !actions.isBusy && sharing.peachBool("available") != false
                    ) { value ->
                        if (value) {
                            actions.run { sharingPreview = librarySharingPreview() }
                        } else {
                            actions.run(onDone = configuration::reload) { setLibrarySharing(false, null, sharing.peachString("schema")) }
                        }
                    }
                }
            }
        }
    }
    sharingPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { sharingPreview = null },
            title = { Text(stringResource(R.string.peach_config_library_sharing)) },
            text = {
                Text(
                    stringResource(R.string.peach_config_library_sharing_confirm, formatBytes(preview.peachLong("bytes") ?: 0)) +
                        "\n\n" + (preview["data"]?.toString()?.take(1200) ?: "")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sharingPreview = null
                    actions.run(onDone = configuration::reload) {
                        setLibrarySharing(true, preview.peachString("token"), preview.peachString("schema") ?: preview.peachInt("schema"))
                    }
                }) { Text(stringResource(R.string.peach_confirm)) }
            },
            dismissButton = { TextButton(onClick = { sharingPreview = null }) { Text(stringResource(R.string.peach_cancel)) } }
        )
    }
}

@Composable
internal fun PeachDiagnosticsPage(onBack: () -> Unit, onOpenWeb: (String) -> Unit) {
    val diagnostics = rememberPeachLoad { diagnostics() }
    PeachConfigPage(stringResource(R.string.peach_config_diagnostics), onBack, onOpenWeb, diagnostics) { report ->
        item { PeachHint(listOfNotNull(report.peachString("version"), report.peachString("status")).joinToString(" · ")) }
        report.peachObject("checks")?.let { checks ->
            item {
                PeachSection(stringResource(R.string.peach_config_checks)) {
                    checks.keys.forEachIndexed { index, key ->
                        if (index > 0) PeachDivider()
                        val check = checks.peachObject(key)
                        PeachRow(
                            check?.peachString("label") ?: key,
                            subtitle = listOfNotNull(check?.peachString("reason"), check?.peachString("action")).joinToString("\n"),
                            value = check?.peachString("status")
                        )
                    }
                }
            }
        }
        report.peachObjects("sources").takeIf { it.isNotEmpty() }?.let { sources ->
            item {
                PeachSection(stringResource(R.string.peach_sources_title)) {
                    sources.forEachIndexed { index, source ->
                        if (index > 0) PeachDivider()
                        PeachRow(source.peachString("label") ?: source.peachString("source").orEmpty(), subtitle = source.peachString("reason"), value = source.peachString("status"))
                    }
                }
            }
        }
    }
}

@Composable
internal fun PeachIssuesPage(onBack: () -> Unit, onOpenWeb: (String) -> Unit) {
    val issues = rememberPeachLoad { backgroundIssues() }
    val processing = rememberPeachLoad { libraryProcessing() }
    val actions = rememberPeachActions()
    PeachPageScaffold(stringResource(R.string.peach_config_issues), onBack, onRefresh = { issues.reload(); processing.reload() }) {
        peachLoadItems(issues) { list ->
            item {
                PeachSection(stringResource(R.string.peach_config_background_issues)) {
                    PeachRows(list, stringResource(R.string.peach_empty)) { issue ->
                        PeachRow(
                            issue.peachString("title").orEmpty(),
                            subtitle = issue.peachString("since"),
                            trailing = {
                                Row {
                                    val retry = issue.peachString("retry")
                                    if (retry != null && issue.peachBool("retry_available") == true) {
                                        TextButton(enabled = !actions.isBusy, onClick = { actions.run(onDone = issues::reload) { retryBackgroundIssue(retry) } }) {
                                            Text(stringResource(R.string.peach_retry))
                                        }
                                    }
                                    issue.peachString("href")?.let { href -> TextButton(onClick = { onOpenWeb(href) }) { Text(stringResource(R.string.peach_open)) } }
                                }
                            }
                        )
                    }
                }
            }
        }
        (processing.value as? PeachLoad.Ready)?.value?.let { state ->
            val jobId = state.peachString("job_id")
            if (jobId != null && (state.peachInt("issue_count") ?: 0) > 0) {
                item {
                    val issueRows = rememberPeachLoadKeyed(jobId) { libraryProcessingIssues(jobId) }
                    PeachSection(stringResource(R.string.peach_config_processing_issues, state.peachInt("issue_count") ?: 0)) {
                        when (val value = issueRows.value) {
                            is PeachLoad.Ready -> value.value.peachObjects("rows").forEachIndexed { index, row ->
                                if (index > 0) PeachDivider()
                                PeachRow(row.peachString("title") ?: row.peachString("path").orEmpty(), subtitle = row.peachString("message"), value = row.peachString("severity"))
                            }
                            is PeachLoad.Failed -> PeachRow(value.error.message.orEmpty())
                            PeachLoad.Loading -> PeachRow(stringResource(R.string.peach_loading))
                        }
                    }
                }
            }
        }
    }
}

// endregion
