package com.vela.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.TimeSource

/** 进程内有界缓存。断网时短暂熔断所有 TMDB 请求，并允许使用一天内的旧数据。 */
internal class TmdbRequestCache(
    private val now: () -> Long = monotonicClock()
) {
    private data class Entry(val value: String, val savedAt: Long)
    private val entries = linkedMapOf<String, Entry>()
    private val state = Mutex()
    // 固定数量的锁避免为大量不同搜索词永久保留 Mutex。
    private val keys = List(32) { Mutex() }
    private val gate = Semaphore(2)
    private var unavailableUntil = 0L

    suspend fun get(key: String, load: suspend () -> String): String =
        keys[(key.hashCode() and Int.MAX_VALUE) % keys.size].withLock {
            val cached = state.withLock { entries[key] }
            if (cached != null && now() - cached.savedAt < FRESH_MS) return@withLock cached.value
            try {
                gate.withPermit {
                    // 排队中的请求也必须检查熔断，防止失败后继续冲击不可达的服务。
                    state.withLock {
                        check(now() >= unavailableUntil) { "TMDB 暂时不可用，请稍后重试" }
                    }
                    val value = try {
                        load()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e !is TmdbHttpException || e.status == 429 || e.status >= 500) {
                            state.withLock {
                                val cooldown = (e as? TmdbHttpException)?.retryAfterMs.orDefaultCooldown()
                                unavailableUntil = maxOf(unavailableUntil, now() + cooldown)
                            }
                        }
                        throw e
                    }
                    state.withLock {
                        entries.remove(key)
                        entries[key] = Entry(value, now())
                        while (entries.size > 128) entries.remove(entries.keys.first())
                    }
                    value
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cached != null && now() - cached.savedAt < STALE_MS &&
                    (e !is TmdbHttpException || e.status == 429 || e.status >= 500)) cached.value
                else throw e
            }
        }

    private fun Long?.orDefaultCooldown(): Long = (this ?: 60_000L).coerceIn(60_000L, 3_600_000L)

    private companion object {
        const val FRESH_MS = 30 * 60_000L
        const val STALE_MS = 24 * 60 * 60_000L
        fun monotonicClock(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}

internal class TmdbHttpException(val status: Int, val retryAfterMs: Long? = null) :
    IllegalStateException("TMDB HTTP $status")
