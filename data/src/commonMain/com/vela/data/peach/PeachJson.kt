package com.vela.data.peach

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Peach 的接口服务于它自己的 Web 前端，没有版本契约：同一个字段可能是 SQLite 的 0/1，
 * 也可能是 JSON 布尔；数字可能带小数。这里的读取函数一律宽松：类型不符返回 null，
 * 未知字段忽略，由映射层决定缺省值。
 */

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.objects(key: String): List<JsonObject> =
    array(key)?.mapNotNull { it as? JsonObject }.orEmpty()

internal fun JsonObject.string(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content.takeIf { it.isNotEmpty() }
}

internal fun JsonObject.double(key: String): Double? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.doubleOrNull ?: primitive.booleanOrNull?.let { if (it) 1.0 else 0.0 }
}

internal fun JsonObject.long(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
}

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

/** 兼容 JSON 布尔、SQLite 的 0/1 与字符串 "true"/"1"。 */
internal fun JsonObject.bool(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    primitive.booleanOrNull?.let { return it }
    primitive.doubleOrNull?.let { return it != 0.0 }
    return when (primitive.content.lowercase()) {
        "true", "yes" -> true
        "false", "no", "" -> false
        else -> null
    }
}

/** 字符串数组；元素是对象时取 [objectKey] 字段（如详情页标签 `{k, cat}`）。 */
internal fun JsonObject.strings(key: String, objectKey: String = "name"): List<String> {
    return array(key)?.mapNotNull { element ->
        when (element) {
            is JsonObject -> element.string(objectKey)
            is JsonPrimitive -> if (element is JsonNull) null else element.content.takeIf { it.isNotBlank() }
            else -> null
        }
    }.orEmpty()
}

/** 任意 JSON 值的展示文本：对象与数组不展开。 */
internal fun JsonElement?.displayText(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content.takeIf { it.isNotEmpty() }
    else -> null
}
