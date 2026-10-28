package com.vela.data.peach

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * 给界面层读取低频、结构多变的 Peach 返回体（诊断、统计、复核行、配置分区等）用的公开读取函数。
 * 与模块内的宽松读取规则一致：类型不符返回 null。
 */

fun JsonObject.peachString(key: String): String? = string(key)

fun JsonObject.peachInt(key: String): Int? = int(key)

fun JsonObject.peachLong(key: String): Long? = long(key)

fun JsonObject.peachDouble(key: String): Double? = double(key)

fun JsonObject.peachBool(key: String): Boolean? = bool(key)

fun JsonObject.peachObject(key: String): JsonObject? = obj(key)

fun JsonObject.peachObjects(key: String): List<JsonObject> = objects(key)

fun JsonObject.peachStrings(key: String, objectKey: String = "name"): List<String> = strings(key, objectKey)

/** 任意值的展示文本；对象与数组返回 null。 */
fun JsonElement?.peachText(): String? = displayText()
