package com.vela.data.peach

import com.vela.data.network.VelaJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeachBrowseTest {

    @Test
    fun virtualIdsRoundTrip() {
        assertEquals("later", PeachIds.viewKey(PeachIds.view("later")))
        assertNull(PeachIds.viewKey("view:unknown"))
        assertEquals("browse", PeachIds.hubPage(PeachIds.hub("browse")))
        assertEquals(12L to null, PeachIds.followRef(PeachIds.follow(12)))
        assertEquals(12L to 3, PeachIds.followRef(PeachIds.follow(12, 3)))
        assertNull(PeachIds.entity("follow:12"))
        assertNull(PeachIds.assetId("follow:12"))
        assertEquals("ABC-1", PeachIds.javPreviewCode(PeachIds.javPreview("ABC-1")))
        assertTrue(PeachMapper.virtualViews().any { it.id == PeachIds.hub("browse") })
    }

    @Test
    fun followTimelineTakesPrimaryPostAndFindsVariants() {
        val json = VelaJson.parseToJsonElement(
            """
            {"ok": true, "has_more": true, "counts": {"new": 4, "seen": 1},
             "facets": {"authors": ["A"], "tags": [["cat", 3]]},
             "groups": [{"release_key": "r", "primary": {"id": 7, "title": "Post", "author": "A", "status": "new",
                 "thumb_url": "/follow-image?id=7&v=abc", "duration": 61.5, "playable": true,
                 "media_items": [{"index": 0, "media_kind": "video", "thumb_url": "/follow-cover?id=7&media=0"},
                                 {"index": 2, "media_kind": "image", "name": "pic.jpg"}]},
                 "variants": [{"id": 8, "title": "Alt", "status": "seen"}], "duplicates": []}]}
            """
        ).jsonObject
        val page = PeachFollowPage.parse(json)
        assertEquals(listOf(7L), page.items.map { it.id })
        assertTrue(page.hasMore)
        assertEquals(4, page.counts["new"])
        assertEquals(listOf("cat"), page.tags)
        assertEquals("Alt", PeachFollowPage.findItem(json, 8)?.title)

        val item = PeachMapper.followToBaseItem(page.items.single())
        assertEquals("follow:7", item.id)
        assertEquals(615_000_000L, item.runTimeTicks)
        assertEquals("u./follow-image?id=7&v=abc", item.imageTags?.get("Primary"))
        val media = PeachMapper.followToBaseItem(page.items.single(), 2)
        assertEquals("follow:7:2", media.id)
        assertEquals("pic.jpg", media.name)
    }

    @Test
    fun imageUrlsForNonLibraryItems() {
        val base = "https://peach.local"
        assertEquals(
            "https://peach.local/follow-image?id=7&v=abc",
            PeachImageUrls.imageUrl(base, "follow:7", imageTag = PeachImageUrls.pathTag("/follow-image?id=7&v=abc"))
        )
        assertEquals("https://peach.local/follow-cover?id=7&media=2", PeachImageUrls.imageUrl(base, "follow:7:2"))
        assertEquals("https://peach.local/cover-image?code=ABC-1", PeachImageUrls.imageUrl(base, "javpreview:ABC-1"))
    }

    @Test
    fun parsesSuggestionsWithNavigableIds() {
        val suggestions = PeachSuggestion.parseList(
            VelaJson.parseToJsonElement(
                """
                {"q": "ab", "groups": [
                  {"kind": "performer", "label": "艺人", "total": 1, "items": [{"value": "Alice", "n": 12, "entity_id": 5}]},
                  {"kind": "tag", "label": "标签", "items": [{"value": "剧情", "entity_id": 9}]},
                  {"kind": "asset", "label": "作品", "items": [{"value": "ABC-1 Title", "id": 77, "code": "ABC-1"}]}]}
                """
            ).jsonObject
        )
        assertEquals(listOf("performer:5", null, "77"), suggestions.map { it.itemId })
    }

    @Test
    fun parsesJavDetailAndPhotos() {
        val detail = PeachJavDetail.parse(
            VelaJson.parseToJsonElement(
                """{"ok": true, "code": "ABC-1", "title": "T", "score": 4.5, "preview_count": 3,
                    "preview_video_url": "/jav-preview/ABC-1/x.m3u8", "wanted": true, "want_id": 4,
                    "performer_refs": [{"id": 2, "name": "P", "has_image": true}]}"""
            ).jsonObject
        )!!
        assertEquals("4.5", detail.score)
        assertEquals(4L, detail.wantId)
        assertEquals("performer:2", PeachIds.entity("performer", detail.performers.single().id!!))

        val photos = PeachPhotoPage.parse(
            VelaJson.parseToJsonElement(
                """{"sets": [{"id": 10, "kind": "dir", "title": "D", "n": 4}, {"id": "code:ABC", "kind": "code", "code": "ABC", "n": 2}],
                    "items": [{"id": 1}, {"id": 2}], "total": 2, "has_more": false}"""
            ).jsonObject
        )
        assertEquals(listOf("10", "code:ABC"), photos.sets.map { it.id })
        assertEquals(listOf(1L, 2L), photos.photos)
    }
}
