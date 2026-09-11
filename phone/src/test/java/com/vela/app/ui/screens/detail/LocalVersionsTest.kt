package com.vela.app.ui.screens.detail

import com.vela.data.model.BaseItemDto
import com.vela.data.model.MediaSourceInfo
import com.vela.data.model.MediaStream
import com.vela.data.repository.matchingLocalVersions
import com.vela.shared.ui.components.common.buildInlineText
import com.vela.shared.ui.components.common.buildLocalVersionEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalVersionsTest {
    /** 验证查询候选不能把其它集、季或多集文件作为当前单集版本；断言失败表示身份隔离失效。 */
    @Test
    fun episodeVersionsRequireMatchingSeasonAndEpisodeRange() {
        val item = BaseItemDto(id = "current", type = "Episode", parentIndexNumber = 1,
            indexNumber = 2, providerIds = mapOf("Tvdb" to "42"))
        val candidates = listOf(
            item.copy(id = "same"), item.copy(id = "other-episode", indexNumber = 3),
            item.copy(id = "other-season", parentIndexNumber = 2),
            item.copy(id = "double", indexNumberEnd = 3),
            item.copy(id = "unknown", indexNumber = null)
        )
        assertEquals(listOf("current", "same"), matchingLocalVersions(item, candidates).map { it.id })
    }

    /** 验证不信任有冲突或缺失身份的服务端候选；断言失败表示混入错误影片。 */
    @Test
    fun movieVersionsRejectConflictingOrMissingProviderIds() {
        val item = BaseItemDto(id = "current", type = "Movie",
            providerIds = mapOf("Tmdb" to "42", "Imdb" to "tt1"))
        val candidates = listOf(
            item.copy(id = "same", providerIds = mapOf("TMDB" to "42")),
            item.copy(id = "other-movie", providerIds = mapOf("Tmdb" to "99", "Imdb" to "tt9")),
            item.copy(id = "wrong-type", type = "Episode"),
            item.copy(id = "conflict", providerIds = mapOf("Tmdb" to "42", "Imdb" to "tt2")),
            item.copy(id = "unknown", providerIds = null), item.copy(id = " ")
        )
        assertEquals(listOf("current", "same"), matchingLocalVersions(item, candidates).map { it.id })
    }

    /** 验证源 ID 不变成条目 ID，且当前详情不会被同 ID 的列表摘要覆盖；失败表示身份被改写。 */
    @Test
    fun nativeMediaSourcesAreNotSyntheticItems() {
        val item = BaseItemDto(id = "current", type = "Movie", providerIds = mapOf("Tmdb" to "42"),
            mediaSources = listOf(MediaSourceInfo(id = "mediasource_unrelated")))
        assertEquals(listOf(item), matchingLocalVersions(item, listOf(item.copy(mediaSources = null))))
    }

    /** 验证首个源的标题、大小及缺失码率不串用第二个源；失败表示版本元数据跨文件混合。 */
    @Test
    fun versionMetadataComesFromOneSource() {
        val item = BaseItemDto(id = "current", mediaSources = listOf(
            MediaSourceInfo(size = 1073741824L, mediaStreams = listOf(
                MediaStream(type = "Video", index = 2, displayTitle = "1080p"))),
            MediaSourceInfo(size = 2147483648L, mediaStreams = listOf(
                MediaStream(type = "Video", index = 0, displayTitle = "4K", bitRate = 8000000)))
        ))
        val entries = buildLocalVersionEntries(listOf(item, item.copy(id = "other")), "current", "视频", "< 1 MB")
        assertEquals("1080p / 1.0 GB", entries.first().first)
        assertNull(buildInlineText(listOf(MediaSourceInfo(), item.mediaSources!!.last()),
            listOf(MediaStream(bitRate = 8000000)), "< 1 MB"))
    }
}
