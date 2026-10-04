package com.vela.data.update

import android.content.Context
import android.os.Build
import android.util.Log
import com.vela.data.preferences.NetworkPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class AppUpdateCheckException(message: String) : IOException(message)

class AppUpdateRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppUpdatePreferences(appContext)
    private val networkPreferences = NetworkPreferences(appContext)

    fun currentFlavor(): AppFlavor = AppFlavor.fromPackageName(appContext.packageName)

    fun deviceAbis(): List<String> = Build.SUPPORTED_ABIS.toList()

    fun currentMirror(): DownloadMirror = preferences.getMirror()

    fun customPrefix(): String = preferences.getCustomPrefix()

    fun setMirrorId(id: String) {
        preferences.setMirrorId(id)
    }

    fun setCustomPrefix(prefix: String) {
        preferences.setCustomPrefix(prefix)
    }

    /**
     * 依次尝试镜像候选，最后直连。代理可能 403、超时或返回 HTML 错误页，这些都视为该候选失败；
     * 只有直连 404 才说明仓库确实没有 Release。
     */
    suspend fun checkLatest(): AppUpdateRelease = withContext(Dispatchers.IO) {
        val prefixes = mirrorCandidatePrefixes(preferences.getMirror(), forApi = true)
        var lastError: Exception? = null
        for (prefix in prefixes) {
            currentCoroutineContext().ensureActive()
            val isDirect = prefix.isEmpty()
            val request = Request.Builder()
                .url(accelerateGithubUrl(GITHUB_LATEST_RELEASE_URL, prefix))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            try {
                val client = if (isDirect) apiClient() else apiClient(MIRROR_API_TIMEOUT_MS)
                return@withContext client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (response.code == 404 && isDirect) {
                        throw AppUpdateCheckException("NO_RELEASE")
                    }
                    if (!response.isSuccessful) {
                        throw AppUpdateCheckException("HTTP ${response.code}")
                    }
                    parseGithubReleaseJson(body)
                }
            } catch (error: AppUpdateCheckException) {
                if (error.message == "NO_RELEASE") throw error
                lastError = error
            } catch (error: IOException) {
                lastError = error
            } catch (error: SerializationException) {
                lastError = AppUpdateCheckException("Invalid response")
            }
        }
        throw lastError ?: AppUpdateCheckException("No mirror available")
    }

    /**
     * 下载 APK。自动模式先并发测速、按吞吐排序；任一候选失败（含大小与 Release 不符）就换下一个，
     * 全部失败时抛出最后一个错误。
     */
    suspend fun download(
        asset: AppUpdateAsset,
        onProgress: (received: Long, total: Long?) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val mirror = preferences.getMirror()
        val candidates = mirrorCandidatePrefixes(mirror, forApi = false)
        val prefixes = if (mirror.id == AUTO_DOWNLOAD_MIRROR_ID) {
            rankByProbe(asset, candidates)
        } else {
            candidates
        }
        var lastError: IOException? = null
        for (prefix in prefixes) {
            currentCoroutineContext().ensureActive()
            try {
                return@withContext downloadFrom(accelerateGithubUrl(asset.downloadUrl, prefix), asset, onProgress)
            } catch (error: IOException) {
                Log.w(TAG, "Update download failed via ${prefix.ifEmpty { "direct" }}: ${error.message}")
                lastError = error
            }
        }
        throw lastError ?: AppUpdateCheckException("No mirror available")
    }

    private suspend fun downloadFrom(
        url: String,
        asset: AppUpdateAsset,
        onProgress: (received: Long, total: Long?) -> Unit
    ): File {
        val target = File(updatesDir(), asset.name)
        if (target.exists()) {
            target.delete()
        }
        val temp = File(target.parentFile, "${target.name}.part")
        if (temp.exists()) {
            temp.delete()
        }

        val expectedSize = asset.sizeBytes.takeIf { it > 0 }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val received = downloadClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AppUpdateCheckException("HTTP ${response.code}")
            }
            val body = response.body
            val contentLength = body.contentLength().takeIf { it > 0 }
            if (expectedSize != null && contentLength != null && contentLength != expectedSize) {
                // 代理出错时常返回 200 + HTML 错误页，不能当成 APK 存下来。
                throw AppUpdateCheckException("Size mismatch")
            }
            val total = contentLength ?: expectedSize
            onProgress(0, total)
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var received = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        received += read
                        onProgress(received, total)
                    }
                    output.flush()
                    received
                }
            }
        }
        if (expectedSize != null && received != expectedSize) {
            temp.delete()
            throw AppUpdateCheckException("Incomplete download")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    /**
     * 并发下载每个候选的前 [PROBE_BYTES] 字节，按吞吐从高到低排序；测速失败的候选排在后面仍可兜底，
     * 直连始终保留在列表中。测速在设备上进行，网络环境（国内/代理/IPv6）不同结果不同。
     */
    private suspend fun rankByProbe(asset: AppUpdateAsset, prefixes: List<String>): List<String> = coroutineScope {
        val speeds = prefixes.map { prefix ->
            async { prefix to probeSpeed(accelerateGithubUrl(asset.downloadUrl, prefix), asset.sizeBytes) }
        }.awaitAll()
        val ranked = speeds.filter { it.second != null }.sortedByDescending { it.second }.map { it.first }
        Log.i(TAG, "Update mirror probe: " + speeds.joinToString { "${it.first.ifEmpty { "direct" }}=${it.second}" })
        ranked + prefixes.filterNot { it in ranked }
    }

    /** 返回字节/秒；状态码、总大小不符或超时返回 null。 */
    private fun probeSpeed(url: String, expectedSize: Long): Long? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-${PROBE_BYTES - 1}")
            .get()
            .build()
        val startedAt = System.nanoTime()
        return try {
            probeClient().newCall(request).execute().use { response ->
                val totalSize = when (response.code) {
                    206 -> response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    200 -> response.body.contentLength().takeIf { it > 0 }
                    else -> return null
                }
                if (expectedSize > 0 && totalSize != null && totalSize != expectedSize) return null
                var read = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                response.body.byteStream().use { input ->
                    while (read < PROBE_BYTES) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        read += count
                    }
                }
                val minBytes = if (expectedSize > 0) minOf(PROBE_BYTES, expectedSize) else PROBE_BYTES
                if (read < minBytes) return null
                val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000).coerceAtLeast(1)
                read * 1000 / elapsedMs
            }
        } catch (error: IOException) {
            null
        }
    }

    fun updatesDir(): File {
        val dir = File(appContext.cacheDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun apiClient(callTimeoutMs: Long? = null): OkHttpClient {
        val timeouts = networkPreferences.getTimeoutConfig()
        return OkHttpClient.Builder()
            .callTimeout(callTimeoutMs ?: timeouts.requestTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(timeouts.connectionTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.socketTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeouts.socketTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun downloadClient(): OkHttpClient {
        val timeouts = networkPreferences.getTimeoutConfig()
        return OkHttpClient.Builder()
            .connectTimeout(timeouts.connectionTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(timeouts.socketTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun probeClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    companion object {
        private const val TAG = "AppUpdate"
        private const val USER_AGENT = "Vela-AppUpdate"
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        /** 测速样本 512 KiB：足以越过 TCP 慢启动，又不至于在慢线路上拖太久。 */
        private const val PROBE_BYTES = 512L * 1024
        /** 单个候选测速上限；超时视为不可用，不阻塞其余候选。 */
        private const val PROBE_TIMEOUT_MS = 8_000L
        /** 镜像 API 请求上限，失败后尽快回退到下一个候选 / 直连。 */
        private const val MIRROR_API_TIMEOUT_MS = 8_000L
    }
}
