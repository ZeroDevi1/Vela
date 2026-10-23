package com.vela.app.util.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 依赖设备上的 ICU 中文排序数据，须在真机或模拟器上运行。 */
@RunWith(AndroidJUnit4::class)
class TitleIndexTest {

    @Test
    fun labelOf_groupsHanByPinyinAndLatinByLetter() {
        assertEquals("A", TitleIndex.labelOf("艾克斯奥特曼"))
        assertEquals("A", TitleIndex.labelOf("爱情公寓"))
        assertEquals("D", TitleIndex.labelOf("大生意人"))
        assertEquals("L", TitleIndex.labelOf("兰香如故"))
        assertEquals("O", TitleIndex.labelOf("欧布奥特曼"))
        assertEquals("M", TitleIndex.labelOf("  matrix"))
        assertEquals(TitleIndex.OTHER_LABEL, TitleIndex.labelOf("1917"))
        assertEquals(TitleIndex.OTHER_LABEL, TitleIndex.labelOf(""))
    }

    @Test
    fun initialsOf_skipsPunctuationAndKeepsLatin() {
        assertEquals("aksatm", TitleIndex.initialsOf("艾克斯奥特曼"))
        assertEquals("aqgy3", TitleIndex.initialsOf("爱情公寓 3"))
        assertEquals("aqgylwp", TitleIndex.initialsOf("爱情公寓：辣味篇"))
    }

    @Test
    fun compare_ordersHanByPinyin() {
        assertTrue(TitleIndex.compare("艾克斯奥特曼", "布莱泽奥特曼") < 0)
        assertTrue(TitleIndex.compare("初代奥特曼", "戴拿奥特曼") < 0)
    }
}
