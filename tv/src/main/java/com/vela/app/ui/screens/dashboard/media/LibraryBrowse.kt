package com.vela.app.ui.screens.dashboard.media

import androidx.annotation.StringRes
import com.vela.data.model.BaseItemDto
import com.vela.data.model.DisplayPreferencesDto
import com.vela.shared.R

/** 媒体库页顶部页签，顺序与布局参照 Emby TV 客户端。 */
enum class LibraryBrowseTab {
    ITEMS,
    RECOMMENDED,
    FAVORITES,
    TRAILERS,
    GENRES,
    EPISODES,
    COLLECTIONS,
    FOLDERS
}

data class LibrarySortField(
    val sortBy: String,
    val defaultOrder: String,
    @StringRes val labelRes: Int,
    val supportsOrder: Boolean = true
)

data class LibraryRecommendationSection(
    val recommendationType: String?,
    val baselineName: String?,
    val items: List<BaseItemDto>
)

/** 文件夹 / 合集逐级进入时的一层；[id] 作为下一层查询的 parentId。 */
data class LibraryFolderNav(
    val id: String,
    val name: String
)

data class LibraryGenreNav(
    val id: String,
    val name: String
)

fun libraryBrowseTabs(contentType: ContentType): List<LibraryBrowseTab> = when (contentType) {
    ContentType.MOVIES -> listOf(
        LibraryBrowseTab.ITEMS,
        LibraryBrowseTab.RECOMMENDED,
        LibraryBrowseTab.FAVORITES,
        LibraryBrowseTab.TRAILERS,
        LibraryBrowseTab.GENRES,
        LibraryBrowseTab.COLLECTIONS,
        LibraryBrowseTab.FOLDERS
    )
    ContentType.SERIES -> listOf(
        LibraryBrowseTab.ITEMS,
        LibraryBrowseTab.RECOMMENDED,
        LibraryBrowseTab.FAVORITES,
        LibraryBrowseTab.GENRES,
        LibraryBrowseTab.EPISODES,
        LibraryBrowseTab.FOLDERS
    )
    ContentType.ALL -> listOf(
        LibraryBrowseTab.ITEMS,
        LibraryBrowseTab.RECOMMENDED,
        LibraryBrowseTab.FAVORITES,
        LibraryBrowseTab.GENRES,
        LibraryBrowseTab.COLLECTIONS,
        LibraryBrowseTab.FOLDERS
    )
    else -> emptyList()
}

@StringRes
fun LibraryBrowseTab.labelRes(contentType: ContentType): Int = when (this) {
    LibraryBrowseTab.ITEMS -> when (contentType) {
        ContentType.MOVIES -> R.string.library_tab_items
        ContentType.SERIES -> R.string.library_tab_shows
        else -> R.string.library_tab_all
    }
    LibraryBrowseTab.RECOMMENDED -> R.string.library_tab_recommended
    LibraryBrowseTab.FAVORITES -> R.string.tv_library_tab_favorites
    LibraryBrowseTab.TRAILERS -> R.string.library_tab_trailers
    LibraryBrowseTab.GENRES -> R.string.library_tab_genres
    LibraryBrowseTab.EPISODES -> R.string.search_results_episodes
    LibraryBrowseTab.COLLECTIONS -> R.string.tv_library_tab_collections
    LibraryBrowseTab.FOLDERS -> R.string.library_tab_folders
}

/** 该页签的列表是否可排序 / 筛选；推荐与流派总览没有排序。 */
fun LibraryBrowseTab.supportsSort(): Boolean =
    this != LibraryBrowseTab.RECOMMENDED && this != LibraryBrowseTab.GENRES

/** 该页签的条目以横版（16:9）卡片展示。 */
fun LibraryBrowseTab.usesLandscapeCards(): Boolean = this == LibraryBrowseTab.EPISODES

fun librarySortFields(): List<LibrarySortField> = listOf(
    LibrarySortField("SortName", "Ascending", R.string.library_sort_title),
    LibrarySortField("DateCreated", "Descending", R.string.library_sort_date_added),
    LibrarySortField("DateLastContentAdded", "Descending", R.string.library_sort_date_updated),
    LibrarySortField("PremiereDate", "Descending", R.string.library_sort_premiere),
    LibrarySortField("ProductionYear", "Descending", R.string.library_sort_year),
    LibrarySortField("CommunityRating", "Descending", R.string.library_sort_community),
    LibrarySortField("CriticRating", "Descending", R.string.library_sort_critic),
    LibrarySortField("OfficialRating", "Ascending", R.string.library_sort_official_rating),
    LibrarySortField("DatePlayed", "Descending", R.string.library_sort_date_played),
    LibrarySortField("Runtime", "Descending", R.string.library_sort_runtime),
    LibrarySortField("Random", "Ascending", R.string.library_sort_random, supportsOrder = false)
)

fun matchedLibrarySortBy(raw: String?): String? {
    val candidate = raw
        ?.split(',')
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotBlank() }
        ?: return null
    return librarySortFields().firstOrNull { field ->
        field.sortBy.equals(candidate, ignoreCase = true)
    }?.sortBy
}

fun librarySortOrder(raw: String?, fallback: String): String = when {
    raw.equals("Ascending", ignoreCase = true) -> "Ascending"
    raw.equals("Descending", ignoreCase = true) -> "Descending"
    else -> fallback
}

fun DisplayPreferencesDto.resolvedLibrarySortBy(): String? =
    matchedLibrarySortBy(sortBy)
        ?: matchedLibrarySortBy(customPrefs?.get("SortBy"))
        ?: matchedLibrarySortBy(customPrefs?.get("sortBy"))

fun DisplayPreferencesDto.resolvedLibrarySortOrder(fallback: String): String =
    librarySortOrder(
        sortOrder ?: customPrefs?.get("SortOrder") ?: customPrefs?.get("sortOrder"),
        fallback
    )

@StringRes
fun recommendationTitleRes(recommendationType: String?): Int = when (recommendationType) {
    "HasDirectorFromRecentlyPlayed",
    "HasLikedDirector" -> R.string.library_rec_director
    "HasActorFromRecentlyPlayed",
    "HasLikedActor" -> R.string.library_rec_actor
    "SimilarToLikedItem" -> R.string.library_rec_liked
    "SimilarToRecentlyPlayed" -> R.string.library_rec_watched
    else -> R.string.library_tab_recommended
}

/** 合集与文件夹在页内逐级进入，其余条目打开详情页。 */
fun BaseItemDto.opensAsFolder(): Boolean =
    type == "BoxSet" || type == "Folder" || type == "CollectionFolder" ||
        (isFolder == true && type != "Series" && type != "Season" && type != "MusicAlbum")
