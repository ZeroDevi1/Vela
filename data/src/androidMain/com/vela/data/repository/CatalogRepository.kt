package com.vela.data.repository

import com.vela.data.model.*
import java.time.LocalDate
import com.vela.data.network.CatalogNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cancellation must propagate through all optional metadata branches. */
suspend fun <T> catalogResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) { throw e }
catch (e: Exception) { Result.failure(e) }

class CatalogRepository {
    private val api = CatalogNetwork.tmdb
    private val genreLock = Mutex()
    private val genreCache = mutableMapOf<String, List<CatalogGenre>>()
    suspend fun trending(window: String): List<CatalogTitle> {
        val titles = api.trending(window)
        val movieGenres = genres("movie").associateBy { it.id }
        val tvGenres = genres("tv").associateBy { it.id }
        return titles.map { title -> title.copy(genres = title.genreIds.mapNotNull { id ->
            (if (title.mediaType == "movie") movieGenres else tvGenres)[id]
        }) }
    }
    suspend fun browse(shelf: CatalogShelf, page: Int = 1, filter: CatalogFilter = CatalogFilter()): CatalogPage {
        val today = LocalDate.now()
        val (path, params) = catalogBrowseRequest(shelf, filter, today)
        val result = api.browse(path, shelf.type, page, params)
        // Genre metadata is optional: a failed genre request must not discard a valid list.
        val genres = if (shelf.type == "all") emptyMap() else
            catalogResult { genres(shelf.type).associateBy { it.id } }.getOrDefault(emptyMap())
        return result.copy(results = result.results.map { it.copy(genres = it.genreIds.mapNotNull(genres::get)) })
    }
    suspend fun genres(type: String): List<CatalogGenre> = genreLock.withLock {
        genreCache[type] ?: api.genres(type).also { genreCache[type] = it }
    }
    suspend fun search(query: String) = api.search(query)
    suspend fun detail(title: CatalogTitle) = api.catalogDetail(title.mediaType, title.id)
    suspend fun collection(id: Int) = api.collection(id).parts
}

/** All date filters use calendar dates; movie release lists explicitly use the CN region. */
fun catalogBrowseRequest(shelf: CatalogShelf, filter: CatalogFilter, today: LocalDate): Pair<String, Map<String, String>> {
    val params = mutableMapOf<String, String>()
    val path = when (shelf) {
        CatalogShelf.DAY -> "trending/all/day"
        CatalogShelf.WEEK -> "trending/all/week"
        CatalogShelf.NOW_PLAYING -> "movie/now_playing"
        CatalogShelf.UPCOMING -> "movie/upcoming"
        else -> "discover/${shelf.type}"
    }
    if (shelf == CatalogShelf.NOW_PLAYING || shelf == CatalogShelf.UPCOMING) params["region"] = "CN"
    if (shelf.filterable) {
        params["sort_by"] = "popularity.desc"
        when (shelf) {
            CatalogShelf.TOP_MOVIES, CatalogShelf.TOP_TV, CatalogShelf.ANIMATION_TOP -> {
                params["sort_by"] = "vote_average.desc"; params["vote_count.gte"] = "200"
            }
            CatalogShelf.RECENT_TOP, CatalogShelf.CLASSICS -> {
                params["vote_average.gte"] = "7"; params["vote_count.gte"] = "200"
                params["sort_by"] = "vote_average.desc"
                if (shelf == CatalogShelf.RECENT_TOP) {
                    params["primary_release_date.gte"] = today.minusYears(1).toString()
                    params["primary_release_date.lte"] = today.toString()
                } else params["primary_release_date.lte"] = today.minusYears(20).toString()
            }
            CatalogShelf.AIRING_TODAY, CatalogShelf.ON_AIR -> {
                params["air_date.gte"] = today.toString()
                params["air_date.lte"] = (if (shelf == CatalogShelf.ON_AIR) today.plusDays(7) else today).toString()
                params["timezone"] = "Asia/Shanghai"
            }
            CatalogShelf.NEW_TV -> {
                params["first_air_date.gte"] = today.withMonth(((today.monthValue - 1) / 3) * 3 + 1).withDayOfMonth(1).toString()
                params["first_air_date.lte"] = today.toString()
            }
            else -> Unit
        }
        val genres = listOfNotNull(16.takeIf { shelf.category == CatalogCategory.ANIMATION }, filter.genre).distinct()
        if (genres.isNotEmpty()) params["with_genres"] = genres.joinToString(",")
        filter.year?.let { params[if (shelf.type == "movie") "primary_release_year" else "first_air_date_year"] = it.toString() }
        if (filter.country.isNotBlank()) params["with_origin_country"] = filter.country
        if (filter.sort.isNotBlank()) {
            require(filter.sort in setOf("popularity.desc", "vote_average.desc", "date"))
            params["sort_by"] = if (filter.sort == "date") {
                if (shelf.type == "movie") "primary_release_date.desc" else "first_air_date.desc"
            } else filter.sort
            if (filter.sort == "vote_average.desc") params["vote_count.gte"] = "200"
        }
    }
    return path to params
}
