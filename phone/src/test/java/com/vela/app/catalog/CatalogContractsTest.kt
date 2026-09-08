package com.vela.app.catalog

import com.vela.data.model.BaseItemDto
import com.vela.data.model.CatalogIdentity
import com.vela.data.model.CatalogPage
import com.vela.data.repository.buildTraktScrobblePayload
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CatalogContractsTest {
    @Test fun tmdbIdsCannotCrossMovieAndSeriesNamespaces() {
        val movie = BaseItemDto(id = "movie", type = "Movie", providerIds = mapOf("Tmdb" to "42"))
        assertTrue(CatalogIdentity("movie", 42).matches(movie))
        assertFalse(CatalogIdentity("tv", 42).matches(movie))
        assertFalse(CatalogIdentity("movie", 42).matches(movie.copy(type = "Episode")))
    }

    @Test fun exactImdbCanResolveMissingTmdbButTitleCannot() {
        val title = BaseItemDto(id = "library", type = "Movie", name = "Same title", providerIds = mapOf("IMDB" to "tt123"))
        assertTrue(CatalogIdentity("movie", 7, "tt123").matches(title))
        assertFalse(CatalogIdentity("movie", 7, "tt456").matches(title))
        assertFalse(CatalogIdentity("movie", 7).matches(title.copy(providerIds = emptyMap())))
        assertFalse(CatalogIdentity("movie", 7, "tt123").matches(title.copy(id = null)))
    }

    @Test fun episodeScrobbleUsesEpisodeIdentityAndClampedPercentage() {
        val episode = BaseItemDto(id = "server-episode", type = "Episode", seriesId = "server-series", providerIds = mapOf("Tvdb" to "123"))
        val payload = buildTraktScrobblePayload(episode, 150_000, 100_000)!!
        assertEquals(123, payload["episode"]!!.jsonObject["ids"]!!.jsonObject["tvdb"]!!.jsonPrimitive.int)
        assertEquals(100.0, payload["progress"]!!.jsonPrimitive.double, 0.0)
        assertFalse(payload.toString().contains("server-series"))
        assertFalse(payload.toString().contains("server-episode"))
        assertEquals(0.0, buildTraktScrobblePayload(episode, -1, 100)!!["progress"]!!.jsonPrimitive.double, 0.0)
    }

    @Test fun unknownRuntimeAndMissingProviderIdsCannotCreateHistory() {
        val item = BaseItemDto(id = "local", type = "Movie", providerIds = mapOf("Tmdb" to "42"))
        assertNull(buildTraktScrobblePayload(item, 0, 0))
        assertNull(buildTraktScrobblePayload(item.copy(providerIds = null), 50, 100))
        assertNull(buildTraktScrobblePayload(item.copy(type = "Series"), 50, 100))
    }

    @Test fun multiSearchPreservesIdentityAndNullableRatings() {
        val page = Json { ignoreUnknownKeys = true }.decodeFromString<CatalogPage>("""{"page":1,"results":[{"id":42,"media_type":"tv","name":"作品","first_air_date":"2026-09-08","vote_average":null}]}""")
        val title = page.results.single()
        assertEquals("tv:42", title.key)
        assertEquals("作品", title.displayTitle)
        assertNull(title.voteAverage)
        assertEquals("2026-09-08", title.date)
    }
}
