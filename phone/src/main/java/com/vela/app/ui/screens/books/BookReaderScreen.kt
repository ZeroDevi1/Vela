package com.vela.app.ui.screens.books

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

private data class ReaderBook(val chapters: List<BookChapter>, val archive: BookArchive? = null, val textPages: List<String> = emptyList())

/** Downloads and account-scoped progress keys are supplied by the library route. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookReaderScreen(file: File, format: String, title: String, progressKey: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("book_reader", android.content.Context.MODE_PRIVATE) }
    val normalizedFormat = format.lowercase(Locale.ROOT).removePrefix(".")
    var book by remember(file, normalizedFormat) { mutableStateOf<ReaderBook?>(null) }
    var error by remember(file, normalizedFormat) { mutableStateOf<String?>(null) }
    var page by remember(progressKey) { mutableIntStateOf(prefs.getInt("$progressKey.page", 0)) }
    var fontSize by remember(progressKey) { mutableFloatStateOf(prefs.getFloat("$progressKey.font", 18f).coerceIn(14f, 30f)) }
    var night by remember(progressKey) { mutableStateOf(prefs.getBoolean("$progressKey.night", false)) }
    var readingMode by remember(progressKey) { mutableIntStateOf(prefs.getInt("$progressKey.mode", 0).coerceIn(0, 2)) }
    var sliderPage by remember { mutableStateOf<Float?>(null) }
    val raster = normalizedFormat in setOf("pdf", "cbz", "zip")
    LaunchedEffect(readingMode) { prefs.edit().putInt("$progressKey.mode", readingMode).apply() }
    var controls by remember { mutableStateOf(true) }
    var contents by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var resourceWarning by remember(file) { mutableStateOf<String?>(null) }
    val background = if (night) Color(0xFF181818) else Color(0xFFF7F2E8)
    val foreground = if (night) Color(0xFFE5DFD4) else Color(0xFF24211E)
    LaunchedEffect(file, normalizedFormat) {
        try {
            book = withContext(Dispatchers.IO) {
                when (normalizedFormat) {
                    "epub" -> BookArchive(file).let { ReaderBook(it.epubChapters(), it) }
                    "cbz", "zip" -> BookArchive(file).let { ReaderBook(it.comicPages(), it) }
                    "pdf" -> openPdf(file) { pdf ->
                        if (pdf.pageCount == 0) throw IOException("PDF 没有可阅读的页面")
                        ReaderBook(List(pdf.pageCount) { BookChapter("$it", "第 ${it + 1} 页") })
                    }
                    "txt" -> {
                        if (file.length() > 16 * 1024 * 1024) throw IOException("文本超过 16 MB，暂不支持")
                        val text = file.readBytes().let { bytes ->
                            when {
                                bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> bytes.toString(Charsets.UTF_16LE).removePrefix("\uFEFF")
                                bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> bytes.toString(Charsets.UTF_16BE).removePrefix("\uFEFF")
                                else -> Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                            }
                        }
                        val chunks = text.chunked(5000).ifEmpty { throw IOException("文本内容为空") }
                        ReaderBook(chunks.indices.map { BookChapter("$it", "第 ${it + 1} 节") }, textPages = chunks)
                    }
                    else -> throw IOException("暂不支持 $format 格式，请使用 EPUB、PDF、CBZ 或 TXT")
                }
            }.also { page = page.coerceIn(0, (it.chapters.size - 1).coerceAtLeast(0)) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "书籍读取失败" }
    }
    LaunchedEffect(page, fontSize, night, book) {
        if (book != null) prefs.edit().putInt("$progressKey.page", page).putFloat("$progressKey.font", fontSize).putBoolean("$progressKey.night", night).apply()
    }
    Scaffold(
        topBar = { if (controls) TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回书库") }
        }, actions = { TextButton(onClick = { settings = true }) { Text("设置") } }) },
        bottomBar = {
            val current = book
            if (controls && current != null && error == null) Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { contents = true }) { Text(if (normalizedFormat == "epub") "目录" else "跳页") }
                        Spacer(Modifier.weight(1f))
                        Text("${page + 1} / ${current.chapters.size} · ${((page + 1f) / current.chapters.size * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { controls = false }) { Text("沉浸") }
                    }
                    if (current.chapters.size > 1) Slider(value = sliderPage ?: page.toFloat(), onValueChange = { sliderPage = it }, onValueChangeFinished = { sliderPage?.let { page = it.toInt() }; sliderPage = null }, valueRange = 0f..current.chapters.lastIndex.toFloat())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { page-- }, enabled = page > 0) { Text("上一页") }
                        TextButton(onClick = { page++ }, enabled = page < current.chapters.lastIndex) { Text("下一页") }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(background)) {
            val current = book
            when {
                error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error.orEmpty(), color = foreground)
                    TextButton(onClick = onBack) { Text("返回书库") }
                }
                current == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                raster -> RasterReader(file, normalizedFormat, current, page, readingMode,
                    onPage = { page = it }, onMenu = { controls = !controls }, onError = { error = it })
                else -> key(file, page) {
                    when (normalizedFormat) {
                        "epub" -> EpubChapter(current.archive!!, current.chapters[page], fontSize, night,
                            prefs.getInt("$progressKey.scroll.$page", 0),
                            { prefs.edit().putInt("$progressKey.scroll.$page", it).apply() },
                            { destination -> current.chapters.indexOfFirst { it.path == destination }.takeIf { it >= 0 }?.let { page = it } },
                            { error = it }, { resourceWarning = it })
                        "txt" -> {
                            val scroll = rememberScrollState(prefs.getInt("$progressKey.scroll.$page", 0))
                            LaunchedEffect(scroll.value) { prefs.edit().putInt("$progressKey.scroll.$page", scroll.value).apply() }
                            Text(current.textPages[page], Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp), color = foreground, fontSize = fontSize.sp, lineHeight = (fontSize * 1.75f).sp)
                        }
                        else -> Unit
                    }
                }
            }
            resourceWarning?.let { warning ->
                Text(warning, Modifier.align(Alignment.TopCenter).background(background).padding(8.dp),
                    color = foreground, style = MaterialTheme.typography.labelSmall)
            }
            if (!controls) Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(8.dp), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 4.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Text("上一页") }
                    TextButton(onClick = { controls = true }) { Text("${page + 1} / ${book?.chapters?.size ?: 0} · 显示菜单") }
                    TextButton(onClick = { book?.let { if (page < it.chapters.lastIndex) page++ } }, enabled = book?.let { page < it.chapters.lastIndex } == true) { Text("下一页") }
                }
            }
        }
    }
    if (contents) AlertDialog(onDismissRequest = { contents = false }, title = { Text("阅读位置") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) { itemsIndexed(book?.chapters.orEmpty()) { index, chapter ->
            ListItem(headlineContent = { Text(chapter.title) }, supportingContent = { Text("${index + 1}") }, modifier = Modifier.clickable { page = index; contents = false }, colors = ListItemDefaults.colors(containerColor = if (index == page) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface))
        } }
    }, confirmButton = { TextButton(onClick = { contents = false }) { Text("关闭") } })
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("阅读设置") }, text = {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("夜间背景", Modifier.weight(1f)); Switch(night, { night = it }) }
            if (normalizedFormat in setOf("epub", "txt")) { Text("字号 ${fontSize.toInt()}"); Slider(fontSize, { fontSize = it }, valueRange = 14f..30f, steps = 15) }
            if (raster) {
                Text("翻页方向", style = MaterialTheme.typography.titleSmall)
                listOf("从左向右", "从右向左", "上下翻页").forEachIndexed { index, label ->
                    Row(Modifier.fillMaxWidth().clickable { readingMode = index }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = readingMode == index, onClick = { readingMode = index }); Text(label)
                    }
                }
                Text("滑动翻页 · 轻点显示菜单 · 双击或双指缩放。放大后拖动查看，缩回原尺寸再翻页。", style = MaterialTheme.typography.bodySmall)
            }
            Text("阅读位置自动保存在当前账户下。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text("完成") } })
}

private fun <T> openPdf(file: File, block: (PdfRenderer) -> T): T = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use(block) }

@Composable
private fun RasterReader(file: File, format: String, book: ReaderBook, page: Int, mode: Int,
    onPage: (Int) -> Unit, onMenu: () -> Unit, onError: (String) -> Unit) {
    val pager = rememberPagerState(initialPage = page, pageCount = { book.chapters.size })
    val zoomed = remember(file) { mutableStateMapOf<Int, Boolean>() }
    val latestPage by rememberUpdatedState(onPage)
    LaunchedEffect(page) { if (pager.currentPage != page) pager.scrollToPage(page) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { latestPage(it) }
    }
    val content: @Composable (Int) -> Unit = { index ->
        RasterBookPage(file, format, book, index, onError, onMenu) { zoomed[index] = it }
    }
    if (mode == 2) VerticalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = zoomed[pager.currentPage] != true) { content(it) }
    else HorizontalPager(pager, Modifier.fillMaxSize(), reverseLayout = mode == 1, userScrollEnabled = zoomed[pager.currentPage] != true) { content(it) }
}

@Composable
private fun RasterBookPage(file: File, format: String, book: ReaderBook, page: Int, onError: (String) -> Unit, onMenu: () -> Unit, onZoom: (Boolean) -> Unit) {
    var bitmap by remember(file, page) { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(bitmap) {
        val displayed = bitmap
        onDispose { displayed?.recycle() }
    }
    LaunchedEffect(file, page) {
        var decoded: Bitmap? = null
        try {
            bitmap = withContext(Dispatchers.IO) {
                (if (format == "pdf") openPdf(file) { pdf -> pdf.openPage(page).use { source ->
                    val scale = 2048f / maxOf(source.width, source.height).coerceAtLeast(1)
                    Bitmap.createBitmap((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                        decoded = it
                        it.eraseColor(android.graphics.Color.WHITE)
                        source.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                } } else {
                    val bytes = book.archive!!.bytes(book.chapters[page].path)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("漫画图片损坏")
                    options.inSampleSize = 1
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
                    options.inJustDecodeBounds = false
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("图片解码失败")
                }).also { decoded = it }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { onError(e.message ?: "页面读取失败") }
        finally { if (bitmap !== decoded) decoded?.recycle() }
    }
    var zoom by remember(file, page) { mutableFloatStateOf(1f) }
    var offset by remember(file, page) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(zoom) { onZoom(zoom > 1f) }
    val transform = rememberTransformableState { change, pan, _ ->
        zoom = (zoom * change).coerceIn(1f, 5f)
        offset = if (zoom == 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize().clipToBounds()
        .pointerInput(file, page) { detectTapGestures(onTap = { onMenu() }, onDoubleTap = {
            zoom = if (zoom > 1f) 1f else 2.5f; offset = Offset.Zero
        }) }
        .transformable(transform, canPan = { zoom > 1f }), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "第 ${page + 1} 页，可双指缩放", Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom; scaleY = zoom
            translationX = offset.x.coerceIn(-size.width * (zoom - 1) / 2, size.width * (zoom - 1) / 2)
            translationY = offset.y.coerceIn(-size.height * (zoom - 1) / 2, size.height * (zoom - 1) / 2)
        }, contentScale = ContentScale.Fit) } ?: CircularProgressIndicator()
    }
}

@Composable
private fun EpubChapter(archive: BookArchive, chapter: BookChapter, font: Float, night: Boolean, savedScroll: Int, onScroll: (Int) -> Unit, onChapter: (String) -> Unit, onError: (String) -> Unit, onWarning: (String) -> Unit) {
    val context = LocalContext.current
    val webView = remember(archive, chapter.path) {
        WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            setOnScrollChangeListener { _, _, y, _, _ -> onScroll(y) }
        }
    }
    DisposableEffect(webView) { onDispose { webView.stopLoading(); webView.destroy() } }
    LaunchedEffect(webView, font, night) {
        try {
            val html = withContext(Dispatchers.IO) { decodeBookText(archive.bytes(chapter.path)) }
            val color = if (night) "#e5dfd4" else "#24211e"
            val bg = if (night) "#181818" else "#f7f2e8"
            val style = "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; img-src https://book.local; style-src 'unsafe-inline' https://book.local; font-src https://book.local\"><style>html,body{background:$bg!important;color:$color!important;font-size:${font.toInt()}px!important;line-height:1.75!important}body{padding:20px;overflow-wrap:anywhere}img,svg{max-width:100%;height:auto}iframe,object,embed,form{display:none!important}</style>"
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                    if (request.url.scheme != "https" || request.url.host != "book.local") return WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                    return try {
                        val path = safeBookPath(request.url.path.orEmpty().removePrefix("/"))
                        val mime = when (path.substringAfterLast('.').lowercase()) { "css" -> "text/css"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; "woff" -> "font/woff"; "woff2" -> "font/woff2"; else -> "application/octet-stream" }
                        WebResourceResponse(mime, null, archive.bytes(path).inputStream())
                    } catch (e: Exception) {
                        view?.post { onWarning("部分插图或样式无法读取，正文仍可阅读") }
                        WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    if (request.url.scheme == "https" && request.url.host == "book.local") {
                        val path = request.url.path.orEmpty().removePrefix("/")
                        if (path == chapter.path && request.url.fragment != null) return false
                        onChapter(path)
                    }
                    return true
                }
                override fun onPageFinished(view: WebView, url: String?) { view.post { view.scrollTo(0, savedScroll) } }
            }
            val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
            val content = head.find(html)?.let { html.replaceRange(it.range.last + 1, it.range.last + 1, style) } ?: style + html
            val url = java.net.URI("https", "book.local", "/${chapter.path}", null).toASCIIString()
            webView.loadDataWithBaseURL(url, content, "text/html", "UTF-8", null)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { onError(e.message ?: "章节读取失败") }
    }
    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}
