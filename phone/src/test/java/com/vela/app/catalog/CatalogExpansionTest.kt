package com.vela.app.catalog

import com.vela.data.model.*
import com.vela.data.repository.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CatalogExpansionTest {
    private val today = LocalDate.of(2026, 9, 8)

    @Test fun animationFilterKeepsAnimationWhenAnotherGenreIsSelected() {
        val (path, params) = catalogBrowseRequest(CatalogShelf.ANIMATION_TV,
            CatalogFilter(genre = 35, year = 2025, country = "JP", sort = "vote_average.desc"), today)
        assertEquals("discover/tv", path)
        assertEquals("16,35", params["with_genres"])
        assertEquals("2025", params["first_air_date_year"])
        assertFalse(params.containsKey("primary_release_year"))
        assertEquals("200", params["vote_count.gte"])
    }

    @Test fun episodeAirDatesAreNotSeriesPremiereDates() {
        val (_, params) = catalogBrowseRequest(CatalogShelf.ON_AIR, CatalogFilter(), today)
        assertEquals("2026-09-08", params["air_date.gte"])
        assertEquals("2026-09-15", params["air_date.lte"])
        assertFalse(params.containsKey("first_air_date.gte"))
        val (_, quarter) = catalogBrowseRequest(CatalogShelf.NEW_TV, CatalogFilter(), today)
        assertEquals("2026-07-01", quarter["first_air_date.gte"])
    }

    @Test fun releaseListsSpecifyRegionAndDoNotPretendToSupportDiscoverFilters() {
        val (path, params) = catalogBrowseRequest(CatalogShelf.UPCOMING, CatalogFilter(year = 2020, genre = 16), today)
        assertEquals("movie/upcoming", path)
        assertEquals(mapOf("region" to "CN"), params)
        assertFalse(CatalogShelf.UPCOMING.filterable)
    }

    @Test fun paginationMetadataAndNullRatingsSurviveDeserialization() {
        val page = Json { ignoreUnknownKeys = true }.decodeFromString<CatalogPage>(
            """{"page":2,"total_pages":7,"results":[{"id":10,"name":"Show","vote_average":null}]}""")
        assertEquals(2, page.page)
        assertEquals(7, page.totalPages)
        assertNull(page.results.single().voteAverage)
    }

    @Test fun undatedSubscriptionsAndMissingIdentitiesRemainVisible() {
        val row = parseSubscription(Json.parseToJsonElement("""{"id":1,"name":"Undated","type":"电视剧","season":2,"state":"P"}""").jsonObject)
        assertEquals("Undated", row.name)
        assertEquals(2, row.season)
        assertNull(row.catalog)
        assertNull(row.added)
        assertNull(row.totalEpisodes)
    }

    @Test fun movieAndSeriesSubscriptionIdentityCannotCollide() {
        val movie = parseSubscription(Json.parseToJsonElement("""{"id":1,"name":"Movie","type":"电影","tmdbid":42,"season":0}""").jsonObject)
        val tv = parseSubscription(Json.parseToJsonElement("""{"id":2,"name":"Series","type":"电视剧","tmdbid":42,"season":1}""").jsonObject)
        assertEquals("movie:42", movie.catalog?.key)
        assertEquals("tv:42", tv.catalog?.key)
        assertNull(movie.season)
        assertNotEquals(movie.id, tv.id)
    }

    private fun episode(id: String, server: String, number: Int?, played: Boolean?, end: Int? = null) = FederatedMediaItem(
        BaseItemDto(id = id, type = "Episode", indexNumber = number, indexNumberEnd = end, parentIndexNumber = 1,
            userData = UserItemDataDto(played = played)), server, server, null)

    @Test fun duplicateServersAndMultiEpisodeFilesCountEachEpisodeOnce() {
        val library = SubscriptionLibrary(episodes = listOf(
            episode("double", "A", 1, false, 2), episode("watched", "B", 1, true), episode("extra", "B", 2, false)
        ), complete = true)
        assertEquals(2, library.episodeGroups.size)
        assertEquals(1, library.watched)
        assertEquals(setOf(2), library.pending.keys)
        assertTrue(library.progressKnown)
    }

    @Test fun unknownPlayedStateAndMissingEpisodeNumberDoNotBecomeUnwatched() {
        val library = SubscriptionLibrary(episodes = listOf(episode("unknown", "A", 1, null), episode("missing", "A", null, false)), complete = true)
        assertEquals(0, library.pending.size)
        assertFalse(library.progressKnown)
        assertFalse(SubscriptionLibrary().progressKnown)
    }

    @Test fun partialServerFailureCannotReportCompleteProgress() {
        val library = SubscriptionLibrary(episodes = listOf(episode("known", "A", 1, false)), errors = listOf("B offline"))
        assertEquals(setOf(1), library.pending.keys)
        assertFalse(library.progressKnown)
    }
}
