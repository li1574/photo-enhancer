package com.cupai.photoenhancer.photo

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import com.cupai.photoenhancer.sr.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** LRU keyed on pixel bytes so the grid can scroll a huge library without OOM. */
private class BitmapLru(maxBytes: Int) : LruCache<String, Bitmap>(maxBytes) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/**
 * Two-level image cache for the browser:
 *  - thumbnails, sized for the grid cell, evicted aggressively
 *  - previews, one screenful, for the swipe-through viewer
 *
 * Bitmaps are never recycled explicitly - an evicted entry simply loses its last
 * reference, which avoids recycling a bitmap still being drawn.
 */
object Thumbnails {

    /**
     * Total cache budget. Deliberately a small slice of the heap: the enhancer needs
     * ~150 MB of headroom for a 5120px result, and it shares the process with us.
     */
    private val budget = (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(6 shl 20)

    /** ~36 grid thumbnails - comfortably more than LazyVerticalGrid keeps composed. */
    private val thumbCache = BitmapLru(budget / 3)

    /** A handful of 1280px previews for the swipe-through viewer. */
    private val previewCache = BitmapLru(budget / 2)

    suspend fun thumb(context: Context, image: MediaImage, px: Int): Bitmap? {
        val key = "t:${image.id}:$px"
        thumbCache.get(key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) { decodeThumb(context, image, px) } ?: return null
        thumbCache.put(key, bmp)
        return bmp
    }

    suspend fun preview(context: Context, image: MediaImage, maxDim: Int): Bitmap? {
        val key = "p:${image.id}:$maxDim"
        previewCache.get(key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            runCatching { BitmapUtils.loadOrientedBitmap(context, image.uri, maxDim) }.getOrNull()
        } ?: return null
        previewCache.put(key, bmp)
        return bmp
    }

    fun clear() {
        thumbCache.evictAll()
        previewCache.evictAll()
    }

    private fun decodeThumb(context: Context, image: MediaImage, px: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(image.uri, Size(px, px), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                image.id,
                MediaStore.Images.Thumbnails.MINI_KIND,
                null
            )
        }
    } catch (t: Throwable) {
        // Some providers (cloud-backed, HEIF on old ROMs) fail; fall back to a
        // sampled decode of the real image rather than showing a hole in the grid.
        runCatching {
            val side = (px * 1.5f).toInt()
            BitmapUtils.loadOrientedBitmap(context, image.uri, side)
        }.getOrNull()
    }
}
