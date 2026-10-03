package com.vela.app.ui.screens.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import com.vela.data.model.BaseItemDto
import com.vela.data.model.ExternalIdInfo
import com.vela.data.repository.MediaRepository
import com.vela.shared.R
import com.vela.shared.playback.UserDataRefreshSignals
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private val IsoDate = Regex("""\d{4}-\d{2}-\d{2}""")

/** 编辑表单的文本状态；数字、日期、列表都以文本编辑，保存时再校验转换。 */
private data class MetadataForm(
    val name: String = "",
    val originalTitle: String = "",
    val sortName: String = "",
    val overview: String = "",
    val year: String = "",
    val premiereDate: String = "",
    val communityRating: String = "",
    val criticRating: String = "",
    val officialRating: String = "",
    val genres: String = "",
    val tags: String = "",
    val studios: String = "",
    val lockData: Boolean = false
)

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } .orEmpty()

private fun JsonObject.studioNames(): List<String> =
    (this["Studios"] as? JsonArray)?.mapNotNull { element ->
        (element as? JsonObject)?.string("Name")?.takeIf { it.isNotBlank() }
    }.orEmpty()

private fun JsonObject.toForm(): MetadataForm = MetadataForm(
    name = string("Name"),
    originalTitle = string("OriginalTitle"),
    sortName = string("ForcedSortName"),
    overview = string("Overview"),
    year = string("ProductionYear"),
    premiereDate = string("PremiereDate").take(10),
    communityRating = string("CommunityRating"),
    criticRating = string("CriticRating"),
    officialRating = string("OfficialRating"),
    genres = stringList("Genres").joinToString(", "),
    tags = stringList("Tags").joinToString(", "),
    studios = studioNames().joinToString(", "),
    lockData = (this["LockData"] as? JsonPrimitive)?.booleanOrNull == true
)

private fun splitList(text: String): List<String> =
    text.split(',', '，', '、', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

private fun textOrNull(text: String) = text.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull

/**
 * 把表单写回原始 JSON。只改表单涉及的键，其余字段（人员、锁定字段等）原样保留，
 * 避免用不完整的模型回写导致服务端清空数据。
 *
 * @return 校验失败时返回 null
 */
private fun MetadataForm.applyTo(
    original: JsonObject,
    providerIds: Map<String, String>
): JsonObject? {
    val year = year.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return null }
    val community = communityRating.trim().takeIf { it.isNotEmpty() }?.let { it.toFloatOrNull() ?: return null }
    val critic = criticRating.trim().takeIf { it.isNotEmpty() }?.let { it.toFloatOrNull() ?: return null }
    val date = premiereDate.trim().takeIf { it.isNotEmpty() }?.let {
        if (!IsoDate.matches(it)) return null
        "${it}T00:00:00.000Z"
    }
    // 工作室保留原有对象（带 Id），新名字补成只有 Name 的对象。
    val existingStudios = (original["Studios"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.associateBy { it.string("Name") }
        .orEmpty()
    val studios = splitList(studios).map { name ->
        existingStudios[name] ?: JsonObject(mapOf("Name" to JsonPrimitive(name)))
    }
    val updated = original.toMutableMap()
    updated["Name"] = JsonPrimitive(name.trim())
    updated["OriginalTitle"] = textOrNull(originalTitle)
    updated["ForcedSortName"] = textOrNull(sortName)
    updated["Overview"] = textOrNull(overview)
    updated["ProductionYear"] = year?.let(::JsonPrimitive) ?: JsonNull
    updated["PremiereDate"] = date?.let(::JsonPrimitive) ?: JsonNull
    updated["CommunityRating"] = community?.let(::JsonPrimitive) ?: JsonNull
    updated["CriticRating"] = critic?.let(::JsonPrimitive) ?: JsonNull
    updated["OfficialRating"] = textOrNull(officialRating)
    updated["Genres"] = JsonArray(splitList(genres).map(::JsonPrimitive))
    updated["Tags"] = JsonArray(splitList(tags).map(::JsonPrimitive))
    updated["Studios"] = JsonArray(studios)
    updated["ProviderIds"] = JsonObject(
        providerIds.mapValues { it.value.trim() }
            .filterValues { it.isNotEmpty() }
            .mapValues { JsonPrimitive(it.value) }
    )
    updated["LockData"] = JsonPrimitive(lockData)
    return JsonObject(updated)
}

/**
 * 编辑元数据：标题、简介、年份、评分、类型、标签、工作室、外部 ID、锁定。
 * 以条目的原始 JSON 为底稿整份回写（`POST Items/{Id}`），Emby 与 Jellyfin 通用。
 */
@Composable
fun EditMetadataSheet(
    item: BaseItemDto,
    mediaRepository: MediaRepository,
    onDismiss: () -> Unit
) {
    val itemId = item.id?.takeIf { it.isNotBlank() } ?: return
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var original by remember { mutableStateOf<JsonObject?>(null) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }
    var form by remember { mutableStateOf(MetadataForm()) }
    var externalIds by remember { mutableStateOf<List<ExternalIdInfo>>(emptyList()) }
    val providerIds = remember { mutableStateMapOf<String, String>() }
    var saving by remember { mutableStateOf(false) }
    val invalidMessage = stringResource(R.string.metadata_edit_invalid)
    val failedTemplate = stringResource(R.string.metadata_error_generic, "%s")

    LaunchedEffect(itemId) {
        mediaRepository.getItemJson(itemId)
            .onSuccess { json ->
                original = json
                form = json.toForm()
                providerIds.clear()
                (json["ProviderIds"] as? JsonObject)?.forEach { (key, value) ->
                    (value as? JsonPrimitive)?.contentOrNull?.let { providerIds[key] = it }
                }
            }
            .onFailure { loadError = it }
        mediaRepository.getExternalIdInfos(itemId).onSuccess { infos ->
            externalIds = infos.filter { !it.key.isNullOrBlank() }.distinctBy { it.key!!.lowercase() }
        }
    }

    fun save() {
        val base = original ?: return
        val payload = form.applyTo(base, providerIds.toMap())
        if (payload == null || form.name.isBlank()) {
            scope.launch { snackbar.showSnackbar(invalidMessage) }
            return
        }
        saving = true
        scope.launch {
            mediaRepository.updateItemJson(itemId, payload)
                .onSuccess {
                    UserDataRefreshSignals.notifyUserDataChanged(itemId)
                    onDismiss()
                }
                .onFailure { snackbar.showSnackbar(failedTemplate.replace("%s", it.message.orEmpty())) }
            saving = false
        }
    }

    MetadataSheetScaffold(
        title = stringResource(R.string.item_action_edit_metadata),
        onDismiss = onDismiss,
        actions = {
            Button(onClick = ::save, enabled = original != null && !saving) {
                if (saving) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text(stringResource(R.string.save))
                }
            }
        }
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when {
                original == null && loadError != null -> CenteredBox { StatusText(errorText(loadError!!)) }
                original == null -> CenteredBox { CircularProgressIndicator() }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(key = "basic") {
                        SectionTitle(stringResource(R.string.metadata_section_basic))
                    }
                    item(key = "name") {
                        FormField(form.name, { form = form.copy(name = it) }, stringResource(R.string.metadata_field_title))
                    }
                    item(key = "original") {
                        FormField(form.originalTitle, { form = form.copy(originalTitle = it) }, stringResource(R.string.metadata_field_original_title))
                    }
                    item(key = "sort") {
                        FormField(form.sortName, { form = form.copy(sortName = it) }, stringResource(R.string.metadata_field_sort_name))
                    }
                    item(key = "overview") {
                        FormField(
                            value = form.overview,
                            onValueChange = { form = form.copy(overview = it) },
                            label = stringResource(R.string.metadata_field_overview),
                            singleLine = false
                        )
                    }
                    item(key = "dates") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FormField(
                                value = form.year,
                                onValueChange = { form = form.copy(year = it.filter(Char::isDigit).take(4)) },
                                label = stringResource(R.string.metadata_field_year),
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(0.8f)
                            )
                            FormField(
                                value = form.premiereDate,
                                onValueChange = { form = form.copy(premiereDate = it.take(10)) },
                                label = stringResource(R.string.metadata_field_premiere_date),
                                placeholder = "yyyy-MM-dd",
                                modifier = Modifier.weight(1.2f)
                            )
                        }
                    }
                    item(key = "ratings") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FormField(
                                value = form.communityRating,
                                onValueChange = { form = form.copy(communityRating = it) },
                                label = stringResource(R.string.metadata_field_community_rating),
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier.weight(1f)
                            )
                            FormField(
                                value = form.criticRating,
                                onValueChange = { form = form.copy(criticRating = it) },
                                label = stringResource(R.string.metadata_field_critic_rating),
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    item(key = "official") {
                        FormField(form.officialRating, { form = form.copy(officialRating = it) }, stringResource(R.string.metadata_field_official_rating))
                    }
                    item(key = "lists") {
                        SectionTitle(
                            stringResource(R.string.metadata_section_classification),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    item(key = "genres") {
                        FormField(form.genres, { form = form.copy(genres = it) }, stringResource(R.string.metadata_field_genres), placeholder = stringResource(R.string.metadata_list_hint))
                    }
                    item(key = "tags") {
                        FormField(form.tags, { form = form.copy(tags = it) }, stringResource(R.string.metadata_field_tags), placeholder = stringResource(R.string.metadata_list_hint))
                    }
                    item(key = "studios") {
                        FormField(form.studios, { form = form.copy(studios = it) }, stringResource(R.string.metadata_field_studios), placeholder = stringResource(R.string.metadata_list_hint))
                    }
                    item(key = "ids") {
                        SectionTitle(
                            stringResource(R.string.metadata_section_external_ids),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    // 先列出服务端声明的外部 ID，再补上条目已有但不在声明里的键。
                    val keys = (externalIds.mapNotNull { it.key } + providerIds.keys)
                        .distinctBy { it.lowercase() }
                    keys.forEach { key ->
                        item(key = "id:$key") {
                            val info = externalIds.firstOrNull { it.key.equals(key, true) }
                            val storedKey = providerIds.keys.firstOrNull { it.equals(key, true) } ?: key
                            FormField(
                                value = providerIds[storedKey].orEmpty(),
                                onValueChange = { providerIds[storedKey] = it },
                                label = info?.name?.takeIf { it.isNotBlank() } ?: key,
                                keyboardType = KeyboardType.Ascii
                            )
                        }
                    }
                    item(key = "lock") {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.item_action_lock_metadata)) },
                            supportingContent = { Text(stringResource(R.string.metadata_lock_summary)) },
                            trailingContent = {
                                Switch(checked = form.lockData, onCheckedChange = { form = form.copy(lockData = it) })
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    singleLine: Boolean = true,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 8,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
    )
}
