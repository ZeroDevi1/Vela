package com.vela.app.cache

import android.content.Context
import android.util.Log
import com.vela.data.network.VelaJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/**
 * 页面快照缓存：内存 LRU + 磁盘，用于“先显示上次的内容，再后台刷新”。
 *
 * - 读：先查内存，未命中再读磁盘（IO 线程）；进程被回收或冷启动后仍能秒开。
 * - 写：同步更新内存，磁盘写入在单独的 IO 协程里完成，不阻塞调用方。
 * - 键必须包含服务器与用户标识，避免切换服务器/账号后串数据；调用方负责拼键。
 * - 磁盘最多保留 [MAX_DISK_ENTRIES] 个文件，超出按修改时间淘汰最旧的。
 *
 * 快照只是展示兜底，不保证新鲜；调用方拿到后仍应发起网络请求并用结果覆盖。
 */
object PageSnapshotCache {
    private const val TAG = "PageSnapshotCache"
    private const val DIR_NAME = "page-snapshots"
    private const val MAX_MEMORY_ENTRIES = 24
    private const val MAX_DISK_ENTRIES = 120

    /** 读出的快照。[savedAtMs] 为写入时刻（墙钟毫秒），调用方可据此判断是否过旧。 */
    class Snapshot<T>(val value: T, val savedAtMs: Long)

    @Serializable
    private data class DiskEnvelope(val savedAtMs: Long, val payload: String)

    private val memory = object : LinkedHashMap<String, Snapshot<Any?>>(MAX_MEMORY_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot<Any?>>?): Boolean =
            size > MAX_MEMORY_ENTRIES
    }
    private val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> read(context: Context, key: String, serializer: KSerializer<T>): Snapshot<T>? {
        synchronized(memory) { memory[key] }?.let { return it as Snapshot<T> }
        return withContext(Dispatchers.IO) {
            val file = fileFor(context, key)
            if (!file.exists()) return@withContext null
            runCatching {
                val envelope = VelaJson.decodeFromString(DiskEnvelope.serializer(), file.readText())
                val value = VelaJson.decodeFromString(serializer, envelope.payload)
                Snapshot(value, envelope.savedAtMs)
            }.onFailure {
                // 模型升级后旧快照可能解析失败，直接丢弃
                file.delete()
            }.getOrNull()
        }?.also { snapshot ->
            synchronized(memory) { memory[key] = snapshot as Snapshot<Any?> }
        }
    }

    fun <T> write(context: Context, key: String, value: T, serializer: KSerializer<T>) {
        val snapshot = Snapshot<Any?>(value, System.currentTimeMillis())
        synchronized(memory) { memory[key] = snapshot }
        val appContext = context.applicationContext
        diskScope.launch {
            runCatching {
                val payload = VelaJson.encodeToString(serializer, value)
                val envelope = VelaJson.encodeToString(
                    DiskEnvelope.serializer(),
                    DiskEnvelope(snapshot.savedAtMs, payload)
                )
                val file = fileFor(appContext, key)
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(envelope)
                if (!temp.renameTo(file)) {
                    file.delete()
                    temp.renameTo(file)
                }
                trimDisk(file.parentFile)
            }.onFailure { Log.w(TAG, "write snapshot failed", it) }
        }
    }

    private fun fileFor(context: Context, key: String): File {
        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        return File(dir, sha1(key) + ".json")
    }

    private fun trimDisk(dir: File?) {
        val files = dir?.listFiles { file -> file.name.endsWith(".json") } ?: return
        if (files.size <= MAX_DISK_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK_ENTRIES)
            .forEach { it.delete() }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
