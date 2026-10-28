package com.vela.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.vela.data.R
import com.vela.data.DataModuleConfig
import com.vela.data.api.MediaServerApi
import com.vela.data.api.MediaServerApiClient
import com.vela.data.model.AuthHeaderDto
import com.vela.data.model.ServerInfo
import com.vela.data.network.ServerTrust.applyServerTrust
import com.vela.data.peach.PeachApiException
import com.vela.data.peach.PeachClient
import com.vela.data.peach.PeachCredential
import com.vela.data.peach.PeachMediaServerApi
import com.vela.data.peach.PeachServerVersion
import com.vela.data.preferences.NetworkTimeoutConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object NetworkModule {

    private const val CLIENT_NAME = "Vela"
    private const val DEVICE_NAME = "Android"
    private const val NETWORK_LOG_TAG = "VelaNetwork"
    private const val OFFLINE_DEBOUNCE_MS = 4000L
    private val deviceId by lazy { "vela-android-${UUID.randomUUID()}" }
    private val apiCache = ConcurrentHashMap<String, MediaServerApi>()
    private val suppressLineFailover = AtomicInteger(0)

    @Volatile
    private var lineFailureReporter: ((String) -> Unit)? = null

    fun setLineFailureReporter(reporter: ((String) -> Unit)?) {
        lineFailureReporter = reporter
    }

    suspend fun <T> withoutLineFailover(block: suspend () -> T): T {
        suppressLineFailover.incrementAndGet()
        try {
            return block()
        } finally {
            suppressLineFailover.decrementAndGet()
        }
    }

    fun getClientDeviceId(): String = deviceId

    fun isInternetAvailable(context: Context): Boolean {
        val connectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun currentNetworkAccess(context: Context): NetworkAccess {
        val connectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkAccess.OFFLINE
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return NetworkAccess.OFFLINE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                NetworkAccess.LAN_CAPABLE
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> {
                NetworkAccess.WAN
            }
            else -> NetworkAccess.OFFLINE
        }
    }

    fun observeNetworkAccess(context: Context): Flow<NetworkAccess> = callbackFlow {
        val appContext = context.applicationContext
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        fun emitAccess() {
            trySend(currentNetworkAccess(appContext))
        }

        emitAccess()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = emitAccess()
            override fun onLost(network: Network) = emitAccess()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) = emitAccess()
            override fun onUnavailable() = emitAccess()
        }

        val request = NetworkRequest.Builder().build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
        }.onFailure {
            emitAccess()
        }

        awaitClose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    fun observeNetworkAvailability(context: Context): Flow<Boolean> = callbackFlow {
        val appContext = context.applicationContext
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var OfflineEmission: Job? = null

        trySend(isInternetAvailable(appContext))

        fun OfflineDebounce() {
            if (isInternetAvailable(appContext)) {
                OfflineEmission?.cancel()
                OfflineEmission = null
                trySend(true)
                return
            }

            if (OfflineEmission?.isActive == true) {
                return
            }

            OfflineEmission = launch {
                delay(OFFLINE_DEBOUNCE_MS)
                if (!isInternetAvailable(appContext)) {
                    trySend(false)
                }
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                OfflineDebounce()
            }

            override fun onLost(network: Network) {
                OfflineDebounce()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                OfflineDebounce()
            }

            override fun onUnavailable() {
                OfflineDebounce()
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
        }.onFailure {
            trySend(isInternetAvailable(appContext))
        }

        awaitClose {
            OfflineEmission?.cancel()
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    fun createMediaServerApi(
        baseUrl: String,
        accessToken: String? = null,
        serverType: ServerType? = null,
        storageDir: File? = null,
        timeoutConfig: NetworkTimeoutConfig? = null
    ): MediaServerApi {
        val baseUrlStd = trimTrailingSlash(baseUrl, trailingSlash = true)
        val endpointType = serverType ?: inferServerType(baseUrlStd)
        val resolvedTimeoutConfig = timeoutConfig ?: defaultTimeoutConfig()
        val cacheKey = buildString {
            append(trimTrailingSlash(baseUrlStd))
            append("|")
            append(accessToken.orEmpty())
            append("|")
            append(endpointType.name)
            append("|")
            append(resolvedTimeoutConfig.requestTimeoutMs)
            append("|")
            append(resolvedTimeoutConfig.connectionTimeoutMs)
            append("|")
            append(resolvedTimeoutConfig.socketTimeoutMs)
        }
        apiCache[cacheKey]?.let { return it }

        if (endpointType == ServerType.PEACH) {
            return createPeachApi(
                baseUrl = baseUrlStd,
                credential = PeachCredential.decode(accessToken) ?: PeachCredential.Open,
                timeoutConfig = resolvedTimeoutConfig
            ).also { apiCache[cacheKey] = it }
        }

        val okHttpClient = createOkHttpClient(
            accessToken = accessToken,
            serverType = endpointType,
            storageDir = storageDir,
            timeoutConfig = resolvedTimeoutConfig
        )
        val httpClient = createHttpClient(
            baseUrl = baseUrlStd,
            okHttpClient = okHttpClient
        )

        return MediaServerApiClient(
            client = httpClient,
            serverType = endpointType,
            baseUrl = baseUrlStd
        ).also { apiCache[cacheKey] = it }
    }

    suspend fun serverEndpoint(
        context: Context,
        serverUrl: String,
        storageDir: File? = null,
        timeoutConfig: NetworkTimeoutConfig? = null
    ): Result<ServerEndpoint> {
        val candidates = buildBaseUrlCandidates(serverUrl)
        var lastError: Exception? = null

        for (candidate in candidates) {
            try {
                val api = createMediaServerApi(
                    baseUrl = candidate,
                    storageDir = storageDir,
                    timeoutConfig = timeoutConfig
                )
                val response = api.getPublicSystemInfo()
                if (response.isSuccessful && response.body() != null) {
                    val serverInfo = response.body()!!
                    val detectedType = detectServerType(serverInfo, response.headers())
                    return Result.success(
                        ServerEndpoint(
                            baseUrl = trimTrailingSlash(candidate, trailingSlash = true),
                            serverType = detectedType,
                            serverInfo = serverInfo
                        )
                    )
                }

                lastError = Exception(
                    context.getString(R.string.data_error_server_endpoint_http, response.code())
                )
            } catch (e: Exception) {
                lastError = e
            }
        }

        probePeach(serverUrl, timeoutConfig).onSuccess { return Result.success(it) }
            .onFailure { error -> if (ServerTrust.isCertificateFailure(error)) lastError = error as? Exception ?: lastError }

        val certificateError = lastError?.takeIf { ServerTrust.isCertificateFailure(it) }
        if (certificateError != null) {
            ServerTrust.inspect(trimTrailingSlash(serverUrl.trim()))?.let { certificate ->
                return Result.failure(UntrustedServerCertificateException(certificate).apply { initCause(certificateError) })
            }
        }

        return Result.failure(
            lastError ?: Exception(context.getString(R.string.data_error_server_endpoint_unresolved))
        )
    }

    /**
     * 识别 Peach：`/api/client-updates` 在开放访问时直接回版本；设了口令时回 401，
     * 再看 `/login` 页是不是 Peach 的登录页。两步都不发送凭据。
     */
    suspend fun probePeach(
        serverUrl: String,
        timeoutConfig: NetworkTimeoutConfig? = null
    ): Result<ServerEndpoint> {
        val baseUrl = trimTrailingSlash(serverUrl.trim(), trailingSlash = true)
        val probe = createPeachClient(baseUrl, PeachCredential.Open, timeoutConfig ?: defaultTimeoutConfig())
        return try {
            val version = PeachServerVersion.parse(probe.get("/api/client-updates"))
            if (version.version == null) {
                Result.failure(Exception("not peach"))
            } else {
                Result.success(peachEndpoint(baseUrl, version.version))
            }
        } catch (error: PeachApiException) {
            if (error.kind != PeachApiException.Kind.UNAUTHORIZED) return Result.failure(error)
            val loginPage = runCatching { probe.getText("/login") }.getOrNull().orEmpty()
            if (loginPage.contains("Peach", ignoreCase = true)) {
                Result.success(peachEndpoint(baseUrl, version = null))
            } else {
                Result.failure(error)
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun peachEndpoint(baseUrl: String, version: String?) = ServerEndpoint(
        baseUrl = baseUrl,
        serverType = ServerType.PEACH,
        serverInfo = ServerInfo(serverName = "Peach", productName = PeachMediaServerApi.PRODUCT_NAME, version = version)
    )

    /** Peach 的请求客户端：只带 Peach 自己的凭据头，不加 Jellyfin 的 Authorization。 */
    fun createPeachClient(
        baseUrl: String,
        credential: PeachCredential,
        timeoutConfig: NetworkTimeoutConfig? = null
    ): PeachClient {
        val resolvedTimeoutConfig = timeoutConfig ?: defaultTimeoutConfig()
        val okHttpClient = createPeachOkHttpClient(credential, resolvedTimeoutConfig)
        val baseUrlStd = trimTrailingSlash(baseUrl, trailingSlash = true)
        return PeachClient(
            client = createHttpClient(baseUrlStd, okHttpClient),
            baseUrl = baseUrlStd,
            loginClient = createHttpClient(
                baseUrlStd,
                okHttpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
            )
        )
    }

    fun createPeachApi(
        baseUrl: String,
        credential: PeachCredential,
        timeoutConfig: NetworkTimeoutConfig? = null
    ): PeachMediaServerApi = PeachMediaServerApi(createPeachClient(baseUrl, credential, timeoutConfig))

    private fun createPeachOkHttpClient(
        credential: PeachCredential,
        timeoutConfig: NetworkTimeoutConfig
    ): OkHttpClient {
        val headers = credential.headers()
        val authInterceptor = Interceptor { chain ->
            val builder = chain.request().newBuilder()
            headers.forEach { (name, value) -> builder.header(name, value) }
            chain.proceed(builder.build())
        }
        return OkHttpClient.Builder()
            .callTimeout(timeoutConfig.requestTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutConfig.connectionTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutConfig.socketTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutConfig.socketTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .applyServerTrust()
            .addInterceptor(authInterceptor)
            .addInterceptor(lineFailureInterceptor())
            .build()
    }

    private fun createOkHttpClient(
        accessToken: String? = null,
        serverType: ServerType,
        storageDir: File? = null,
        timeoutConfig: NetworkTimeoutConfig
    ): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 32
        }

        val builder = OkHttpClient.Builder()
            .callTimeout(timeoutConfig.requestTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutConfig.connectionTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutConfig.socketTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutConfig.socketTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .applyServerTrust()

        if (storageDir != null) {
            val networkStore = File(storageDir, "media_store/network_http_cache")
            if (!networkStore.exists()) {
                networkStore.mkdirs()
            }
            builder.cache(Cache(networkStore, 64L * 1024L * 1024L))
        }

        val authInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val authHeader = buildAuthHeader(accessToken, deviceId, serverType)

            val newRequest = originalRequest.newBuilder()
                .addHeader("Authorization", authHeader)
                .addHeader("X-Emby-Authorization", authHeader)
                .addHeader("Content-Type", "application/json")
                .build()

            chain.proceed(newRequest)
        }

        val cacheInterceptor = Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val isGet = request.method.equals("GET", ignoreCase = true)
            val path = request.url.encodedPath
            val isUserScopedData = path.contains("/Users/", ignoreCase = true)
            val cacheControl = response.header("Cache-Control").orEmpty()
            val hasExplicitCaching =
                cacheControl.contains("max-age", ignoreCase = true) ||
                    cacheControl.contains("no-store", ignoreCase = true) ||
                    cacheControl.contains("no-cache", ignoreCase = true)

            if (isGet && response.isSuccessful && !hasExplicitCaching && !isUserScopedData) {
                response.newBuilder()
                    .header("Cache-Control", "public, max-age=60")
                    .build()
            } else {
                response
            }
        }

        val timingInterceptor = Interceptor { chain ->
            val request = chain.request()
            val startNanos = System.nanoTime()
            try {
                val response = chain.proceed(request)
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                val endpoint = request.url.encodedPath
                Log.d(
                    NETWORK_LOG_TAG,
                    "${request.method} $endpoint -> ${response.code} in ${durationMs}ms"
                )
                response
            } catch (e: Exception) {
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                val endpoint = request.url.encodedPath
                Log.w(
                    NETWORK_LOG_TAG,
                    "${request.method} $endpoint failed in ${durationMs}ms: ${e.message}"
                )
                throw e
            }
        }

        builder.addInterceptor(authInterceptor)
        builder.addNetworkInterceptor(cacheInterceptor)
        builder.addInterceptor(timingInterceptor)
        builder.addInterceptor(lineFailureInterceptor())

        return builder.build()
    }

    private fun createHttpClient(
        baseUrl: String,
        okHttpClient: OkHttpClient
    ): HttpClient {
        return HttpClient(OkHttp) {
            expectSuccess = false
            engine {
                preconfigured = okHttpClient
            }
            defaultRequest {
                url(baseUrl)
            }
            install(ContentNegotiation) {
                json(VelaJson)
            }
        }
    }

    private fun lineFailureInterceptor(): Interceptor {
        return Interceptor { chain ->
            try {
                chain.proceed(chain.request())
            } catch (error: IOException) {
                if (shouldReportLineFailure(chain, error)) {
                    lineFailureReporter?.invoke(chain.request().url.toString())
                }
                throw error
            }
        }
    }

    private fun shouldReportLineFailure(
        chain: Interceptor.Chain,
        error: IOException
    ): Boolean {
        if (suppressLineFailover.get() != 0) return false
        if (chain.call().isCanceled()) return false
        return error is SocketTimeoutException ||
            error is ConnectException ||
            error is UnknownHostException ||
            error is NoRouteToHostException
    }

    private fun defaultTimeoutConfig(): NetworkTimeoutConfig {
        return NetworkTimeoutConfig(
            requestTimeoutMs = 30000,
            connectionTimeoutMs = 6000,
            socketTimeoutMs = 10000
        )
    }

    private fun buildAuthHeader(
        accessToken: String?,
        deviceId: String,
        serverType: ServerType
    ): String {
        return AuthHeaderDto.fromServerType(
            serverType = serverType,
            deviceId = deviceId,
            version = DataModuleConfig.CLIENT_VERSION,
            accessToken = accessToken,
            clientName = CLIENT_NAME,
            deviceName = DEVICE_NAME
        ).asHeaderValue()
    }

}