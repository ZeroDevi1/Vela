package com.vela.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CatalogTitle(
    val id: Int,
    @SerialName("media_type") val mediaType: String = "movie",
    val title: String? = null,
    val name: String? = null,
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    val genres: List<CatalogGenre> = emptyList(),
    @SerialName("external_ids") val externalIds: CatalogExternalIds? = null,
    @SerialName("belongs_to_collection") val collection: CatalogCollection? = null,
    val credits: CatalogCredits? = null,
    val similar: CatalogPage? = null,
    val seasons: List<CatalogSeason> = emptyList(),
    val status: String? = null,
    val doubanId: String? = null,
    val doubanRating: Double? = null
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: name.orEmpty()
    val date: String get() = releaseDate ?: firstAirDate.orEmpty()
    val posterUrl: String? get() = posterPath?.let { if (it.startsWith("https://") || it.startsWith("http://")) it else "https://image.tmdb.org/t/p/w500$it" }
    val key: String get() = "$mediaType:$id"
}

@Serializable data class CatalogGenres(val genres: List<CatalogGenre> = emptyList())
@Serializable data class CatalogFindResult(@SerialName("movie_results") val movies: List<CatalogTitle> = emptyList(), @SerialName("tv_results") val shows: List<CatalogTitle> = emptyList())
@Serializable data class CatalogPage(
    val results: List<CatalogTitle> = emptyList(),
    val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1
)
@Serializable data class CatalogGenre(val id: Int, val name: String)
@Serializable data class CatalogExternalIds(@SerialName("imdb_id") val imdbId: String? = null)
@Serializable data class CatalogCollection(val id: Int, val name: String = "", val parts: List<CatalogTitle> = emptyList())
@Serializable data class CatalogCredits(val cast: List<CatalogPerson> = emptyList(), val crew: List<CatalogPerson> = emptyList())
@Serializable data class CatalogPerson(val id: Int, val name: String, val character: String? = null, val job: String? = null, @SerialName("profile_path") val profilePath: String? = null)
@Serializable data class CatalogSeason(@SerialName("season_number") val number: Int, val name: String, @SerialName("episode_count") val episodeCount: Int = 0)
