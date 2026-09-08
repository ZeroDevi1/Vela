package com.vela.data.repository

import com.vela.data.model.CatalogTitle
import com.vela.data.network.CatalogNetwork
import kotlinx.coroutines.CancellationException

/** Cancellation must propagate through all optional metadata branches. */
suspend fun <T> catalogResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) { throw e }
catch (e: Exception) { Result.failure(e) }

class CatalogRepository {
    private val api = CatalogNetwork.tmdb
    suspend fun trending(window: String): List<CatalogTitle> {
        val titles = api.trending(window)
        val movieGenres = api.genres("movie").associateBy { it.id }
        val tvGenres = api.genres("tv").associateBy { it.id }
        return titles.map { title -> title.copy(genres = title.genreIds.mapNotNull { id ->
            (if (title.mediaType == "movie") movieGenres else tvGenres)[id]
        }) }
    }
    suspend fun search(query: String) = api.search(query)
    suspend fun detail(title: CatalogTitle) = api.catalogDetail(title.mediaType, title.id)
    suspend fun collection(id: Int) = api.collection(id).parts
}
