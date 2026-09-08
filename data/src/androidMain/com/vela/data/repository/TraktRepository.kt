package com.vela.data.repository

import android.content.Context
import com.vela.data.api.*
import com.vela.data.model.BaseItemDto
import com.vela.data.model.CatalogTitle
import com.vela.data.network.CatalogNetwork
import com.vela.data.security.SecureSessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Trakt is a read-only mirror of library state; reporting has its own bounded retry path. */
class TraktRepository private constructor(context: Context) {
    private val secure = SecureSessionStore(context)
    private val prefs = context.applicationContext.getSharedPreferences("trakt_history", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenLock = Mutex()
    private val events = Channel<ScrobbleEvent>(64)
    @Volatile private var epoch = 0L
    @Volatile private var reportingJob: Job? = null
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    val clientId: String get() = secure.getToken("trakt_client_id").orEmpty()
    val loggedIn: Boolean get() = secure.hasToken("trakt_session")
    val username: String get() = prefs.getString("username", "").orEmpty()

    init {
        scope.launch {
            for (event in events) {
                val task = scope.launch {
                    if (event.epoch != epoch || !loggedIn) return@launch
                    catalogResult { report(event) }.onFailure { _error.value = it.message ?: "Trakt scrobble failed" }
                }
                reportingJob = task
                task.join()
            }
        }
    }

    suspend fun beginAuthorization(id: String, secret: String): TraktDeviceCode {
        require(id.isNotBlank() && secret.isNotBlank()) { "Trakt client credentials required" }
        val response = TraktApi(id.trim()).request("oauth/device/code", payload = buildJsonObject { put("client_id", id.trim()) }).jsonObject
        logout()
        secure.putToken("trakt_client_id", id.trim())
        secure.putToken("trakt_client_secret", secret.trim())
        return TraktDeviceCode(response.text("device_code") ?: error("Missing device code"),
            response.text("user_code") ?: error("Missing user code"),
            response.text("verification_url") ?: error("Missing verification URL"),
            response.number("interval")?.takeIf { it > 0 } ?: error("Missing polling interval"),
            response.number("expires_in")?.takeIf { it > 0 } ?: error("Missing device code expiry"))
    }

    suspend fun awaitAuthorization(device: TraktDeviceCode) {
        val currentEpoch = epoch
        val deadline = android.os.SystemClock.elapsedRealtime() + device.expiresIn * 1000L
        var interval = device.interval.coerceAtLeast(1) * 1000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            delay(interval)
            check(currentEpoch == epoch) { "Trakt authorization cancelled" }
            val result = catalogResult {
                TraktApi(clientId).request("oauth/device/token", payload = credentials { put("code", device.code) }).jsonObject
            }
            val tokens = result.getOrNull()
            if (tokens != null) {
                check(currentEpoch == epoch) { "Trakt authorization cancelled" }
                validateTokens(tokens)
                secure.putToken("trakt_session", tokens.toString())
                val user = authenticated("users/settings").jsonObject["user"]?.jsonObject?.text("username").orEmpty()
                check(currentEpoch == epoch) { "Trakt session changed" }
                prefs.edit().putString("username", user).apply()
                return
            }
            val error = result.exceptionOrNull()
            when ((error as? TraktHttpException)?.status) {
                400 -> Unit
                429 -> interval = maxOf(interval + 5000, (error.retryAfterSeconds ?: 0L) * 1000)
                else -> throw error ?: IllegalStateException("Trakt authorization failed")
            }
        }
        error("Trakt authorization expired")
    }

    fun logout() {
        epoch++
        reportingJob?.cancel()
        secure.removeToken("trakt_session")
        prefs.edit().clear().apply()
        _error.value = null
    }

    private fun credentials(extra: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("client_id", clientId)
        put("client_secret", secure.getToken("trakt_client_secret") ?: error("Missing Trakt client secret"))
        extra()
    }

    private fun validateTokens(tokens: JsonObject) {
        check(!tokens.text("access_token").isNullOrBlank() && !tokens.text("refresh_token").isNullOrBlank() && tokens.number("expires_in") != null) { "Invalid Trakt token response" }
    }

    private suspend fun accessToken(forceRefresh: Boolean = false): String = tokenLock.withLock {
        val currentEpoch = epoch
        var tokens = CatalogNetwork.json.parseToJsonElement(secure.getToken("trakt_session") ?: error("Trakt login required")).jsonObject
        val created = tokens.text("created_at")?.toLongOrNull() ?: 0L
        val expires = tokens.text("expires_in")?.toLongOrNull() ?: 0L
        if (forceRefresh || System.currentTimeMillis() / 1000 >= created + expires - 60) {
            tokens = TraktApi(clientId).request("oauth/token", payload = credentials {
                put("refresh_token", tokens.text("refresh_token")); put("grant_type", "refresh_token"); put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
            }).jsonObject
            validateTokens(tokens)
            check(currentEpoch == epoch) { "Trakt session changed" }
            secure.putToken("trakt_session", tokens.toString())
        }
        tokens.text("access_token") ?: error("Missing Trakt token")
    }

    private suspend fun authenticated(path: String, payload: JsonObject? = null): JsonElement {
        val currentEpoch = epoch
        val token = accessToken()
        check(currentEpoch == epoch) { "Trakt session changed" }
        return try { TraktApi(clientId).request(path, token, payload) }
        catch (e: TraktHttpException) {
            if (e.status != 401) throw e
            val refreshed = accessToken(forceRefresh = true)
            check(currentEpoch == epoch) { "Trakt session changed" }
            TraktApi(clientId).request(path, refreshed, payload)
        }
    }

    fun scrobble(action: String, item: BaseItemDto, positionMs: Long, durationMs: Long) {
        if (!loggedIn || durationMs <= 0 || action !in setOf("start", "pause", "stop")) return
        val payload = buildTraktScrobblePayload(item, positionMs, durationMs) ?: run {
            _error.value = "Trakt: missing movie/episode provider identity"
            return
        }
        if (!events.trySend(ScrobbleEvent(epoch, action, payload)).isSuccess) _error.value = "Trakt event queue is full"
    }

    private suspend fun report(event: ScrobbleEvent) {
        repeat(2) { attempt ->
            if (event.epoch != epoch || !loggedIn) return
            val result = catalogResult { authenticated("scrobble/${event.action}", event.payload) }
            if (result.isSuccess) { _error.value = null; return }
            val error = result.exceptionOrNull()!!
            val status = (error as? TraktHttpException)?.status
            // Trakt rejects duplicate history and sub-1% stops intentionally.
            if (event.action == "stop" && status in setOf(409, 422)) return
            if (attempt == 1 || (status != null && status != 429 && status < 500)) throw error
            val wait = (error as? TraktHttpException)?.retryAfterSeconds ?: 1L
            if (wait > 30) throw error
            delay(wait.coerceAtLeast(1) * 1000)
        }
    }

    suspend fun sync(): List<TraktHistoryEntry> = coroutineScope {
        val currentEpoch = epoch
        val watchedMovies = async { authenticated("sync/watched/movies").jsonArray }
        val watchedShows = async { authenticated("sync/watched/shows").jsonArray }
        val playback = async { authenticated("sync/playback").jsonArray }
        val watching = async { authenticated("users/me/watching") }
        val rows = watchedMovies.await().map { it.jsonObject to "watched" } + watchedShows.await().map { it.jsonObject to "watched" } +
            playback.await().map { it.jsonObject to "paused" } + (watching.await() as? JsonObject)?.let { listOf(it to "watching") }.orEmpty()
        val entries = rows.mapNotNull { (row, state) ->
            val media = (row["movie"] ?: row["show"]) as? JsonObject ?: return@mapNotNull null
            val tmdb = media["ids"]?.jsonObject?.number("tmdb") ?: return@mapNotNull null
            val title = CatalogTitle(tmdb, if (row["movie"] != null) "movie" else "tv", title = media.text("title"), releaseDate = media.text("year"))
            TraktHistoryEntry(title, state, row.text("progress")?.toDoubleOrNull(), row.text("last_watched_at") ?: row.text("paused_at"))
        }.sortedByDescending { it.date }
        check(currentEpoch == epoch) { "Trakt session changed" }
        val encoded = buildJsonArray { entries.forEach { entry -> add(buildJsonObject {
            put("id", entry.title.id); put("type", entry.title.mediaType); put("title", entry.title.displayTitle)
            put("state", entry.state); put("progress", entry.progress); put("date", entry.date)
        }) } }
        prefs.edit().putString("entries", encoded.toString()).apply()
        entries
    }

    fun cachedHistory(): List<TraktHistoryEntry> = runCatching {
        CatalogNetwork.json.parseToJsonElement(prefs.getString("entries", "[]").orEmpty()).jsonArray.map { it.jsonObject }.map { row ->
            TraktHistoryEntry(CatalogTitle(row.number("id")!!, row.text("type")!!, title = row.text("title")), row.text("state")!!, row.text("progress")?.toDoubleOrNull(), row.text("date"))
        }
    }.getOrDefault(emptyList())

    suspend fun rating(title: CatalogTitle): Double? {
        if (clientId.isBlank()) return null
        val type = if (title.mediaType == "movie") "movie" else "show"
        val results = TraktApi(clientId).request("search/tmdb/${title.id}?type=$type").jsonArray
        val id = results.firstOrNull()?.jsonObject?.get(type)?.jsonObject?.get("ids")?.jsonObject?.number("trakt") ?: return null
        val response = TraktApi(clientId).request("${type}s/$id/ratings").jsonObject
        return response.text("rating")?.toDoubleOrNull()?.takeIf { (response.number("votes") ?: 0) > 0 }
    }

    private data class ScrobbleEvent(val epoch: Long, val action: String, val payload: JsonObject)
    companion object {
        @Volatile private var instance: TraktRepository? = null
        fun getInstance(context: Context): TraktRepository = instance ?: synchronized(this) {
            instance ?: TraktRepository(context.applicationContext).also { instance = it }
        }
    }
}

/** Episode provider ids identify the episode itself, never the parent series. */
fun buildTraktScrobblePayload(item: BaseItemDto, positionMs: Long, durationMs: Long): JsonObject? {
    val key = when (item.type) { "Movie" -> "movie"; "Episode" -> "episode"; else -> return null }
    val providers = item.providerIds.orEmpty().mapKeys { it.key.lowercase() }
    val ids = buildJsonObject {
        listOf("tmdb", "tvdb", "trakt").forEach { name -> providers[name]?.toIntOrNull()?.takeIf { it > 0 }?.let { put(name, it) } }
        providers["imdb"]?.takeIf { it.startsWith("tt") }?.let { put("imdb", it) }
    }
    if (ids.isEmpty() || durationMs <= 0) return null
    return buildJsonObject {
        put(key, buildJsonObject { put("ids", ids) })
        put("progress", (positionMs.toDouble() / durationMs * 100).coerceIn(0.0, 100.0))
    }
}

data class TraktDeviceCode(val code: String, val userCode: String, val verificationUrl: String, val interval: Int, val expiresIn: Int)
data class TraktHistoryEntry(val title: CatalogTitle, val state: String, val progress: Double?, val date: String?)
