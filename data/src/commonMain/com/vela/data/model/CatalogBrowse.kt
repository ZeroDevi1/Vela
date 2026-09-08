package com.vela.data.model

enum class CatalogCategory { RECOMMENDED, MOVIES, SERIES, ANIMATION }

enum class CatalogShelf(val type: String, val category: CatalogCategory) {
    DAY("all", CatalogCategory.RECOMMENDED), WEEK("all", CatalogCategory.RECOMMENDED),
    POPULAR_MOVIES("movie", CatalogCategory.MOVIES), TOP_MOVIES("movie", CatalogCategory.MOVIES),
    NOW_PLAYING("movie", CatalogCategory.MOVIES), UPCOMING("movie", CatalogCategory.MOVIES),
    RECENT_TOP("movie", CatalogCategory.MOVIES), CLASSICS("movie", CatalogCategory.MOVIES),
    POPULAR_TV("tv", CatalogCategory.SERIES), TOP_TV("tv", CatalogCategory.SERIES),
    AIRING_TODAY("tv", CatalogCategory.SERIES), ON_AIR("tv", CatalogCategory.SERIES), NEW_TV("tv", CatalogCategory.SERIES),
    ANIMATION_MOVIES("movie", CatalogCategory.ANIMATION), ANIMATION_TV("tv", CatalogCategory.ANIMATION),
    ANIMATION_TOP("tv", CatalogCategory.ANIMATION);

    val filterable: Boolean get() = this !in setOf(DAY, WEEK, NOW_PLAYING, UPCOMING)
}

data class CatalogFilter(val genre: Int? = null, val year: Int? = null, val country: String = "", val sort: String = "")
