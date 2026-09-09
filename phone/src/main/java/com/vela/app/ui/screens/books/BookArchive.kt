package com.vela.app.ui.screens.books

import java.io.File
import java.io.IOException
import java.net.URI
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

internal data class BookChapter(val path: String, val title: String)

/** Archives are never extracted; every resource is bounded and addressed inside this book. */
internal class BookArchive(private val file: File) {
    val entries: List<String> = ZipFile(file).use { zip ->
        val names = mutableSetOf<String>()
        var total = 0L
        zip.entries().asSequence().map { entry ->
            if (names.size >= 10000) throw IOException("书籍包含过多文件")
            val name = safeBookPath(entry.name)
            if (!names.add(name)) throw IOException("书籍包含重复路径")
            if (entry.size < 0 || entry.size > MAX_RESOURCE_BYTES) throw IOException("书籍资源大小无效")
            total += entry.size
            if (total > 512L * 1024 * 1024) throw IOException("书籍解压大小超过限制")
            name
        }.toList()
    }

    fun bytes(path: String): ByteArray = ZipFile(file).use { zip ->
        val entry = zip.getEntry(safeBookPath(path)) ?: throw IOException("书籍资源不存在：$path")
        zip.getInputStream(entry).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val n = input.read(buffer)
                if (n < 0) break
                if (output.size().toLong() + n > minOf(MAX_RESOURCE_BYTES.toLong(), entry.size)) throw IOException("书籍资源超过大小限制")
                output.write(buffer, 0, n)
            }
            if (output.size().toLong() != entry.size) throw IOException("书籍资源长度不匹配")
            output.toByteArray()
        }
    }

    fun comicPages(): List<BookChapter> = entries.filter {
        it.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif") && !it.startsWith("__MACOSX/")
    }.sortedWith(::naturalBookCompare).map { BookChapter(it, it.substringAfterLast('/')) }
        .ifEmpty { throw IOException("漫画文件中没有可阅读的图片") }

    fun epubChapters(): List<BookChapter> {
        val container = parseBookXml(bytes("META-INF/container.xml"))
        val root = container.getElementsByTagNameNS("*", "rootfile").item(0) as? org.w3c.dom.Element
            ?: throw IOException("EPUB 缺少内容索引")
        val opfPath = safeBookPath(root.getAttribute("full-path"))
        val opf = parseBookXml(bytes(opfPath))
        val manifest = mutableMapOf<String, BookChapter>()
        val items = opf.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val item = items.item(i) as org.w3c.dom.Element
            if (item.getAttribute("media-type") in setOf("application/xhtml+xml", "text/html")) {
                val path = resolveBookPath(opfPath, item.getAttribute("href"))
                manifest[item.getAttribute("id")] = BookChapter(path, "章节 ${manifest.size + 1}")
            }
        }
        val spine = opf.getElementsByTagNameNS("*", "itemref")
        return (0 until spine.length).mapNotNull { i ->
            val item = spine.item(i) as org.w3c.dom.Element
            if (item.getAttribute("linear") == "no") null else {
                val chapter = manifest[item.getAttribute("idref")] ?: throw IOException("EPUB 章节索引损坏")
                val doc = parseBookXml(bytes(chapter.path))
                val heading = listOf("h1", "h2", "title").firstNotNullOfOrNull { tag ->
                    doc.getElementsByTagNameNS("*", tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
                }
                chapter.copy(title = heading?.take(120) ?: chapter.title)
            }
        }.ifEmpty { throw IOException("EPUB 没有可阅读的章节") }
    }

    companion object { const val MAX_RESOURCE_BYTES = 32 * 1024 * 1024 }
}

internal fun safeBookPath(path: String): String {
    if (path.isBlank() || path.startsWith('/') || '\\' in path || '\u0000' in path || ':' in path || path.split('/').any { it == ".." || it == "." }) {
        throw IOException("书籍资源路径不安全")
    }
    return path
}

internal fun resolveBookPath(base: String, href: String): String {
    val ref = URI(href)
    if (ref.isAbsolute || ref.rawAuthority != null) throw IOException("书籍引用了外部资源")
    val resolved = URI(null, null, "/${safeBookPath(base)}", null).resolve(ref).normalize()
    return safeBookPath(resolved.path.removePrefix("/"))
}

internal fun decodeBookText(bytes: ByteArray): String {
    val encoding = when {
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
        bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> Charsets.UTF_16BE
        bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> Charsets.UTF_16LE
        else -> Charsets.UTF_8
    }
    return encoding.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
}

internal fun parseBookXml(bytes: ByteArray): Document {
    // EPUB commonly declares the XHTML public DTD. Remove that declaration locally;
    // internal subsets and entity definitions are never accepted or resolved.
    val source = decodeBookText(bytes)
    if (source.contains("<!ENTITY", true)) throw IOException("不支持含实体定义的书籍")
    val sanitized = source.replace(Regex("<!DOCTYPE\\s+[^\\[>]+>", RegexOption.IGNORE_CASE), "")
    if (sanitized.contains("<!DOCTYPE", true)) throw IOException("不支持含内部 DTD 的书籍")
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        // Android XML factories do not consistently implement SAX feature flags.
        // DTD declarations are removed or rejected above; the resolver fails closed below.
    }
    return factory.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> throw IOException("禁止外部 XML 资源") }
    }.parse(org.xml.sax.InputSource(java.io.StringReader(sanitized)))
}

internal fun naturalBookCompare(a: String, b: String): Int {
    val parts = Regex("[0-9]+|[^0-9]+")
    val aa = parts.findAll(a).map { it.value }.toList()
    val bb = parts.findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(aa.size, bb.size)) {
        val x = aa[i]; val y = bb[i]
        val result = if (x[0].isDigit() && y[0].isDigit()) {
            val xx = x.trimStart('0').ifEmpty { "0" }; val yy = y.trimStart('0').ifEmpty { "0" }
            xx.length.compareTo(yy.length).takeIf { it != 0 } ?: xx.compareTo(yy)
        } else x.compareTo(y, ignoreCase = true)
        if (result != 0) return result
    }
    return aa.size.compareTo(bb.size).takeIf { it != 0 } ?: a.compareTo(b)
}
