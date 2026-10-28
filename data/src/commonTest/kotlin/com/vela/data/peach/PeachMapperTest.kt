package com.vela.data.peach

import com.vela.data.network.VelaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeachMapperTest {

    private fun json(text: String): JsonObject = VelaJson.parseToJsonElement(text).jsonObject

    /** `/api/items` 列表卡片：SQLite 的 0/1 布尔、带小数的时长与时间戳。 */
    private val listCard = json(
        """
        {"id": 42, "location": "nas", "name": "ABC-123.mp4", "catalog_title": "Sample Title",
         "original_title": null, "medium": "video", "creator": null, "studio": "Studio A",
         "code": "ABC-123", "release_date": "2024-05-06", "size": 1073741824,
         "duration": 3600.5, "width": 1920, "height": 1080, "play_count": 2,
         "first_seen": 1700000000.25, "last_played": 1700003600.0, "leave_ratio": 0.5,
         "feedback": "seen", "disposal": null, "rating": 80, "o_count": 1, "watch_later": 1,
         "tags": ["tag1", "tag2"], "performers": ["Performer A"],
         "performer_entities": [{"id": 7, "name": "Performer A", "has_image": true}],
         "creator_entity": null, "has_cover": true, "cover_key": "ABC-123", "has_thumb": true,
         "region_label": "日本", "unknown_field": {"nested": true},
         "part_group": {"key": "k", "title": "ABC-123", "count": 2, "seed_id": 42, "item_ids": [42, 43]}}
        """
    )

    @Test
    fun parsesListCardLeniently() {
        val asset = PeachAsset.parse(listCard)!!
        assertEquals(42L, asset.id)
        assertEquals("Sample Title", asset.displayTitle)
        assertTrue(asset.watchLater)
        assertTrue(asset.isSeen)
        assertEquals("ABC-123", asset.coverKey)
        assertEquals("mp4", asset.container)
        assertEquals(listOf(42L, 43L), asset.partGroup?.itemIds)
        assertEquals(PeachEntityRef("performer", 7, "Performer A", hasImage = true), asset.entityRefs.single())
    }

    @Test
    fun mapsAssetToBaseItem() {
        val item = PeachMapper.toBaseItem(PeachAsset.parse(listCard)!!)
        assertEquals("42", item.id)
        assertEquals("Movie", item.type)
        assertEquals(36_005_000_000L, item.runTimeTicks)
        assertEquals(18_002_500_000L, item.userData?.playbackPositionTicks)
        assertEquals(true, item.userData?.played)
        assertEquals(true, item.userData?.isFavorite)
        assertEquals(2024, item.productionYear)
        assertEquals("2024-05-06T00:00:00.0000000Z", item.premiereDate)
        assertEquals(8f, item.communityRating)
        assertEquals("ABC-123", item.providerIds?.get(PeachMapper.PROVIDER_CODE))
        assertEquals("performer:7", item.people?.single()?.id)
        assertEquals("c.ABC-123", item.imageTags?.get("Primary"))
        assertEquals(2, item.partCount)
        assertEquals("2023-11-14T22:13:20.0000000Z", item.dateCreated)
    }

    @Test
    fun finishedOrUnstartedAssetsHaveNoResumePosition() {
        val finished = PeachAsset.parse(json("""{"id": 1, "duration": 100, "leave_ratio": 0.99}"""))!!
        val fresh = PeachAsset.parse(json("""{"id": 2, "duration": 100}"""))!!
        assertEquals(0L, PeachMapper.toBaseItem(finished).userData?.playbackPositionTicks)
        assertEquals(0L, PeachMapper.toBaseItem(fresh).userData?.playbackPositionTicks)
        assertEquals("2", PeachMapper.toBaseItem(fresh).name)
    }

    @Test
    fun detailTagsUseKeyField() {
        val detail = PeachAsset.parse(
            json(
                """
                {"id": 5, "tags": [{"k": "剧情", "cat": "story", "official": true}],
                 "entity_refs": {"performer": [{"id": 3, "name": "P"}], "creator": [], "studio": [{"id": 9, "name": "S"}], "series": []},
                 "liked": 1, "has_cover": false, "cover_key": ""}
                """
            )
        )!!
        assertEquals(listOf("剧情"), detail.tags)
        assertTrue(detail.liked)
        assertNull(detail.coverKey)
        val item = PeachMapper.toBaseItem(detail)
        assertEquals("studio:9", item.studios?.single()?.id)
        assertEquals("p", item.imageTags?.get("Primary"))
    }

    @Test
    fun imageUrlsFollowPeachRoutes() {
        val base = "https://peach.local:8443/"
        assertEquals(
            "https://peach.local:8443/cover?code=ABC-123&thumb=2",
            PeachImageUrls.imageUrl(base, "42", imageTag = "c.ABC-123")
        )
        assertEquals(
            "https://peach.local:8443/cover?code=A%20B&thumb=1",
            PeachImageUrls.imageUrl(base, "42", coverKey = "A B", large = true)
        )
        assertEquals("https://peach.local:8443/poster?id=42&c=4", PeachImageUrls.imageUrl(base, "42", imageTag = "p"))
        assertEquals(
            "https://peach.local:8443/entity-image?kind=performer&id=7&thumb=1",
            PeachImageUrls.imageUrl(base, "performer:7")
        )
        assertNull(PeachImageUrls.imageUrl(base, "lib:main"))
        assertNull(PeachImageUrls.imageUrl(base, "tag:3"))
    }

    @Test
    fun idsRoundTrip() {
        assertEquals("lib:a", PeachIds.library("a"))
        assertEquals("a", PeachIds.libraryId("lib:a"))
        assertEquals("studio" to 12L, PeachIds.entity("studio:12"))
        assertNull(PeachIds.entity("unknown:12"))
        assertNull(PeachIds.entity("performer:x"))
        assertEquals(12L, PeachIds.assetId("12"))
        assertNull(PeachIds.assetId("lib:12"))
    }

    @Test
    fun parsesLibrariesAndStreamPlan() {
        val libraries = PeachLibrary.parseList(
            json("""{"libraries": [{"id": "main", "name": "主库", "folders": 2, "metered": false, "roots": [{"root_id": "r1"}]}]}""")
        )
        assertEquals("lib:main", PeachMapper.libraryToView(libraries.single()).id)
        assertEquals(listOf("r1"), libraries.single().rootIds)

        val plan = PeachStreamPlan.parse(
            json("""{"id": 1, "protocol": "hls", "mime_type": "application/vnd.apple.mpegurl", "src": "/stream/hls/1/index.m3u8?session=s"}""")
        )!!
        assertTrue(plan.isHls)
        assertNull(PeachStreamPlan.parse(json("""{"error": "x"}""")))
    }

    @Test
    fun subtitleTracksKeepUnplayableNotes() {
        val tracks = PeachSubtitleTrack.parseList(
            json(
                """
                {"id": 1, "subtitles": [
                  {"id": "sidecar:a", "index": 0, "label": "简体中文", "format": "srt", "playable": true, "note": "", "src": "/api/assets/1/subtitles/0?track=a"},
                  {"id": "sidecar:b", "index": 1, "label": "PGS", "format": "sup", "playable": false, "note": "图形字幕，播放器里不显示", "src": "/api/assets/1/subtitles/1?track=b"}
                ]}
                """
            )
        )
        assertEquals(2, tracks.size)
        assertTrue(tracks[0].playable)
        assertFalse(tracks[1].playable)
        assertEquals("图形字幕，播放器里不显示", tracks[1].note)
    }

    @Test
    fun epochConversionHandlesLeapYears() {
        assertEquals("2024-02-29T12:00:00.0000000Z", PeachMapper.epochSecondsToIso(1709208000.0))
        assertNull(PeachMapper.epochSecondsToIso(0.0))
    }
}
