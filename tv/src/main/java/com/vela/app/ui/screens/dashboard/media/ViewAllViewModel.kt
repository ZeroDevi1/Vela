package com.vela.app.ui.screens.dashboard.media

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.util.text.TitleIndex
import com.vela.data.model.BaseItemDto
import com.vela.data.model.QueryResult
import com.vela.data.repository.AuthRepositoryProvider
import com.vela.data.repository.MediaRepository
import com.vela.data.repository.MediaRepositoryProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TV「查看全部 / 媒体库」页的数据源。
 *
 * 媒体库模式（从「我的媒体」或首页某个库进入）按 Emby TV 的页签组织：条目、推荐、收藏夹、预告片、
 * 流派、单集、合集、文件夹；流派、合集、文件夹可在页内逐级进入，返回键逐级退出。
 * 其他入口（流派、已观看、首页行的「全部」）只有单一列表。
 *
 * 查询条件在 [ensureItemsLoaded] 时确定，之后的操作都基于这次的条件；同一 ViewModel 只服务一个导航条目。
 * 每次刷新递增 [loadGeneration]，过期请求的结果直接丢弃。只在主线程调用公开方法。
 */
@HiltViewModel
class ViewAllViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val mediaRepository: MediaRepository by lazy { MediaRepositoryProvider.getInstance(context) }
    private val authRepository by lazy { AuthRepositoryProvider.getInstance(context) }
    private val sortMemory by lazy {
        context.getSharedPreferences("vela_tv_library_sort", Context.MODE_PRIVATE)
    }

    private val _uiState = MutableStateFlow(ViewAllUiState())
    val uiState: StateFlow<ViewAllUiState> = _uiState.asStateFlow()

    private val _items = MutableStateFlow<List<BaseItemDto>>(emptyList())
    val items: StateFlow<List<BaseItemDto>> = _items.asStateFlow()

    private var contentType = ContentType.ALL
    private var parentId: String? = null
    private var genreId: String? = null
    private var currentRequestKey: String? = null

    private var currentPage = 0
    private var hasMorePages = true
    private var loadJob: Job? = null
    private var ensureJob: Job? = null
    private var loadGeneration = 0

    /** 文件夹 / 合集逐级进入的路径，最后一项是当前所在层。 */
    private var folderStack: List<LibraryFolderNav> = emptyList()
    /** 流派页签中进入的流派；null 表示在流派总览。 */
    private var drilledGenre: LibraryGenreNav? = null

    fun ensureItemsLoaded(contentType: ContentType, parentId: String?, genreId: String?) {
        val requestKey = "$contentType|${parentId.orEmpty()}|${genreId.orEmpty()}"
        if (currentRequestKey == requestKey) return
        currentRequestKey = requestKey
        this.contentType = contentType
        this.parentId = parentId
        this.genreId = genreId

        val isLibrary = isLibraryRequest(contentType, parentId, genreId)
        _uiState.value = _uiState.value.copy(
            isLibrary = isLibrary,
            tabs = if (isLibrary) libraryBrowseTabs(contentType) else emptyList(),
            // Emby 媒体库默认按名称排序；其他入口保持「最近添加」
            sortBy = if (isLibrary) NAME_SORT_BY else "DateCreated",
            sortOrder = if (isLibrary) "Ascending" else "Descending"
        )
        ensureJob?.cancel()
        ensureJob = viewModelScope.launch {
            if (isLibrary) loadLibrarySortPreferences()
            loadItems(refresh = true)
        }
    }

    fun refresh() = loadItems(refresh = true)

    fun loadMoreItems() {
        if (loadJob?.isActive == true) return
        loadItems(refresh = false)
    }

    fun setBrowseTab(tab: LibraryBrowseTab) {
        if (_uiState.value.browseTab == tab) return
        folderStack = emptyList()
        drilledGenre = null
        _uiState.value = _uiState.value.copy(
            browseTab = tab,
            drillTitle = null,
            canGoBack = false,
            recommendationSections = emptyList()
        )
        _items.value = emptyList()
        loadItems(refresh = true)
    }

    /** 进入合集或文件夹，在当前页签内展示其子项。 */
    fun openFolder(folder: BaseItemDto) {
        val folderId = folder.id?.takeIf { it.isNotBlank() } ?: return
        folderStack = folderStack + LibraryFolderNav(folderId, folder.name.orEmpty())
        _uiState.value = _uiState.value.copy(drillTitle = folder.name, canGoBack = true)
        _items.value = emptyList()
        loadItems(refresh = true)
    }

    fun openGenre(genre: BaseItemDto) {
        val id = genre.id?.takeIf { it.isNotBlank() } ?: return
        drilledGenre = LibraryGenreNav(id, genre.name.orEmpty())
        _uiState.value = _uiState.value.copy(drillTitle = genre.name, canGoBack = true)
        _items.value = emptyList()
        loadItems(refresh = true)
    }

    /** 退出一层文件夹或流派；已在顶层时返回 false，由调用方执行页面返回。 */
    fun popBrowseLevel(): Boolean {
        when {
            folderStack.isNotEmpty() -> folderStack = folderStack.dropLast(1)
            drilledGenre != null -> drilledGenre = null
            else -> return false
        }
        _uiState.value = _uiState.value.copy(
            drillTitle = folderStack.lastOrNull()?.name ?: drilledGenre?.name,
            canGoBack = folderStack.isNotEmpty() || drilledGenre != null
        )
        _items.value = emptyList()
        loadItems(refresh = true)
        return true
    }

    fun setSort(sortBy: String, sortOrder: String) {
        _uiState.value = _uiState.value.copy(sortBy = sortBy, sortOrder = sortOrder)
        if (_uiState.value.isLibrary) saveLibrarySortPreferences(sortBy, sortOrder)
        loadItems(refresh = true)
    }

    fun toggleUnplayedOnly() {
        _uiState.value = _uiState.value.copy(unplayedOnly = !_uiState.value.unplayedOnly)
        loadItems(refresh = true)
    }

    fun toggleGenreFilter(genre: String) {
        val current = _uiState.value.selectedGenres
        _uiState.value = _uiState.value.copy(
            selectedGenres = if (genre in current) current - genre else current + genre
        )
        loadItems(refresh = true)
    }

    fun clearGenreFilters() {
        if (_uiState.value.selectedGenres.isEmpty()) return
        _uiState.value = _uiState.value.copy(selectedGenres = emptySet())
        loadItems(refresh = true)
    }

    /** 当前列表是否按名称排序并已在本地按拼音分组，可用首字母索引跳转。 */
    fun isNameIndexed(state: ViewAllUiState = _uiState.value): Boolean =
        state.sortBy == NAME_SORT_BY &&
            parentId != WATCHED_VIEW_ALL_PARENT_ID &&
            contentType != ContentType.EPISODES &&
            when (state.browseTab) {
                LibraryBrowseTab.ITEMS, LibraryBrowseTab.FAVORITES -> true
                LibraryBrowseTab.GENRES -> drilledGenre != null
                else -> false
            }

    /** 供流派筛选弹窗使用的当前库流派名列表；加载失败返回空列表。 */
    suspend fun loadGenreNames(): List<String> = withContext(Dispatchers.IO) {
        mediaRepository.getFilteredGenres(
            parentId = parentId?.takeIf { it != WATCHED_VIEW_ALL_PARENT_ID },
            includeItemTypes = includeTypes()
        ).getOrNull().orEmpty().mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
    }

    private fun loadItems(refresh: Boolean) {
        if (refresh) {
            currentPage = 0
            hasMorePages = true
            loadJob?.cancel()
        } else if (!hasMorePages) {
            return
        }

        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val state = _uiState.value
                val nameIndexed = isNameIndexed(state)
                val result = withContext(Dispatchers.IO) {
                    if (nameIndexed) fetchAllSortedByName(state) else fetchPage(state, currentPage * PAGE_SIZE, PAGE_SIZE)
                }
                if (generation != loadGeneration) return@launch
                result.fold(
                    onSuccess = { queryResult ->
                        val newItems = queryResult.items.orEmpty().filterBySelectedGenres(state)
                        val total = queryResult.totalRecordCount ?: newItems.size
                        hasMorePages = !nameIndexed && pagesSupported(state) &&
                            (currentPage + 1) * PAGE_SIZE < total
                        _items.value = if (refresh) newItems else _items.value + newItems
                        currentPage++
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            totalItems = total,
                            hasMorePages = hasMorePages
                        )
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = error.message ?: "Unknown error occurred"
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error occurred"
                )
            }
        }
    }

    /** 服务器只支持单流派过滤，多选流派时在客户端取交集。 */
    private fun List<BaseItemDto>.filterBySelectedGenres(state: ViewAllUiState): List<BaseItemDto> {
        if (state.selectedGenres.size <= 1 || !state.browseTab.supportsSort()) return this
        return filter { item ->
            val itemGenres = item.genres.orEmpty().toSet()
            state.selectedGenres.all(itemGenres::contains)
        }
    }

    private fun pagesSupported(state: ViewAllUiState): Boolean =
        parentId != WATCHED_VIEW_ALL_PARENT_ID &&
            state.browseTab != LibraryBrowseTab.RECOMMENDED &&
            (state.browseTab != LibraryBrowseTab.GENRES || drilledGenre != null)

    /**
     * 按名称排序时拉取全部条目并在本地按「索引标签 → 拼音」排序。
     *
     * 服务器按 SortName 的码点排序，中文标题不会按拼音聚集，首字母索引无法据此跳转；
     * 因此分批（每批 [NAME_INDEX_BATCH_SIZE]）拉全量，字段去掉 Overview 以控制体积。
     * 任一批失败即整体失败，不返回部分结果。
     */
    private suspend fun fetchAllSortedByName(state: ViewAllUiState): Result<QueryResult<BaseItemDto>> {
        val collected = mutableListOf<BaseItemDto>()
        var total: Int
        do {
            val page = fetchPage(state, collected.size, NAME_INDEX_BATCH_SIZE, NAME_INDEX_FIELDS)
                .getOrElse { return Result.failure(it) }
            val batch = page.items.orEmpty()
            collected += batch
            total = page.totalRecordCount ?: collected.size
        } while (batch.isNotEmpty() && collected.size < total)

        val labelOrder = TitleIndex.LABELS.withIndex().associate { (index, label) -> label to index }
        val ascending = collected
            .map { item -> item to item.indexTitle() }
            .sortedWith { (_, a), (_, b) ->
                val byLabel = labelOrder.getValue(TitleIndex.labelOf(a))
                    .compareTo(labelOrder.getValue(TitleIndex.labelOf(b)))
                if (byLabel != 0) byLabel else TitleIndex.compare(a, b)
            }
            .map { (item, _) -> item }
        val sorted = if (state.sortOrder == "Descending") ascending.asReversed() else ascending
        return Result.success(QueryResult(items = sorted, totalRecordCount = total, startIndex = 0))
    }

    private suspend fun fetchPage(
        state: ViewAllUiState,
        startIndex: Int,
        limit: Int,
        fields: String = ITEM_FIELDS
    ): Result<QueryResult<BaseItemDto>> {
        val repo = mediaRepository
        val genres = state.selectedGenres.sorted().joinToString("|").ifBlank { null }
        val filters = if (state.unplayedOnly) "IsUnplayed" else null

        if (parentId == WATCHED_VIEW_ALL_PARENT_ID) {
            return when (contentType) {
                ContentType.SERIES -> repo.loadWatchedItems("Episode")
                    .mapCatching { repo.loadSeriesForWatchedEpisodes(it).getOrThrow() }
                ContentType.EPISODES -> repo.loadWatchedItems("Episode")
                else -> repo.loadWatchedItems("Movie")
            }.map { QueryResult(items = it, totalRecordCount = it.size, startIndex = 0) }
        }

        suspend fun query(
            includeItemTypes: String?,
            queryParentId: String? = parentId,
            recursive: Boolean = true,
            genreIds: String? = genreId?.takeIf { it.isNotBlank() },
            queryFilters: String? = filters
        ) = repo.getUserItems(
            parentId = queryParentId,
            genres = genres,
            genreIds = genreIds,
            includeItemTypes = includeItemTypes,
            recursive = recursive,
            sortBy = state.sortBy,
            sortOrder = state.sortOrder,
            limit = limit,
            startIndex = startIndex,
            filters = queryFilters,
            fields = fields
        )

        return when (state.browseTab) {
            LibraryBrowseTab.ITEMS -> query(includeTypes())
            LibraryBrowseTab.FAVORITES -> query(
                includeItemTypes = includeTypes(),
                queryFilters = listOfNotNull("IsFavorite", filters).joinToString(",")
            )
            LibraryBrowseTab.TRAILERS -> query("Trailer")
            LibraryBrowseTab.EPISODES -> query("Episode")
            LibraryBrowseTab.COLLECTIONS -> folderStack.lastOrNull()
                ?.let { query(null, queryParentId = it.id, recursive = false) }
                ?: query("BoxSet")
            LibraryBrowseTab.FOLDERS -> query(
                includeItemTypes = null,
                queryParentId = folderStack.lastOrNull()?.id ?: parentId,
                recursive = false
            )
            LibraryBrowseTab.GENRES -> drilledGenre
                ?.let { query(includeTypes(), genreIds = it.id) }
                ?: repo.getFilteredGenres(parentId = parentId, includeItemTypes = includeTypes())
                    .map { QueryResult(items = it, totalRecordCount = it.size, startIndex = 0) }
            LibraryBrowseTab.RECOMMENDED -> loadRecommendations()
        }
    }

    private suspend fun loadRecommendations(): Result<QueryResult<BaseItemDto>> {
        val repo = mediaRepository
        val sections = if (contentType == ContentType.SERIES) {
            repo.getSuggestions(mediaType = "Series", limit = 24).map { items ->
                listOf(LibraryRecommendationSection(null, null, items))
            }
        } else {
            repo.getMovieRecommendations(parentId = parentId, categoryLimit = 8, itemLimit = 18)
                .map { rows ->
                    rows.map { row ->
                        LibraryRecommendationSection(row.recommendationType, row.baselineItemName, row.items.orEmpty())
                    }
                }
                .recoverCatching { error ->
                    // 服务器不支持推荐接口时退回通用建议
                    val fallback = repo.getSuggestions(mediaType = "Movie", limit = 24).getOrElse { throw error }
                    listOf(LibraryRecommendationSection(null, null, fallback))
                }
        }
        return sections.map { loaded ->
            val nonEmpty = loaded.filter { it.items.isNotEmpty() }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(recommendationSections = nonEmpty)
            }
            val flat = nonEmpty.flatMap { it.items }.distinctBy { it.id }
            QueryResult(items = flat, totalRecordCount = flat.size, startIndex = 0)
        }
    }

    private fun includeTypes(): String = when (contentType) {
        ContentType.MOVIES, ContentType.MOVIES_GENRE -> "Movie"
        ContentType.SERIES, ContentType.TVSHOWS_GENRE -> "Series"
        ContentType.EPISODES -> "Episode"
        ContentType.ALL -> "Movie,Series"
    }

    /** 本地记忆优先，其次服务器上的库显示偏好（与 Emby / Jellyfin Web 共用）；都没有时保持默认。 */
    private suspend fun loadLibrarySortPreferences() {
        val libraryId = parentId ?: return
        val memoryKey = sortMemoryKey(libraryId)
        val localSortBy = matchedLibrarySortBy(sortMemory.getString("$memoryKey.sortBy", null))
        if (localSortBy != null) {
            _uiState.value = _uiState.value.copy(
                sortBy = localSortBy,
                sortOrder = librarySortOrder(sortMemory.getString("$memoryKey.sortOrder", null), _uiState.value.sortOrder)
            )
            return
        }
        val prefs = withContext(Dispatchers.IO) {
            mediaRepository.getLibrarySortPreferences(libraryId).getOrNull()
        } ?: return
        val sortBy = prefs.resolvedLibrarySortBy() ?: return
        _uiState.value = _uiState.value.copy(
            sortBy = sortBy,
            sortOrder = prefs.resolvedLibrarySortOrder(_uiState.value.sortOrder)
        )
    }

    private fun saveLibrarySortPreferences(sortBy: String, sortOrder: String) {
        val libraryId = parentId ?: return
        val memoryKey = sortMemoryKey(libraryId)
        sortMemory.edit()
            .putString("$memoryKey.sortBy", sortBy)
            .putString("$memoryKey.sortOrder", sortOrder)
            .apply()
        viewModelScope.launch(Dispatchers.IO) {
            // 同步到服务器失败不影响本地记忆，下次进入仍按本地排序
            mediaRepository.saveLibrarySortPreferences(libraryId, sortBy, sortOrder)
        }
    }

    private fun sortMemoryKey(libraryId: String): String {
        val serverId = authRepository.getActiveSessionSnapshot().activeServerId?.takeIf { it.isNotBlank() } ?: "default"
        return "$serverId|lib:$libraryId"
    }
}

data class ViewAllUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val sortBy: String = "DateCreated",
    val sortOrder: String = "Descending",
    val selectedGenres: Set<String> = emptySet(),
    val unplayedOnly: Boolean = false,
    val totalItems: Int = 0,
    val hasMorePages: Boolean = true,
    /** 是否为媒体库页（有页签、记忆排序）；false 时为单一列表。 */
    val isLibrary: Boolean = false,
    val tabs: List<LibraryBrowseTab> = emptyList(),
    val browseTab: LibraryBrowseTab = LibraryBrowseTab.ITEMS,
    /** 当前进入的流派 / 文件夹名；null 表示在页签顶层。 */
    val drillTitle: String? = null,
    val canGoBack: Boolean = false,
    val recommendationSections: List<LibraryRecommendationSection> = emptyList()
)

enum class ContentType {
    ALL, MOVIES, SERIES, EPISODES, MOVIES_GENRE, TVSHOWS_GENRE
}

const val NAME_SORT_BY = "SortName"

private const val PAGE_SIZE = 60

/** 全量名称排序每批条数：经验值，兼顾单次响应体积与请求次数。 */
private const val NAME_INDEX_BATCH_SIZE = 300

private const val ITEM_FIELDS =
    "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,Genres,CommunityRating,ProductionYear,Overview,UserData"

/** 全量拉取时卡片所需的最小字段集（不含 Overview 等大字段）。 */
private const val NAME_INDEX_FIELDS =
    "SortName,ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,Genres,ProductionYear,UserData"

/** 从「我的媒体」或首页某个库进入：有真实库 id、不是流派列表或已观看列表。 */
private fun isLibraryRequest(contentType: ContentType, parentId: String?, genreId: String?): Boolean =
    !parentId.isNullOrBlank() && parentId != WATCHED_VIEW_ALL_PARENT_ID && genreId.isNullOrBlank() &&
        (contentType == ContentType.ALL || contentType == ContentType.MOVIES || contentType == ContentType.SERIES)

/** 首字母索引与拼音排序所用的标题：优先服务器的 SortName（已去掉冠词等）。 */
internal fun BaseItemDto.indexTitle(): String = sortName?.takeIf(String::isNotBlank) ?: name.orEmpty()
