package com.vela.data.api

import com.vela.data.model.MediaExtra
import com.vela.data.model.TmdbImage
import com.vela.data.model.TmdbImagesResponse
import com.vela.data.model.TmdbVideosResponse
import com.vela.data.model.toMediaExtras
import com.vela.data.model.toRawVideos
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.withPermit

internal data class TmdbTitleSummary(
    val title: String,
    val posterUrl: String?,
    val year: Int?
)

@Serializable
internal data class TmdbTitleDetail(
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double? = null,
    val genres: List<TmdbGenre> = emptyList()
)

@Serializable
internal data class TmdbGenre(val name: String? = null)

class TmdbApi(
    private val client: HttpClient,
    private val apiKey: String = TMDB_API_KEY
) {
    internal suspend fun titleDetail(mediaType: String, tmdbId: String): TmdbTitleDetail? = runCatching {
        client.get("https://api.themoviedb.org/3/$mediaType/$tmdbId") {
            parameter("api_key", apiKey)
            parameter("language", "en-US")
        }.body<TmdbTitleDetail>()
    }.getOrNull()

    internal suspend fun titleSummary(mediaType: String, tmdbId: String): TmdbTitleSummary? {
        val detail = titleDetail(mediaType, tmdbId) ?: return null
        val title = detail.title?.takeIf { it.isNotBlank() }
            ?: detail.name?.takeIf { it.isNotBlank() }
            ?: return null
        val year = (detail.releaseDate ?: detail.firstAirDate)
            ?.takeIf { it.length >= 4 }
            ?.substring(0, 4)
            ?.toIntOrNull()
        return TmdbTitleSummary(title = title, posterUrl = imageUrl(detail.posterPath, "w500"), year = year)
    }

    private val catalogGate = kotlinx.coroutines.sync.Semaphore(2)

    suspend fun trending(window: String): List<com.vela.data.model.CatalogTitle> {
        require(window == "day" || window == "week")
        return catalog<com.vela.data.model.CatalogPage>("trending/all/$window").results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
    }

    /** Type-specific lists omit media_type; restore it before exposing catalog identities. */
    suspend fun browse(path: String, type: String, page: Int, filters: Map<String, String>): com.vela.data.model.CatalogPage {
        require(type in setOf("all", "movie", "tv"))
        require(page in 1..500)
        val result = catalog<com.vela.data.model.CatalogPage>(path, params = filters + ("page" to page.toString()))
        return result.copy(results = result.results.map { if (type == "all") it else it.copy(mediaType = type) }
            .filter { it.id > 0 && it.mediaType in setOf("movie", "tv") }.distinctBy { it.key })
    }

    suspend fun genres(type: String): List<com.vela.data.model.CatalogGenre> {
        require(type == "movie" || type == "tv")
        return catalog<com.vela.data.model.CatalogGenres>("genre/$type/list").genres
    }

    suspend fun findByImdb(id: String): List<com.vela.data.model.CatalogTitle> {
        require(id.matches(Regex("tt[0-9]+")))
        val response = catalog<com.vela.data.model.CatalogFindResult>("find/$id")
        return response.movies.map { it.copy(mediaType = "movie") } + response.shows.map { it.copy(mediaType = "tv") }
    }

    suspend fun search(query: String): List<com.vela.data.model.CatalogTitle> =
        catalog<com.vela.data.model.CatalogPage>("search/multi", query).results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }

    suspend fun catalogDetail(type: String, id: Int): com.vela.data.model.CatalogTitle {
        require(type == "movie" || type == "tv")
        require(id > 0)
        return catalog<com.vela.data.model.CatalogTitle>("$type/$id").let { detail ->
            detail.copy(mediaType = type, similar = detail.similar?.let { page ->
                page.copy(results = page.results.map { it.copy(mediaType = type) })
            })
        }
    }

    suspend fun collection(id: Int): com.vela.data.model.CatalogCollection = catalog("collection/$id")

    private suspend inline fun <reified T> catalog(path: String, query: String? = null, params: Map<String, String> = emptyMap()): T =
        catalogGate.withPermit {
            // 单次请求有界；429 交给页面显式重试，避免目录请求挤占播放流量。
            val response = client.get("https://api.themoviedb.org/3/$path") {
                parameter("api_key", apiKey)
                parameter("language", "zh-CN")
                parameter("include_adult", false)
                params.forEach { (key, value) -> parameter(key, value) }
                if (query != null) parameter("query", query)
                if (path.startsWith("find/")) parameter("external_source", "imdb_id")
                if (path.matches(Regex("(movie|tv)/[0-9]+"))) {
                    parameter("append_to_response", "credits,external_ids,similar")
                }
            }
            check(response.status.value in 200..299) { "TMDB HTTP ${response.status.value}" }
            response.body<T>()
        }

    suspend fun titleLogoPath(mediaType: String, tmdbId: String): String? = runCatching {
        client.get("https://api.themoviedb.org/3/$mediaType/$tmdbId/images") {
            parameter("api_key", apiKey)
        }.body<TmdbImagesResponse>()
            .logos
            .asSequence()
            .filter { image -> !image.filePath.isNullOrBlank() }
            .sortedWith(
                compareByDescending<TmdbImage> { image -> image.iso6391 == "en" }
                    .thenByDescending { image -> image.iso6391 == null }
                    .thenByDescending { image -> image.voteAverage ?: 0.0 }
                    .thenByDescending { image -> image.voteCount ?: 0 }
            )
            .firstOrNull()
            ?.filePath
    }.getOrNull()

    suspend fun titleLogoUrl(
        mediaType: String,
        tmdbId: String,
        size: String = "original"
    ): String? {
        return imageUrl(titleLogoPath(mediaType, tmdbId), size)
    }

    suspend fun fetchExtras(mediaType: String, tmdbId: String): List<MediaExtra> = runCatching {
        client.get("https://api.themoviedb.org/3/$mediaType/$tmdbId/videos") {
            parameter("api_key", apiKey)
        }.body<TmdbVideosResponse>()
            .results
            .toRawVideos()
            .toMediaExtras()
    }.getOrDefault(emptyList())

    private companion object {
        private const val TMDB_API_KEY = "4219e299c89411838049ab0dab19ebd5"

        private fun imageUrl(imagePath: String?, size: String): String? {
            val path = imagePath?.takeIf { it.isNotBlank() } ?: return null
            return "https://image.tmdb.org/t/p/$size/${path.removePrefix("/")}"
        }
    }
}