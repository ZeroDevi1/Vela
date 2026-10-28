package com.vela.data.network

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/** 服务器证书摘要，供首次连接时给用户核对。 */
data class ServerCertificateInfo(
    val host: String,
    val port: Int,
    /** 服务器证书公钥（SPKI）的 SHA-256，冒号分隔的大写十六进制。 */
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val notAfter: String,
    /** 这个地址之前固定过别的指纹：证书换过了，需要重新确认。 */
    val replacesPinned: Boolean
) {
    val hostPort: String get() = "$host:$port"
}

/** 证书不受系统信任且未被用户固定。带上证书摘要，界面据此询问是否信任。 */
class UntrustedServerCertificateException(
    val certificate: ServerCertificateInfo
) : CertificateException("服务器证书未受信任：${certificate.hostPort}")

/**
 * 自签证书的首次信任（TOFU）。Peach 源码部署用本机自签 CA 签发证书，系统不认；
 * 用户在首次连接时核对指纹并确认后，这里按 `主机:端口` 固定服务器公钥指纹。
 *
 * 系统信任链校验永远先跑；只有系统拒绝、且证书公钥与固定指纹一致时才放行。
 * 固定只覆盖用户确认过的地址，其余地址的校验行为与系统一致。
 */
object ServerTrust {
    private const val PREFS_NAME = "vela_server_trust"
    private const val PIN_PREFIX = "pin:"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var defaultsInstalled = false

    private val systemTrustManager: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private val systemHostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

    val trustManager: X509TrustManager by lazy { PinningTrustManager() }

    val sslSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    val hostnameVerifier = HostnameVerifier { hostname, session ->
        systemHostnameVerifier.verify(hostname, session) || matchesPin(session)
    }

    /**
     * 读取固定记录，并把固定逻辑装到 `HttpsURLConnection` 的默认工厂上，
     * 让 ExoPlayer 的 DefaultHttpDataSource 等不经 OkHttp 的请求也认用户确认过的证书。
     */
    fun install(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        if (!defaultsInstalled) {
            synchronized(this) {
                if (!defaultsInstalled) {
                    HttpsURLConnection.setDefaultSSLSocketFactory(sslSocketFactory)
                    HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier)
                    defaultsInstalled = true
                }
            }
        }
    }

    fun pin(certificate: ServerCertificateInfo) {
        prefs?.edit()?.putString(PIN_PREFIX + certificate.hostPort.lowercase(Locale.ROOT), certificate.fingerprint)?.apply()
    }

    fun pinnedFingerprint(host: String, port: Int): String? =
        prefs?.getString(PIN_PREFIX + "$host:$port".lowercase(Locale.ROOT), null)

    fun removePin(host: String, port: Int) {
        prefs?.edit()?.remove(PIN_PREFIX + "$host:$port".lowercase(Locale.ROOT))?.apply()
    }

    fun OkHttpClient.Builder.applyServerTrust(): OkHttpClient.Builder =
        sslSocketFactory(sslSocketFactory, trustManager).hostnameVerifier(hostnameVerifier)

    /** 错误链里是否有「证书不受信任」，用来决定是否走指纹确认流程。 */
    fun isCertificateFailure(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth < 8) {
            if (current is CertificateException) return true
            if (current is javax.net.ssl.SSLPeerUnverifiedException) return true
            if (current is javax.net.ssl.SSLHandshakeException &&
                current.message.orEmpty().contains("trust anchor", ignoreCase = true)
            ) return true
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * 只为读取证书做一次不校验的 TLS 握手，不发送任何请求数据，也不携带凭据。
     * 拿到的证书只用于给用户展示指纹，用户确认后才会被固定。
     */
    fun inspect(url: String, timeoutMs: Int = 6_000): ServerCertificateInfo? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val inspector = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(inspector), null) }
        return runCatching {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(host, port), timeoutMs)
                raw.soTimeout = timeoutMs
                (context.socketFactory.createSocket(raw, host, port, true) as SSLSocket).use { socket ->
                    socket.startHandshake()
                    val leaf = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                        ?: return@runCatching null
                    describe(host, port, leaf)
                }
            }
        }.getOrNull()
    }

    fun fingerprint(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        return digest.joinToString(":") { byte -> "%02X".format(byte) }
    }

    private fun describe(host: String, port: Int, leaf: X509Certificate): ServerCertificateInfo {
        val fingerprint = fingerprint(leaf)
        val pinned = pinnedFingerprint(host, port)
        return ServerCertificateInfo(
            host = host,
            port = port,
            fingerprint = fingerprint,
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notAfter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(leaf.notAfter),
            replacesPinned = pinned != null && pinned != fingerprint
        )
    }

    private fun matchesPin(session: SSLSession?): Boolean {
        session ?: return false
        val host = session.peerHost ?: return false
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull() ?: return false
        return pinnedFingerprint(host, session.peerPort) == fingerprint(leaf)
    }

    private fun checkPinned(chain: Array<out X509Certificate>?, host: String?, port: Int, systemError: CertificateException) {
        val leaf = chain?.firstOrNull() ?: throw systemError
        if (host == null) throw systemError
        val pinned = pinnedFingerprint(host, port)
        if (pinned != null && pinned == fingerprint(leaf)) return
        throw UntrustedServerCertificateException(describe(host, port, leaf)).apply { initCause(systemError) }
    }

    private class PinningTrustManager : X509ExtendedTrustManager() {
        private val system = systemTrustManager
        private val extended = system as? X509ExtendedTrustManager

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            system.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            extended?.checkClientTrusted(chain, authType, socket) ?: system.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            extended?.checkClientTrusted(chain, authType, engine) ?: system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            system.checkServerTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            try {
                if (extended != null) extended.checkServerTrusted(chain, authType, socket)
                else system.checkServerTrusted(chain, authType)
            } catch (error: CertificateException) {
                val session = (socket as? SSLSocket)?.handshakeSession
                val host = session?.peerHost ?: socket?.inetAddress?.hostName
                val port = session?.peerPort?.takeIf { it > 0 } ?: socket?.port ?: 443
                checkPinned(chain, host, port, error)
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            try {
                if (extended != null) extended.checkServerTrusted(chain, authType, engine)
                else system.checkServerTrusted(chain, authType)
            } catch (error: CertificateException) {
                checkPinned(chain, engine?.peerHost, engine?.peerPort ?: 443, error)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }
}
