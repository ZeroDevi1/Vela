package com.vela.app.util.text

import android.icu.text.AlphabeticIndex
import java.text.Collator
import java.util.Locale

/**
 * 标题首字母索引：汉字按拼音归入 A–Z，拉丁字母按本身归类，其余（数字、符号、假名等）归入 [OTHER_LABEL]。
 *
 * 基于 ICU 中文（拼音）排序规则的 [AlphabeticIndex]（API 24+，minSdk 27 可用；
 * `Transliterator` 需 API 29，因此不用它）。多音字取 ICU 排序规则中的默认读音。
 * [AlphabeticIndex.ImmutableIndex] 线程安全；[Collator] 非线程安全，排序时加锁。
 */
object TitleIndex {
    const val OTHER_LABEL = "#"

    /** 索引栏展示顺序：`#` 在前，随后 A–Z。 */
    val LABELS: List<String> = listOf(OTHER_LABEL) + ('A'..'Z').map(Char::toString)

    private val index: AlphabeticIndex.ImmutableIndex<Any> =
        AlphabeticIndex<Any>(Locale.SIMPLIFIED_CHINESE).buildImmutableIndex()

    private val collator: Collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply {
        strength = Collator.PRIMARY
    }

    /** 返回 [text] 首个有效字符所属的索引标签（[LABELS] 之一）；空白文本返回 [OTHER_LABEL]。 */
    fun labelOf(text: String?): String {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return OTHER_LABEL
        return labelOfChar(trimmed.codePointAt(0)) ?: OTHER_LABEL
    }

    /**
     * 索引栏显示的首字符：拉丁字母转大写，汉字等文字保留原字，数字、符号与空白文本归入 [OTHER_LABEL]。
     * 例如「艾克斯奥特曼」→ `艾`，`matrix` → `M`，`1917` → `#`。
     */
    fun headOf(text: String?): String {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return OTHER_LABEL
        val codePoint = trimmed.codePointAt(0)
        return when {
            codePoint < 0x80 -> codePoint.toChar().takeIf(Char::isLetter)?.uppercaseChar()?.toString() ?: OTHER_LABEL
            Character.isLetter(codePoint) -> String(Character.toChars(codePoint))
            else -> OTHER_LABEL
        }
    }

    /**
     * 返回 [text] 中每个汉字、字母、数字的首字母串（小写），用于拼音首字母搜索。
     * 例如「艾克斯奥特曼X」→ `aksatmx`；空格与标点被跳过，无法归类的字符（如假名）记为 `#`。
     */
    fun initialsOf(text: String): String = buildString {
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            offset += Character.charCount(codePoint)
            when {
                codePoint < 0x80 && Character.isLetterOrDigit(codePoint) ->
                    append(Character.toLowerCase(codePoint.toChar()))
                Character.isLetterOrDigit(codePoint) ->
                    append(labelOfChar(codePoint)?.lowercase() ?: OTHER_LABEL)
            }
        }
    }

    /** 中文拼音顺序比较，用于同一标签内的排序；跨标签顺序以 [LABELS] 为准，保证同一标签的条目连续。 */
    fun compare(a: String, b: String): Int = synchronized(collator) { collator.compare(a, b) }

    private fun labelOfChar(codePoint: Int): String? {
        if (codePoint < 0x80) {
            val ch = codePoint.toChar()
            return if (ch.isLetter()) ch.uppercaseChar().toString() else null
        }
        val bucket = index.getBucket(index.getBucketIndex(String(Character.toChars(codePoint))))
        return bucket.label.takeIf { bucket.labelType == AlphabeticIndex.Bucket.LabelType.NORMAL && it.length == 1 && it[0] in 'A'..'Z' }
    }
}
