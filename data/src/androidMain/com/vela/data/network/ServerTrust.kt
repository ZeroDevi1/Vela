package com.vela.data.network

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
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
    val replacesPinned: Boolean,
    /**
     * 换过的证书仍由同一个签发者（颁发者密钥标识 AKI 相同）签发。Peach 在本机地址变化或
     * 快到期时会用原 CA 重签叶子证书并换新密钥，这种情况属于正常重签；但没有 CA 证书时
     * 无法从密码学上确认，仍需用户确认。
     */
    val sameIssuerAsPinned: Boolean = false,
    /** 叶子证书的颁发者密钥标识（AKI），固定时一并记下，下次换证书时用来比对签发者。 */
    val issuerKeyId: String? = null
) {
    val hostPort: String get() = "$host:$port"
}

/** 用户导入的 CA 证书（例如 Peach 的 `peach-local-ca.crt`）。 */
data class TrustedAuthority(
    val sha256: String,
    val sha1: String,
    val subject: String,
    val notAfter: String
)

/** 证书不受系统信任，也不在用户信任的 CA 或固定指纹之内。带上证书摘要，界面据此询问。 */
class UntrustedServerCertificateException(
    val certificate: ServerCertificateInfo
) : CertificateException("服务器证书未受信任：${certificate.hostPort}")

/**
 * 自签证书的信任。按优先级依次校验：
 *
 * 1. 系统信任链（含 Android 网络安全配置）——永远先跑，行为与系统一致；
 * 2. 用户导入的 CA（推荐）：完整的链校验，Peach 用同一 CA 重签叶子证书后无需再确认；
 * 3. 首次信任（TOFU）：用户核对并固定的服务器公钥指纹，只对确认过的 `主机:端口` 生效。
 *
 * 主机名校验：系统校验通过即可；不通过时只接受固定过指纹的地址。
 */
object ServerTrust {
    private const val PREFS_NAME = "vela_server_trust"
    private const val PIN_PREFIX = "pin:"
    private const val PIN_ISSUER_PREFIX = "pin-aki:"
    private const val AUTHORITY_DIR = "trusted_authorities"
    private const val OID_AUTHORITY_KEY_ID = "2.5.29.35"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var authorityDir: File? = null

    @Volatile
    private var defaultsInstalled = false

    /** 导入 CA 的信任管理器；没有导入时为 null。导入或移除后重建。 */
    @Volatile
    private var authorityTrustManager: X509TrustManager? = null

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
     * 读取固定记录与导入的 CA，并把信任逻辑装到 `HttpsURLConnection` 的默认工厂上，
     * 让 ExoPlayer 的 DefaultHttpDataSource 等不经 OkHttp 的请求也生效。
     */
    fun install(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        if (authorityDir == null) {
            authorityDir = File(context.applicationContext.filesDir, AUTHORITY_DIR).apply { mkdirs() }
            rebuildAuthorityTrust()
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

    // region 导入的 CA

    /** 解析 PEM 或 DER 格式的 CA 证书，不写入。用于导入前展示指纹给用户核对。 */
    fun parseAuthority(bytes: ByteArray): Result<X509Certificate> = runCatching {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        require(certificate.basicConstraints >= 0) { "这不是 CA 证书。请选择 peach-local-ca.crt，而不是 peach.crt。" }
        certificate.checkValidity()
        certificate
    }

    fun describeAuthority(certificate: X509Certificate): TrustedAuthority = TrustedAuthority(
        sha256 = digest("SHA-256", certificate.encoded),
        sha1 = digest("SHA-1", certificate.encoded),
        subject = certificate.subjectX500Principal.name,
        notAfter = formatDate(certificate)
    )

    /** 用户核对指纹后导入。以 PEM 存在应用私有目录，按证书 SHA-256 去重。 */
    fun importAuthority(certificate: X509Certificate): TrustedAuthority {
        val dir = authorityDir ?: throw IllegalStateException("ServerTrust 未初始化")
        val info = describeAuthority(certificate)
        val pem = buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            append(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded))
            append("\n-----END CERTIFICATE-----\n")
        }
        File(dir, fileName(info.sha256)).writeText(pem)
        rebuildAuthorityTrust()
        return info
    }

    fun trustedAuthorities(): List<TrustedAuthority> = loadAuthorities().map(::describeAuthority)

    fun removeAuthority(sha256: String) {
        authorityDir?.let { File(it, fileName(sha256)).delete() }
        rebuildAuthorityTrust()
    }

    private fun fileName(sha256: String) = sha256.replace(":", "").lowercase(Locale.ROOT) + ".pem"

    private fun loadAuthorities(): List<X509Certificate> {
        val files = authorityDir?.listFiles { file -> file.extension == "pem" }.orEmpty()
        val factory = CertificateFactory.getInstance("X.509")
        return files.sortedBy { it.name }.mapNotNull { file ->
            runCatching { factory.generateCertificate(file.inputStream().buffered()) as X509Certificate }.getOrNull()
        }
    }

    private fun rebuildAuthorityTrust() {
        val authorities = loadAuthorities()
        authorityTrustManager = if (authorities.isEmpty()) {
            null
        } else {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                authorities.forEachIndexed { index, certificate -> setCertificateEntry("vela-ca-$index", certificate) }
            }
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(store) }
                .trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        }
    }

    private fun trustedByAuthority(chain: Array<out X509Certificate>?, authType: String?): Boolean {
        val manager = authorityTrustManager ?: return false
        if (chain.isNullOrEmpty()) return false
        return runCatching { manager.checkServerTrusted(chain, authType ?: "RSA") }.isSuccess
    }

    // endregion

    // region 首次信任（固定指纹）

    fun pin(certificate: ServerCertificateInfo) {
        val key = certificate.hostPort.lowercase(Locale.ROOT)
        prefs?.edit()
            ?.putString(PIN_PREFIX + key, certificate.fingerprint)
            ?.apply {
                if (certificate.issuerKeyId != null) putString(PIN_ISSUER_PREFIX + key, certificate.issuerKeyId)
                else remove(PIN_ISSUER_PREFIX + key)
            }
            ?.apply()
    }

    fun pinnedFingerprint(host: String, port: Int): String? =
        prefs?.getString(PIN_PREFIX + "$host:$port".lowercase(Locale.ROOT), null)

    fun removePin(host: String, port: Int) {
        val key = "$host:$port".lowercase(Locale.ROOT)
        prefs?.edit()?.remove(PIN_PREFIX + key)?.remove(PIN_ISSUER_PREFIX + key)?.apply()
    }

    // endregion

    fun OkHttpClient.Builder.applyServerTrust(): OkHttpClient.Builder =
        sslSocketFactory(sslSocketFactory, trustManager).hostnameVerifier(hostnameVerifier)

    /** 错误链里是否有「证书不受信任」，用来决定是否走证书确认流程。 */
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

    fun fingerprint(certificate: X509Certificate): String = digest("SHA-256", certificate.publicKey.encoded)

    /** WebView 等拿不到握手会话的地方用：与 OkHttp 同一套判据（导入的 CA 或固定指纹）。 */
    fun isTrustedForHost(certificate: X509Certificate, host: String, port: Int): Boolean =
        trustedByAuthority(arrayOf(certificate), certificate.publicKey.algorithm) ||
            pinnedFingerprint(host, port) == fingerprint(certificate)

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString(":") { byte -> "%02X".format(byte) }

    private fun formatDate(certificate: X509Certificate): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(certificate.notAfter)

    private fun describe(host: String, port: Int, leaf: X509Certificate): ServerCertificateInfo {
        val fingerprint = fingerprint(leaf)
        val key = "$host:$port".lowercase(Locale.ROOT)
        val pinned = pinnedFingerprint(host, port)
        val pinnedIssuer = prefs?.getString(PIN_ISSUER_PREFIX + key, null)
        val issuerKeyId = authorityKeyId(leaf)
        return ServerCertificateInfo(
            host = host,
            port = port,
            fingerprint = fingerprint,
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notAfter = formatDate(leaf),
            replacesPinned = pinned != null && pinned != fingerprint,
            sameIssuerAsPinned = pinned != null && issuerKeyId != null && issuerKeyId == pinnedIssuer,
            issuerKeyId = issuerKeyId
        )
    }

    /** 颁发者密钥标识（AKI）的 keyIdentifier 字段，十六进制；没有该扩展返回 null。 */
    internal fun authorityKeyId(certificate: X509Certificate): String? {
        val wrapped = certificate.getExtensionValue(OID_AUTHORITY_KEY_ID) ?: return null
        val sequence = derContent(wrapped, expectedTag = 0x04) ?: return null
        val inner = derContent(sequence, expectedTag = 0x30) ?: return null
        val keyId = derContent(inner, expectedTag = 0x80) ?: return null
        return keyId.joinToString("") { "%02X".format(it) }
    }

    /** 取 DER 元素的内容；只支持本处用到的单字节标签与短/长长度形式。 */
    private fun derContent(bytes: ByteArray, expectedTag: Int): ByteArray? {
        if (bytes.size < 2 || (bytes[0].toInt() and 0xFF) != expectedTag) return null
        var length = bytes[1].toInt() and 0xFF
        var offset = 2
        if (length and 0x80 != 0) {
            val count = length and 0x7F
            if (count == 0 || count > 3 || bytes.size < 2 + count) return null
            length = 0
            repeat(count) { index -> length = (length shl 8) or (bytes[2 + index].toInt() and 0xFF) }
            offset = 2 + count
        }
        if (offset + length > bytes.size) return null
        return bytes.copyOfRange(offset, offset + length)
    }

    private fun matchesPin(session: SSLSession?): Boolean {
        session ?: return false
        val host = session.peerHost ?: return false
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull() ?: return false
        return pinnedFingerprint(host, session.peerPort) == fingerprint(leaf)
    }

    private fun checkFallback(
        chain: Array<out X509Certificate>?,
        authType: String?,
        host: String?,
        port: Int,
        systemError: CertificateException
    ) {
        if (trustedByAuthority(chain, authType)) return
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
            try {
                system.checkServerTrusted(chain, authType)
            } catch (error: CertificateException) {
                if (!trustedByAuthority(chain, authType)) throw error
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            try {
                if (extended != null) extended.checkServerTrusted(chain, authType, socket)
                else system.checkServerTrusted(chain, authType)
            } catch (error: CertificateException) {
                val session = (socket as? SSLSocket)?.handshakeSession
                val host = session?.peerHost ?: socket?.inetAddress?.hostName
                val port = session?.peerPort?.takeIf { it > 0 } ?: socket?.port ?: 443
                checkFallback(chain, authType, host, port, error)
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            try {
                if (extended != null) extended.checkServerTrusted(chain, authType, engine)
                else system.checkServerTrusted(chain, authType)
            } catch (error: CertificateException) {
                checkFallback(chain, authType, engine?.peerHost, engine?.peerPort ?: 443, error)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            system.acceptedIssuers + (authorityTrustManager?.acceptedIssuers ?: emptyArray())
    }
}
