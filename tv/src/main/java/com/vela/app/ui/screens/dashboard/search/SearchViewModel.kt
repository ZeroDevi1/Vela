package com.vela.app.ui.screens.dashboard.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.data.model.BaseItemDto
import com.vela.data.model.SeerrDiscoveryCategory
import com.vela.data.model.SeerrItemIds
import com.vela.data.model.SeerrRecommendationTitle
import com.vela.data.model.SearchMediaType
import com.vela.data.model.filterSeerTitles
import com.vela.data.repository.AuthRepositoryProvider
import com.vela.data.repository.MediaRepository
import com.vela.data.repository.SeerrRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.vela.app.util.text.TitleIndex
import java.util.Locale
import javax.inject.Inject

private val defaultSearchTypes = setOf(
    SearchMediaType.MOVIE,
    SearchMediaType.SERIES,
    SearchMediaType.EPISODE
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository
) : ViewModel() {
    private val authRepository = AuthRepositoryProvider.getInstance(context)
    private val seerrRepository = SeerrRepository(context)

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedDiscoveryTab = MutableStateFlow(SearchDiscoveryTab.SUGGESTIONS)
    val selectedDiscoveryTab: StateFlow<SearchDiscoveryTab> = _selectedDiscoveryTab.asStateFlow()

    private val _selectedSearchTypes = MutableStateFlow(defaultSearchTypes)
    val selectedSearchTypes: StateFlow<Set<SearchMediaType>> = _selectedSearchTypes.asStateFlow()

    private var searchJob: Job? = null
    private var seerrScopeId: String? = null

    /**
     * 拼音首字母搜索用的影视名目录（电影 + 剧集，不含单集），按服务器缓存，切换服务器后重新加载。
     * 由 [initialsCatalogMutex] 保护；加载失败不缓存，下次搜索重试。
     */
    private var initialsCatalog: InitialsCatalog? = null
    private val initialsCatalogMutex = Mutex()

    init {
        loadsuggestions()
        refreshSeerrConnectionState()
    }

    fun selectDiscoveryTab(tab: SearchDiscoveryTab) {
        if (_selectedDiscoveryTab.value == tab &&
            (tab.seerrCategory == null || _uiState.value.seerrDiscoveryItems.isNotEmpty())
        ) {
            return
        }
        _selectedDiscoveryTab.value = tab
        tab.seerrCategory?.let(::loadSeerrDiscovery)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query

        searchJob?.cancel()

        if (query.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                isSearching = true,
                error = null
            )
            searchJob = viewModelScope.launch {
                delay(300)
                searchAndCategorize(query)
            }
        } else {
            _uiState.value = _uiState.value.copy(
                movieResults = emptyList(),
                showResults = emptyList(),
                episodeResults = emptyList(),
                seerrMovieResults = emptyList(),
                seerrShowResults = emptyList(),
                isSearching = false,
                error = null
            )
        }
    }

    fun toggleSearchType(type: SearchMediaType) {
        val currentTypes = _selectedSearchTypes.value
        val nextTypes = if (type in currentTypes) {
            currentTypes - type
        } else {
            currentTypes + type
        }
        if (nextTypes.isEmpty() || nextTypes == currentTypes) return

        _selectedSearchTypes.value = nextTypes
        if (_searchQuery.value.isNotBlank()) {
            executeSearch()
        }
    }

    fun executeSearch() {
        val query = _searchQuery.value
        if (query.isNotEmpty()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch {
                searchAndCategorize(query)
            }
        }
    }

    private suspend fun searchAndCategorize(query: String) {
        _uiState.value = _uiState.value.copy(
            isSearching = true,
            error = null
        )

        val activeServerId = authRepository.getActiveSessionSnapshot().activeServerId
        val isSeerrConnected = seerrRepository.getSavedConnectionInfo(activeServerId)?.isVerified == true
        val selectedTypes = _selectedSearchTypes.value
        val seerrTitles = if (
            SearchMediaType.MOVIE in selectedTypes ||
            SearchMediaType.SERIES in selectedTypes
        ) {
            fetchSeerrTitles(
                query = query,
                activeServerId = activeServerId,
                isSeerrConnected = isSeerrConnected
            )
        } else {
            emptyList()
        }

        val (serverItems, initialsItems) = coroutineScope {
            val initials = async { searchByInitials(query, selectedTypes, activeServerId) }
            val server = mediaRepository.searchItems(
                searchTerm = query,
                selectedTypes = selectedTypes,
                limit = 60
            ).getOrNull()?.let { items ->
                filterSearchItems(items, query)
            }
            server to initials.await()
        }
        // 文本匹配优先，拼音首字母匹配补在其后；服务器搜索失败但首字母有结果时仍展示
        val localItems = when {
            serverItems == null && initialsItems.isEmpty() -> null
            else -> {
                val known = serverItems.orEmpty().mapNotNullTo(HashSet()) { it.id }
                serverItems.orEmpty() + initialsItems.filter { it.id !in known }
            }
        }
        if (localItems == null) {
            if (seerrTitles.isNotEmpty()) {
                applySearchResults(
                    localItems = emptyList(),
                    seerrTitles = seerrTitles,
                    query = query,
                    selectedTypes = selectedTypes
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    error = context.getString(com.vela.shared.R.string.search_failed)
                )
            }
            return
        }

        applySearchResults(
            localItems = localItems,
            seerrTitles = seerrTitles,
            query = query,
            selectedTypes = selectedTypes
        )
    }

    private fun loadsuggestions() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(SuggestionsLoading = true)

            try {
                mediaRepository.getSuggestions(
                    mediaType = "Movie,Series",
                    limit = 15
                ).fold(
                    onSuccess = { suggestions ->
                        _uiState.value = _uiState.value.copy(
                            suggestions = suggestions,
                            SuggestionsLoading = false,
                            error = null
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            SuggestionsLoading = false,
                            error = error.message
                        )
                    }
                )

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    SuggestionsLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun refreshSeerrConnectionState(activeServerId: String? = authRepository.getActiveSessionSnapshot().activeServerId) {
        val scopeId = activeServerId?.takeIf { it.isNotBlank() }
        val scopeChanged = seerrScopeId != scopeId
        val wasSeerrConnected = _uiState.value.isSeerrConnected
        seerrScopeId = scopeId
        val isSeerrConnected = scopeId != null &&
            seerrRepository.getSavedConnectionInfo(scopeId)?.isVerified == true

        if (!isSeerrConnected) {
            _selectedDiscoveryTab.value = SearchDiscoveryTab.SUGGESTIONS
            _uiState.value = _uiState.value.copy(
                isSeerrConnected = false,
                seerrMovieResults = emptyList(),
                seerrShowResults = emptyList(),
                seerrDiscoveryItems = emptyList(),
                seerrDiscoveryLoading = false
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isSeerrConnected = true,
            seerrDiscoveryItems = if (scopeChanged) emptyList() else _uiState.value.seerrDiscoveryItems
        )

        if ((_searchQuery.value.isNotBlank()) && (!wasSeerrConnected || scopeChanged)) {
            executeSearch()
            return
        }

        val category = _selectedDiscoveryTab.value.seerrCategory ?: return
        if ((scopeChanged || _uiState.value.seerrDiscoveryItems.isEmpty()) && !_uiState.value.seerrDiscoveryLoading) {
            loadSeerrDiscovery(category)
        }
    }

    private fun loadSeerrDiscovery(category: SeerrDiscoveryCategory) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                seerrDiscoveryLoading = true
            )

            val activeServerId = authRepository.getActiveSessionSnapshot().activeServerId
            val scopeId = activeServerId?.takeIf { it.isNotBlank() }
            val isSeerrConnected = seerrRepository.getSavedConnectionInfo(activeServerId)?.isVerified == true
            if (scopeId == null || !isSeerrConnected) {
                _uiState.value = _uiState.value.copy(
                    isSeerrConnected = false,
                    seerrDiscoveryItems = emptyList(),
                    seerrDiscoveryLoading = false,
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(isSeerrConnected = true)

            seerrRepository.getDiscoveryTitles(
                scopeId = scopeId,
                category = category,
                limit = 20
            ).fold(
                onSuccess = { titles ->
                    if (_selectedDiscoveryTab.value.seerrCategory == category) {
                        _uiState.value = _uiState.value.copy(
                            seerrDiscoveryItems = titles.toDiscoveryItems(),
                            seerrDiscoveryLoading = false
                        )
                    }
                },
                onFailure = {
                    if (_selectedDiscoveryTab.value.seerrCategory == category) {
                        _uiState.value = _uiState.value.copy(
                            seerrDiscoveryItems = emptyList(),
                            seerrDiscoveryLoading = false
                        )
                    }
                }
            )
        }
    }

    private fun List<SeerrRecommendationTitle>.toDiscoveryItems(): List<BaseItemDto> =
        distinctBy { title -> "${title.mediaType}:${title.tmdbId}" }
            .map { title ->
                BaseItemDto(
                    id = SeerrItemIds.detailId(tmdbId = title.tmdbId, mediaType = title.mediaType),
                    name = title.title,
                    type = if (title.mediaType.equals("tv", ignoreCase = true)) "Series" else "Movie",
                    providerIds = buildMap {
                        put("tmdb", title.tmdbId)
                        title.jellyfinMediaId?.takeIf { it.isNotBlank() }?.let { put("jellyfin", it) }
                    },
                    productionYear = title.productionYear,
                    imageUrl = title.posterUrl
                )
            }

    /**
     * 拼音首字母匹配：查询为纯字母数字（如 `atm`）时，在影视名目录中找首字母串以其开头或包含它的中文标题。
     * 只匹配含非 ASCII 字符的标题，纯英文标题已由服务器文本搜索覆盖。不支持单集。
     *
     * @return 按「前缀匹配优先、标题越短越靠前」排序的结果；查询不适用或目录加载失败时为空列表。
     */
    private suspend fun searchByInitials(
        query: String,
        selectedTypes: Set<SearchMediaType>,
        activeServerId: String?
    ): List<BaseItemDto> {
        val key = query.lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
        if (key.isEmpty() || !key.all { it in 'a'..'z' || it in '0'..'9' }) return emptyList()
        val types = selectedTypes
            .filter { it != SearchMediaType.EPISODE }
            .mapTo(HashSet()) { it.serverValue }
        if (types.isEmpty()) return emptyList()

        val entries = loadInitialsCatalog(activeServerId)
        return entries.asSequence()
            .filter { it.item.type in types }
            .mapNotNull { entry ->
                when {
                    entry.initials.startsWith(key) -> entry to 0
                    // 单个字母做包含匹配命中过多，只做前缀匹配
                    key.length > 1 && entry.initials.contains(key) -> entry to 1
                    else -> null
                }
            }
            .sortedWith(compareBy<Pair<InitialsEntry, Int>> { it.second }.thenBy { it.first.initials.length })
            .map { it.first.item }
            .toList()
    }

    private suspend fun loadInitialsCatalog(activeServerId: String?): List<InitialsEntry> =
        initialsCatalogMutex.withLock {
            initialsCatalog?.takeIf { it.serverId == activeServerId }?.let { return@withLock it.entries }

            val collected = mutableListOf<BaseItemDto>()
            do {
                val page = mediaRepository.getUserItems(
                    includeItemTypes = "Movie,Series",
                    recursive = true,
                    sortBy = "SortName",
                    sortOrder = "Ascending",
                    limit = INITIALS_CATALOG_BATCH_SIZE,
                    startIndex = collected.size,
                    fields = "ProductionYear,UserData"
                ).getOrElse { return@withLock emptyList() }
                val batch = page.items.orEmpty()
                collected += batch
                val total = page.totalRecordCount ?: collected.size
            } while (batch.isNotEmpty() && collected.size < total)

            val entries = collected.mapNotNull { item ->
                val name = item.name?.takeIf { name -> name.any { it.code >= 0x80 } } ?: return@mapNotNull null
                InitialsEntry(item = item, initials = TitleIndex.initialsOf(name))
            }
            initialsCatalog = InitialsCatalog(activeServerId, entries)
            entries
        }

    private fun filterSearchItems(items: List<BaseItemDto>, query: String): List<BaseItemDto> {
        return items
            .mapNotNull { item ->
                bestSearchMatch(query, item.name, item.originalTitle)?.let { match -> item to match }
            }
            .sortedBy { (_, match) -> match.priority }
            .map { (item, _) -> item }
    }

    private fun applySearchResults(
        localItems: List<BaseItemDto>,
        seerrTitles: List<SeerrRecommendationTitle>,
        query: String,
        selectedTypes: Set<SearchMediaType>
    ) {
        val movieResults = localItems.resultsFor(SearchMediaType.MOVIE, selectedTypes)
        val showResults = localItems.resultsFor(SearchMediaType.SERIES, selectedTypes)
        val episodeResults = localItems.resultsFor(SearchMediaType.EPISODE, selectedTypes)

        _uiState.value = _uiState.value.copy(
            movieResults = movieResults,
            showResults = showResults,
            episodeResults = episodeResults,
            seerrMovieResults = if (SearchMediaType.MOVIE in selectedTypes) {
                buildSeerrResults(
                    seerrTitles = seerrTitles,
                    mediaType = "movie",
                    query = query,
                    localItems = movieResults
                )
            } else {
                emptyList()
            },
            seerrShowResults = if (SearchMediaType.SERIES in selectedTypes) {
                buildSeerrResults(
                    seerrTitles = seerrTitles,
                    mediaType = "tv",
                    query = query,
                    localItems = showResults
                )
            } else {
                emptyList()
            },
            isSearching = false,
            error = null
        )
    }

    private fun buildSeerrResults(
        seerrTitles: List<SeerrRecommendationTitle>,
        mediaType: String,
        query: String,
        localItems: List<BaseItemDto>
    ): List<SeerrRecommendationTitle> {
        if (seerrTitles.isEmpty()) return emptyList()

        val matchedTitles = seerrTitles
            .filter { title -> title.mediaType.equals(mediaType, ignoreCase = true) }
            .mapNotNull { title ->
                bestSearchMatch(query, title.title)?.let { match -> title to match }
            }
            .sortedBy { (_, match) -> match.priority }
            .map { (title, _) -> title }
            .distinctBy { title -> title.tmdbId }

        return filterSeerTitles(
            seerrTitles = matchedTitles,
            localItems = localItems
        ).take(12)
    }

    private fun bestSearchMatch(query: String, vararg texts: String?): SearchMatch? {
        return texts
            .filterNotNull()
            .mapNotNull { text -> textSearchMatch(text, query) }
            .minByOrNull { match -> match.priority }
    }

    private fun textSearchMatch(
        text: String,
        query: String
    ): SearchMatch? {
        val trimmedQuery = query.trim()
        if (text.isBlank() || trimmedQuery.isBlank()) return null

        val lowerQuery = trimmedQuery.lowercase(Locale.getDefault())
        val normalizedQuery = trimmedQuery.normalizedSearchKey()
        val lowerText = text.lowercase(Locale.getDefault())
        val normalizedText = text.normalizedSearchKey()
        val queryWords = lowerQuery
            .split(Regex("[\\s\\p{Punct}]+"))
            .filter { word -> word.isNotBlank() }
        val tokens = lowerText.split(Regex("[\\s\\p{Punct}]+")).filter { token -> token.isNotBlank() }
        val allWordsMatch = queryWords.isNotEmpty() && queryWords.all { word ->
            tokens.any { token ->
                token == word || token.startsWith(word) || token.contains(word)
            }
        }

        return when {
            normalizedText == normalizedQuery -> SearchMatch.EXACT
            lowerText == lowerQuery -> SearchMatch.EXACT_TEXT
            lowerText.contains(lowerQuery) -> SearchMatch.PHRASE
            normalizedText.contains(normalizedQuery) -> SearchMatch.NORMALIZED_PHRASE
            allWordsMatch -> SearchMatch.ALL_WORDS
            queryWords.size == 1 && tokens.any { token ->
                token.startsWith(queryWords.first()) || token.contains(queryWords.first())
            } -> SearchMatch.SINGLE_WORD
            else -> null
        }
    }

    private fun String.normalizedSearchKey(): String {
        return lowercase(Locale.getDefault()).replace(Regex("[\\s\\p{Punct}]+"), "")
    }

    private suspend fun fetchSeerrTitles(
        query: String,
        activeServerId: String?,
        isSeerrConnected: Boolean
    ): List<SeerrRecommendationTitle> {
        val scopeId = activeServerId?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (!isSeerrConnected) return emptyList()

        return seerrRepository.searchTitles(
            scopeId = scopeId,
            query = query,
            limit = 20
        ).getOrElse { emptyList() }
    }

    private data class InitialsEntry(val item: BaseItemDto, val initials: String)

    private class InitialsCatalog(val serverId: String?, val entries: List<InitialsEntry>)

    private enum class SearchMatch(val priority: Int) {
        EXACT(0),
        EXACT_TEXT(1),
        PHRASE(2),
        NORMALIZED_PHRASE(3),
        ALL_WORDS(4),
        SINGLE_WORD(5)
    }

}

/** 首字母目录每批条数：经验值，兼顾单次响应体积与请求次数。 */
private const val INITIALS_CATALOG_BATCH_SIZE = 500

private fun List<BaseItemDto>.resultsFor(
    type: SearchMediaType,
    selectedTypes: Set<SearchMediaType>
): List<BaseItemDto> =
    if (type in selectedTypes) {
        val limit = if (type == SearchMediaType.EPISODE) 40 else 20
        filter { it.type == type.serverValue }.take(limit)
    } else {
        emptyList()
    }

enum class SearchDiscoveryTab(val seerrCategory: SeerrDiscoveryCategory?) {
    SUGGESTIONS(null),
    TRENDING(SeerrDiscoveryCategory.TRENDING),
    POPULAR_MOVIES(SeerrDiscoveryCategory.POPULAR_MOVIES),
    POPULAR_SHOWS(SeerrDiscoveryCategory.POPULAR_SHOWS),
    UPCOMING_MOVIES(SeerrDiscoveryCategory.UPCOMING_MOVIES),
    UPCOMING_SHOWS(SeerrDiscoveryCategory.UPCOMING_SHOWS)
}

data class SearchUiState(
    val suggestions: List<BaseItemDto> = emptyList(),
    val movieResults: List<BaseItemDto> = emptyList(),
    val showResults: List<BaseItemDto> = emptyList(),
    val episodeResults: List<BaseItemDto> = emptyList(),
    val seerrMovieResults: List<SeerrRecommendationTitle> = emptyList(),
    val seerrShowResults: List<SeerrRecommendationTitle> = emptyList(),
    val seerrDiscoveryItems: List<BaseItemDto> = emptyList(),
    val isSeerrConnected: Boolean = false,
    val isSearching: Boolean = false,
    val SuggestionsLoading: Boolean = false,
    val seerrDiscoveryLoading: Boolean = false,
    val error: String? = null
)