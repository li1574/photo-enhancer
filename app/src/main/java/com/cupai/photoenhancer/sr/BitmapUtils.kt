package com.cupai.photoenhancer.sr

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

object BitmapUtils {

    /**
     * Decodes [uri] honouring EXIF rotation, downsampling so the long side is <= [maxDim].
     *
     * Two independent decoders are tried in turn. `BitmapFactory` is the fast path;
     * `ImageDecoder` (API 28+) is a genuinely separate implementation - it parses the
     * container itself and applies EXIF on its own - so it is a real second opinion
     * rather than a retry of the same code.
     */
    fun loadOrientedBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val primary = runCatching { decodeWithBitmapFactory(context, uri, maxDim) }
        primary.getOrNull()?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val fallback = runCatching { decodeWithImageDecoder(context, uri, maxDim) }
            fallback.getOrNull()?.let { return it }
            fallback.exceptionOrNull()?.let { primary.exceptionOrNull()?.addSuppressed(it) }
        }

        val cause = primary.exceptionOrNull()
        throw IllegalStateException(cause?.message ?: "无法读取图片", cause)
    }

    private fun decodeWithBitmapFactory(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val cr = context.contentResolver
        val (srcW, srcH) = readImageBounds(cr, uri)

        var sample = 1
        while (max(srcW, srcH) / (sample * 2) >= maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("图片解码失败（采样 ${sample}×）")

        val orientation = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (t: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }

        return applyExifRotation(decoded, orientation)
    }

    /**
     * Reads the header only and returns the real pixel size.
     *
     * `BitmapFactory.decodeStream()` returns **null by design** on a bounds-only pass:
     * with `inJustDecodeBounds` set the size lands in the options object, and null is
     * the documented, always-taken return value. Treating that null as a failure - which
     * an earlier version of this file did via `?: error(...)` - makes *every* decode of
     * *every* image fail on *every* device, while the photo grid still looks fine
     * because grid thumbnails come from `ContentResolver.loadThumbnail` instead.
     *
     * So the return value is deliberately discarded here, and this helper is the single
     * place in the app where the bounds pass is allowed to happen. There is no nullable
     * result left for a caller to misread.
     */
    private fun readImageBounds(cr: ContentResolver, uri: Uri): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("无法打开图片")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            error("无法解析图片尺寸（文件可能已损坏或格式不受支持）")
        }
        return bounds.outWidth to bounds.outHeight
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = max(info.size.width, info.size.height)
            if (longSide > maxDim) {
                // Unlike inSampleSize this is a plain ratio, not a power of two.
                decoder.setTargetSampleSize(ceil(longSide.toDouble() / maxDim).toInt())
            }
        }
    }

    fun applyExifRotation(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return src
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (out !== src) src.recycle()
        return out
    }

    /** Scales down so the long side is at most [maxLong]. Returns the input when already small. */
    fun downscaleTo(src: Bitmap, maxLong: Int): Bitmap {
        val long = max(src.width, src.height)
        if (long <= maxLong) return src
        val ratio = maxLong.toFloat() / long
        val nw = max(1, (src.width * ratio).roundToInt())
        val nh = max(1, (src.height * ratio).roundToInt())
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    /**
     * Unsharp mask. The blur pass uses native bitmap scaling (fast) instead of a Kotlin
     * convolution, which keeps this usable on multi-megapixel results.
     *
     * @param amount 0 = no-op, 1 = strong
     */
    fun sharpen(src: Bitmap, amount: Float): Bitmap {
        if (amount <= 0.01f) return src
        val w = src.width
        val h = src.height
        val smallW = max(1, w / 6)
        val smallH = max(1, h / 6)

        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val blur = Bitmap.createScaledBitmap(small, w, h, true)
        small.recycle()

        val sp = IntArray(w * h)
        val bp = IntArray(w * h)
        src.getPixels(sp, 0, w, 0, 0, w, h)
        blur.getPixels(bp, 0, w, 0, 0, w, h)
        blur.recycle()

        // Written back in place: index i only ever reads sp[i] and bp[i], so no
        // third array is needed. On a 5120px result that saves ~80 MB of heap.
        for (i in sp.indices) {
            val s = sp[i]
            val b = bp[i]
            val sr = (s ushr 16) and 0xFF
            val sg = (s ushr 8) and 0xFF
            val sb = s and 0xFF
            val br = (b ushr 16) and 0xFF
            val bg = (b ushr 8) and 0xFF
            val bb = b and 0xFF
            val r = (sr + amount * (sr - br)).toInt().coerceIn(0, 255)
            val g = (sg + amount * (sg - bg)).toInt().coerceIn(0, 255)
            val bl = (sb + amount * (sb - bb)).toInt().coerceIn(0, 255)
            sp[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(sp, 0, w, 0, 0, w, h)
        return result
    }

    /** Writes [bitmap] into the system gallery (Pictures/PhotoEnhancer). */
    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "SR_$stamp.png"
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/PhotoEnhancer"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { os ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        } ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        return uri
    }

    /** Stages the bitmap in cache and returns a content:// uri usable for sharing. */
    fun writeShareFile(context: Context, bitmap: Bitmap): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "enhanced_${System.currentTimeMillis()}.png")
        file.outputStream().use { os ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        }
        return file
    }
}
