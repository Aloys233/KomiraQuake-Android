package com.aloys23.komiraquake.ui.map

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 栅格瓦片加载 + 内存 LRU 缓存（磁盘缓存由 OkHttp 的 Cache 负责）。 */
class TileLoader(private val client: OkHttpClient) {

    // 按字节数计容量（约 64 MB），兼容 256² 与 512² 瓦片：
    // 256² ARGB_8888 约 256 KB/张 → 256 张；Petal 的 512² 约 1 MB/张 → 64 张。
    private val cache = object : LruCache<String, ImageBitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    // 限制并发解码，避免快速平移时一次性打满线程池。
    private val gate = Semaphore(6)

    /** 只读缓存：用于降级绘制父瓦片，不触发网络。 */
    fun peek(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        return gate.withPermit {
            cache.get(url)?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        val bytes = response.body?.bytes() ?: return@withContext null
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: return@withContext null
                        bitmap.asImageBitmap().also { cache.put(url, it) }
                    }
                }.getOrNull()
            }
        }
    }

    companion object {
        const val USER_AGENT = "komiraquake/2.0 (+https://api.wolfx.jp/)"
    }
}

/** 让瓦片响应一定可写入磁盘缓存：部分瓦片 CDN 不带 Cache-Control。 */
object TileCacheInterceptor : Interceptor {
    private const val MAX_AGE_SECONDS = 60L * 60L * 24L * 14L // 14 天

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.header("Cache-Control") != null) return response
        return response.newBuilder()
            .header("Cache-Control", "public, max-age=$MAX_AGE_SECONDS")
            .removeHeader("Pragma")
            .build()
    }
}
