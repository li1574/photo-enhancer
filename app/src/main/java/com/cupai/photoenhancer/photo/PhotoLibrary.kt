package com.cupai.photoenhancer.photo

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.util.Calendar

/** One row of the device gallery, as far as this app cares. */
data class MediaImage(
    val id: Long,
    val uri: Uri,
    val name: String,
    val width: Int,
    val height: Int,
    /** MediaStore DATE_ADDED, seconds since epoch (UTC). */
    val dateAdded: Long,
    val bucket: String,
    val sizeBytes: Long
) {
    val isUsable: Boolean get() = width > 0 && height > 0
    val sizeLabel: String get() = "$width × $height"
}

/**
 * Permission plumbing for the built-in browser.
 *
 * Android 13 split the storage permission in two, and Android 14 added a third
 * state ("selected photos only"). All three have to be understood or the browser
 * shows an empty grid on a perfectly valid grant.
 */
object GalleryPermission {

    fun required(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        // Android 9 and below also need WRITE to insert into the gallery; from API 29
        // MediaStore inserts are scoped and need no permission at all.
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    /** True when we may read at least some photos. */
    fun granted(context: Context): Boolean {
        fun has(p: String) = ContextCompat.checkSelfPermission(context, p) ==
            PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                has(Manifest.permission.READ_MEDIA_IMAGES) ||
                    has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> has(Manifest.permission.READ_MEDIA_IMAGES)
            else -> has(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /** True when only a hand-picked subset of the gallery is visible (Android 14+). */
    fun partial(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        fun has(p: String) = ContextCompat.checkSelfPermission(context, p) ==
            PackageManager.PERMISSION_GRANTED
        return !has(Manifest.permission.READ_MEDIA_IMAGES) &&
            has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }
}

/**
 * Reads the device gallery through MediaStore.
 *
 * The app never copies the whole library anywhere - it only keeps the metadata
 * rows and pulls pixels on demand, so browsing a 20k-photo library stays cheap.
 */
class PhotoRepository(private val context: Context) {

    private val collection: Uri
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    /**
     * Newest first. [limit] guards against absurd libraries; 20k rows of metadata is
     * a few MB, which is acceptable for a browser that scrolls the whole library.
     */
    fun query(limit: Int = 20000): List<MediaImage> {
        if (!GalleryPermission.granted(context)) return emptyList()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE
        )
        val selection = "${MediaStore.Images.Media.SIZE} > 0 AND " +
            "${MediaStore.Images.Media.WIDTH} > 0"
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val out = ArrayList<MediaImage>(1024)
        try {
            context.contentResolver.query(collection, projection, selection, null, order)
                ?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                    val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                    val dCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val bCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val sCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                    while (c.moveToNext() && out.size < limit) {
                        val id = c.getLong(idCol)
                        val w = c.getInt(wCol)
                        val h = c.getInt(hCol)
                        if (w <= 0 || h <= 0) continue
                        out += MediaImage(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = c.getString(nameCol) ?: "IMG_$id",
                            width = w,
                            height = h,
                            dateAdded = c.getLong(dCol),
                            bucket = c.getString(bCol) ?: "",
                            sizeBytes = c.getLong(sCol)
                        )
                    }
                }
        } catch (t: Throwable) {
            // A revoked/partial grant can make the query throw; an empty library is
            // the honest answer and the UI turns it into a "grant access" prompt.
            return emptyList()
        }
        return out
    }

    /** Human date bucket used to group the grid. */
    fun dayLabel(epochSeconds: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochSeconds * 1000L }
        val now = Calendar.getInstance()
        val today = now.clone() as Calendar
        val yesterday = now.clone() as Calendar
        yesterday.add(Calendar.DAY_OF_YEAR, -1)

        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        return when {
            sameDay(cal, today) -> "今天"
            sameDay(cal, yesterday) -> "昨天"
            cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
            else -> "${cal.get(Calendar.YEAR)}年${cal.get(Calendar.MONTH) + 1}月" +
                "${cal.get(Calendar.DAY_OF_MONTH)}日"
        }
    }
}
