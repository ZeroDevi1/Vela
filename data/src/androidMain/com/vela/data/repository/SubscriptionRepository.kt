package com.vela.data.repository

import android.content.Context
import com.vela.data.api.*
import com.vela.data.model.CatalogTitle
import com.vela.data.network.CatalogNetwork
import com.vela.data.security.SecureSessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.time.LocalDate

class SubscriptionRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("catalog_connections", Context.MODE_PRIVATE)
    private val secure = SecureSessionStore(context)
    var enabled: Boolean
        get() = prefs.getBoolean("mp_enabled", false)
        set(value) { prefs.edit().putBoolean("mp_enabled", value).apply() }
    var syncRemoval: Boolean
        get() = prefs.getBoolean("mp_sync_removal", true)
        set(value) { prefs.edit().putBoolean("mp_sync_removal", value).apply() }
    val url: String get() = prefs.getString("mp_url", "").orEmpty()
    val username: String get() = prefs.getString("mp_user", "").orEmpty()
    val loggedIn: Boolean get() = secure.hasToken("moviepilot:$url")
    // 订阅列表每次从服务端读取；服务端删除自然同步，不缓存第二份订阅真相源。
    suspend fun connect(url: String, username: String, password: String, otp: String = "") {
        val normalized = url.trim().trimEnd('/').removeSuffix("/api/v1")
        val token = MoviePilotApi(normalized).login(username.trim(), password, otp)
        MoviePilotApi(normalized, token).subscriptions()
        if (this.url != normalized) secure.removeToken("moviepilot:${this.url}")
        secure.putToken("moviepilot:$normalized", token)
        prefs.edit().putString("mp_url", normalized).putString("mp_user", username.trim()).putBoolean("mp_enabled", true).apply()
    }
    suspend fun testConnection() { api().subscriptions() }
    fun logout() { secure.removeToken("moviepilot:$url"); enabled = false }
    private fun api(): MoviePilotApi {
        check(enabled && loggedIn) { "MoviePilot is not connected" }
        return MoviePilotApi(url, secure.getToken("moviepilot:$url"))
    }

    suspend fun subscriptions(): List<SubscriptionRecord> = api().subscriptions().map(::parseSubscription)
    suspend fun publicCalendar(today: LocalDate = LocalDate.now()) = bangumiCalendar(today)
    suspend fun subscriptionCalendar(records: List<SubscriptionRecord>, today: LocalDate = LocalDate.now()) = moviePilotCalendar(today, records)

    suspend fun removeSubscription(record: SubscriptionRecord) {
        check(syncRemoval) { "MoviePilot subscription removal is disabled" }
        api().removeSubscription(record.id)
    }

    suspend fun subscribedSeasons(title: CatalogTitle): Set<Int> = subscriptions().filter { it.catalog?.key == title.key }
        .mapNotNull { if (it.type == "movie") 0 else it.season }.toSet()

    suspend fun setSubscription(title: CatalogTitle, season: Int?, remove: Boolean) {
        require(title.mediaType == "movie" || (season != null && season > 0)) { "Select a season" }
        check(!remove || syncRemoval) { "MoviePilot subscription removal is disabled" }
        val matches = subscriptions().filter { it.catalog?.key == title.key && it.season == season }
        if (remove) {
            check(matches.size == 1) { "Subscription changed; manage individual entries in My subscriptions" }
            api().removeSubscription(matches.single().id)
        } else if (matches.isEmpty()) api().addSubscription(title, season)
    }

    suspend fun searchDouban(query: String): List<DoubanSearchTitle> = api().searchDouban(query).mapNotNull { row ->
        val id = row.text("douban_id") ?: return@mapNotNull null
        DoubanSearchTitle(id, row.text("title").orEmpty(), row.text("overview").orEmpty(), row.text("poster_path"),
            if (row.text("type") == "电影") "movie" else "tv", row.text("release_date") ?: row.text("year"),
            row.number("tmdb_id")?.takeIf { it > 0 }, row.text("imdb_id"), row.text("vote_average")?.toDoubleOrNull())
    }

    /** Resolve by returned provider ids only; the caller must choose manually if no mapping exists. */
    suspend fun resolveDouban(title: DoubanSearchTitle): CatalogTitle? {
        var imdbId = title.imdbId
        var tmdbId = title.tmdbId
        var rating = title.rating
        if (tmdbId == null && imdbId.isNullOrBlank()) {
            val detail = api().get("douban/${title.id}").jsonObject
            tmdbId = detail.number("tmdb_id")?.takeIf { it > 0 }
            imdbId = detail.text("imdb_id")
            rating = detail.text("vote_average")?.toDoubleOrNull() ?: rating
        }
        val resolved = if (tmdbId != null) CatalogNetwork.tmdb.catalogDetail(title.mediaType, tmdbId)
            else imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) }?.let { id ->
                CatalogNetwork.tmdb.findByImdb(id).singleOrNull { it.mediaType == title.mediaType }
            }
        return resolved?.copy(doubanId = title.id, doubanRating = rating)
    }

    suspend fun calendar(today: LocalDate = LocalDate.now()): CalendarFeed {
        if (enabled && loggedIn) {
            val result = catalogResult { moviePilotCalendar(today) }
            if (result.isSuccess) return result.getOrThrow()
            return bangumiCalendar(today).copy(warning = "MoviePilot: ${result.exceptionOrNull()?.message}")
        }
        return bangumiCalendar(today)
    }

    private suspend fun moviePilotCalendar(today: LocalDate, records: List<SubscriptionRecord>? = null): CalendarFeed = coroutineScope {
        val api = api()
        val subscriptions = records ?: subscriptions()
        val gate = Semaphore(3)
        val responses = subscriptions.map { sub -> async { gate.withPermit { catalogResult {
            val title = sub.catalog ?: error("Subscription ${sub.id} has no TMDB identity")
            val tmdb = title.id
            val type = title.mediaType
            val season = if (type == "tv") sub.season ?: error("Subscription ${sub.id} has no season") else null
            val episodes = if (type == "movie") {
                val detail = api.get("media/tmdb:$tmdb", mapOf("type_name" to "电影")).jsonObject
                listOf(buildJsonObject { put("air_date", detail.text("release_date")); put("name", title.displayTitle) })
            } else {
                api.get("tmdb/$tmdb/$season", sub.episodeGroup?.let { mapOf("episode_group" to it) }.orEmpty()).jsonArray.map { it.jsonObject }
            }
            episodes.mapNotNull { ep ->
                val date = ep.text("air_date")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return@mapNotNull null
                if (date < today.minusDays(7) || date > today.plusDays(30)) return@mapNotNull null
                CalendarEntry("mp:${sub.id}:${ep.number("episode_number")}:$date", title.displayTitle, title.posterUrl,
                    date, title, season = if (type == "tv") season else null, episode = ep.number("episode_number"),
                    completed = sub.totalEpisodes?.let { it > 0 && ep.number("episode_number") == it }, episodeName = ep.text("name"))
            }
        } } } }.awaitAll()
        val failures = responses.mapNotNull { it.exceptionOrNull()?.message }
        if (responses.isNotEmpty() && responses.all { it.isFailure }) error(failures.first())
        CalendarFeed("MoviePilot", responses.flatMap { it.getOrDefault(emptyList()) }.distinctBy { "${it.catalog?.key}:${it.season}:${it.episode}:${it.date}" }.sortedBy { it.date }, failures.joinToString("\n").takeIf { it.isNotBlank() })
    }

    private suspend fun bangumiCalendar(today: LocalDate): CalendarFeed {
        val rows = BangumiApi().calendar()
        val entries = rows.flatMap { day ->
            val weekday = day["weekday"]?.jsonObject?.number("id") ?: return@flatMap emptyList()
            // 公开接口只给本周星期，不伪造过去播出历史或季集进度。
            val date = today.plusDays(((weekday - today.dayOfWeek.value + 7) % 7).toLong())
            day.objects("items").map { subject ->
                CalendarEntry("bgm:${subject.number("id")}", subject.text("name_cn") ?: subject.text("name").orEmpty(),
                    subject["images"]?.jsonObject?.text("large"), date, null,
                    sourceUrl = "https://bgm.tv/subject/${subject.number("id")}", originalTitle = subject.text("name"))
            }
        }
        return CalendarFeed("Bangumi", entries.sortedBy { it.date })
    }
}

data class CalendarFeed(val source: String, val entries: List<CalendarEntry>, val warning: String? = null)
data class CalendarEntry(val key: String, val title: String, val poster: String?, val date: LocalDate,
    val catalog: CatalogTitle?, val season: Int? = null, val episode: Int? = null, val completed: Boolean? = null,
    val sourceUrl: String? = null, val originalTitle: String? = null, val episodeName: String? = null)

data class DoubanSearchTitle(val id: String, val title: String, val overview: String, val poster: String?,
    val mediaType: String, val date: String?, val tmdbId: Int?, val imdbId: String?, val rating: Double?)
