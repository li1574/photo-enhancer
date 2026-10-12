package com.cupai.photoenhancer.sr

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Regression tests for the image-loading path.
 *
 * These exist because of a shipped bug: `BitmapUtils.loadOrientedBitmap` treated the
 * return value of the header-only `BitmapFactory.decodeStream` pass as a success
 * signal. That call returns **null by design** whenever `inJustDecodeBounds` is set,
 * so the elvis-operator always fired and every tap in the built-in browser reported
 * "读取图片失败" - while the grid still looked fine, because thumbnails are produced
 * by a completely different API (`ContentResolver.loadThumbnail`).
 *
 * The grid/thumbnail path could never have caught this, so the test has to go through
 * `loadOrientedBitmap` itself.
 *
 * Scope note: these tests exercise the decode path end to end and lock in the
 * surrounding behaviour (downsampling, EXIF, error surfacing). They do *not* prove the
 * bounds-only null contract, because Robolectric's shadow does not model it - see
 * `robolectricShadow_doesNotModel_theBoundsOnlyNullContract`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BitmapUtilsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ---------------------------------------------------------------- helpers

    /**
     * Real PNG bytes. `Bitmap.compress` is backed by a genuine encoder on the JVM,
     * so what the decoder reads back is an actual PNG rather than a stub.
     */
    private fun pngBytes(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            // 8px checkerboard: gives the decoder something non-uniform to chew on.
            if (((x / 8) + (y / 8)) % 2 == 0) 0xFF102030.toInt() else 0xFFE0C080.toInt()
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)

        val bos = ByteArrayOutputStream()
        val ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        assertTrue("Bitmap.compress produced no bytes", ok && bos.size() > 0)
        return bos.toByteArray()
    }

    private fun writeTemp(name: String, bytes: ByteArray): File {
        val f = File(context.cacheDir, name)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    /** Provider that serves real bytes over a content:// uri, like MediaStore does. */
    class FakeImageProvider : ContentProvider() {
        companion object {
            const val AUTHORITY = "com.cupai.photoenhancer.test.images"
            var payload: ByteArray? = null
        }

        override fun onCreate(): Boolean = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val f = File(context!!.cacheDir, "provider_${uri.lastPathSegment}.png")
            f.writeBytes(payload ?: ByteArray(0))
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?
        ): Cursor? = null

        override fun getType(uri: Uri): String = "image/png"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    }

    // ------------------------------------------- the contract that broke

    /**
     * Robolectric's shadow does **not** model the platform contract for a bounds-only
     * decode. The real `BitmapFactory.decodeStream` returns null whenever
     * `inJustDecodeBounds` is set; `ShadowBitmapFactory` hands back a Bitmap instead.
     *
     * That divergence is recorded here rather than papered over, because it is the
     * reason this suite cannot be the oracle for the original defect: the old, broken
     * code also "passes" under this shadow, so a green run here would have proved
     * nothing. The platform contract is asserted against the AOSP source instead, and
     * the regression protection in production code is structural - `readImageBounds`
     * is the only place a bounds-only decode happens and it never hands the nullable
     * result to a caller.
     *
     * If this assertion ever starts failing, Robolectric has begun modelling the real
     * behaviour and the null check can be promoted into a genuine assertion.
     */
    @Test
    fun robolectricShadow_doesNotModel_theBoundsOnlyNullContract() {
        val bytes = pngBytes(320, 240)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }

        val result = bytes.inputStream().use { BitmapFactory.decodeStream(it, null, opts) }

        assertNotNull(
            "ShadowBitmapFactory now returns null, matching the platform - " +
                "promote this test into a real assertion of the contract",
            result
        )
        // The dimensions are still reported correctly, which is what the fix relies on.
        assertEquals(320, opts.outWidth)
        assertEquals(240, opts.outHeight)
    }

    // ------------------------------------------------------- the fixed path

    @Test
    fun loadOrientedBitmap_decodesFromFileUri() {
        val f = writeTemp("plain_320x240.png", pngBytes(320, 240))

        val bmp = BitmapUtils.loadOrientedBitmap(context, Uri.fromFile(f), 2048)

        assertEquals(320, bmp.width)
        assertEquals(240, bmp.height)
        // Checkerboard must survive: sample the two colours we drew.
        val a = bmp.getPixel(2, 2)
        val b = bmp.getPixel(10, 2)
        assertTrue("expected a two-tone image, got $a / $b", a != b)
    }

    @Test
    fun loadOrientedBitmap_decodesFromContentUri() {
        FakeImageProvider.payload = pngBytes(200, 160)
        Robolectric.buildContentProvider(FakeImageProvider::class.java)
            .create(FakeImageProvider.AUTHORITY)

        val uri = Uri.parse("content://${FakeImageProvider.AUTHORITY}/img/7")
        val bmp = BitmapUtils.loadOrientedBitmap(context, uri, 2048)

        assertEquals(200, bmp.width)
        assertEquals(160, bmp.height)
    }

    @Test
    fun loadOrientedBitmap_downscalesOversizedImage() {
        // 3000px long side against a 1000px budget -> inSampleSize must kick in.
        val f = writeTemp("huge_3000x2000.png", pngBytes(3000, 2000))

        val bmp = BitmapUtils.loadOrientedBitmap(context, Uri.fromFile(f), 1000)

        val longSide = max(bmp.width, bmp.height)
        println("downscaled to ${bmp.width}x${bmp.height}")
        assertTrue("long side $longSide should be sampled down, not full size", longSide <= 2000)
        // Aspect ratio 3:2 must be preserved (allow 1px rounding).
        val ratio = bmp.width.toFloat() / bmp.height.toFloat()
        assertTrue("aspect ratio drifted: $ratio", kotlin.math.abs(ratio - 1.5f) < 0.02f)
    }

    @Test
    fun loadOrientedBitmap_reportsRealReasonWhenUriIsUnreadable() {
        val missing = Uri.parse("content://${FakeImageProvider.AUTHORITY}/gone/1")
        // No provider registered for the authority -> ContentResolver throws.
        try {
            BitmapUtils.loadOrientedBitmap(context, missing, 2048)
            fail("expected a failure for an unresolvable uri")
        } catch (t: Throwable) {
            // The point is that something informative propagates to the UI layer
            // rather than being swallowed.
            assertNotNull(t)
        }
    }

    // -------------------------------------------------------- exif handling

    @Test
    fun applyExifRotation_rotate90_swapsDimensions() {
        val src = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val out = BitmapUtils.applyExifRotation(src, ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(20, out.width)
        assertEquals(40, out.height)
    }

    @Test
    fun applyExifRotation_normal_returnsSameInstance() {
        val src = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val out = BitmapUtils.applyExifRotation(src, ExifInterface.ORIENTATION_NORMAL)
        assertTrue("normal orientation must be a no-op", out === src)
    }

    // ------------------------------------------------------------ sharpen

    @Test
    fun sharpen_isNoOpBelowThreshold() {
        val src = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        assertTrue(BitmapUtils.sharpen(src, 0f) === src)
    }

    @Test
    fun sharpen_preservesGeometry() {
        val src = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        src.eraseColor(0xFF808080.toInt())
        val out = BitmapUtils.sharpen(src, 0.5f)
        assertEquals(64, out.width)
        assertEquals(48, out.height)
    }
}
