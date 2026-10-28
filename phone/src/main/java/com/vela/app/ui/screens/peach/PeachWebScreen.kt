package com.vela.app.ui.screens.peach

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.vela.data.network.ServerTrust
import com.vela.data.peach.PeachCredential
import com.vela.data.peach.PeachRepository
import com.vela.data.peach.PeachWebEntry
import com.vela.shared.R

/**
 * 在应用内打开 Peach 网页，承接 Vela 没有原生化的功能（人工复核、重复文件、JAV 探索等）。
 * 口令登录用 `?t=` 建立会话，密码登录直接写入会话 Cookie；口令不会进入外部浏览器的历史。
 * 自签证书只接受用户在登录时核对并固定过的那一张。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PeachWebScreen(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var entry by remember { mutableStateOf<PeachWebEntry?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf("Peach") }
    LaunchedEffect(path) {
        runCatching { PeachRepository.getInstance(context).webEntry(path) }
            .onSuccess { entry = it }
            .onFailure { error = it.message }
    }
    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, color = Color.White, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back_button), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            val current = entry
            when {
                error != null -> Text(error.orEmpty(), color = Color.White)
                current == null -> CircularProgressIndicator()
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        val cookies = CookieManager.getInstance()
                        cookies.setAcceptCookie(true)
                        (current.credential as? PeachCredential.Session)?.let { session ->
                            cookies.setCookie(current.baseUrl, "${PeachCredential.SESSION_COOKIE}=${session.cookie}; path=/")
                        }
                        WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = PinnedWebViewClient(onTitle = { title = it })
                            loadUrl(current.initialUrl())
                            webView = this
                        }
                    },
                    onRelease = { it.destroy() }
                )
            }
        }
    }
}

private class PinnedWebViewClient(private val onTitle: (String) -> Unit) : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        view?.title?.takeIf { it.isNotBlank() }?.let(onTitle)
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) error?.certificate?.x509Certificate else null
        val uri = error?.url?.let(Uri::parse)
        val host = uri?.host
        val port = uri?.port?.takeIf { it > 0 } ?: 443
        val pinned = host?.let { ServerTrust.pinnedFingerprint(it, port) }
        if (certificate != null && pinned != null && pinned == ServerTrust.fingerprint(certificate)) {
            handler?.proceed()
        } else {
            handler?.cancel()
        }
    }
}
