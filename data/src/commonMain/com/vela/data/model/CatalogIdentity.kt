package com.vela.data.model

/** TMDB ids are scoped by media type. Titles are never evidence of a library match. */
data class CatalogIdentity(val mediaType: String, val tmdbId: Int, val imdbId: String? = null) {
    init { require(mediaType in setOf("movie", "tv") && tmdbId > 0) }

    fun matches(item: BaseItemDto): Boolean {
        if (item.type != if (mediaType == "movie") "Movie" else "Series") return false
        if (item.id.isNullOrBlank()) return false
        val ids = item.providerIds.orEmpty().mapKeys { it.key.lowercase() }
        return ids["tmdb"] == tmdbId.toString() || (!imdbId.isNullOrBlank() && ids["imdb"] == imdbId)
    }
}
