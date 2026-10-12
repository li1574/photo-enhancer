package com.cupai.photoenhancer.sr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Resolved geometry for one enhancement run.
 *
 * [passes] is how many times the network is run; every shipped graph is 4x, so
 * 2x/4x need one pass and 8x/16x need two chained passes. [tiles] is the total
 * across all passes, which is what the progress bar and the ETA are built from.
 */
data class SrPlan(
    val workW: Int,
    val workH: Int,
    val outW: Int,
    val outH: Int,
    val downscaled: Boolean,
    val passes: Int,
    val reach: Int,
    val tiles: Int
) {
    val multiPass: Boolean get() = passes > 1
}

/** Smallest n with native^n >= target. */
fun passesFor(native: Int, target: Int): Int {
    var cur = 1
    var n = 0
    while (cur < target) {
        cur *= native
        n++
    }
    return max(1, n)
}

/**
 * Decides how much of the source actually reaches the network.
 *
 * Three budgets bound the working resolution, and the smallest wins:
 *
 *  - compute: this device's measured cost per source pixel. Rather than guessing
 *    from a desktop figure (a phone is 3-5x slower), the engine times one tile on
 *    the real session and then picks the largest input that finishes a whole run
 *    inside [PASS_BUDGET_MS]. A slow device silently gets a smaller working size
 *    instead of a ten-minute wait.
 *  - memory: every bitmap the engine materialises must stay under [MAX_LONG_SIDE],
 *    and a chained pass materialises model.scale^passes times the working size.
 *  - a per-model ceiling, so a fast device still cannot ask for something absurd.
 *
 * The target scale itself is nearly free - it is only a final resample - so the
 * working resolution never depends on it except through the memory budget.
 */
fun planSr(
    srcW: Int,
    srcH: Int,
    model: SrModel,
    targetScale: Int,
    accelerated: Boolean,
    tileMillis: Long = 0L
): SrPlan {
    val passes = passesFor(model.scale, targetScale)
    val reach = model.scale.toDouble().pow(passes).roundToInt()
    val valid = model.tileSize - model.tilePad * 2

    val ceiling = if (accelerated) model.workCapAccel else model.workCapCpu
    val memoryCap = max(64, MAX_LONG_SIDE / reach)

    val autoCap = if (tileMillis > 0L && valid > 0) {
        // ms spent per source pixel, on this device, for this graph
        val perPx = tileMillis.toDouble() / (valid.toDouble() * valid)
        // source pixels consumed across all passes, relative to the working size
        var sum = 0.0
        var s = 1
        repeat(passes) {
            sum += s.toDouble() * s
            s *= model.scale
        }
        val px = PASS_BUDGET_MS / (perPx * sum)
        if (px.isNaN() || px <= 0) Int.MAX_VALUE else sqrt(px).toInt()
    } else {
        Int.MAX_VALUE
    }

    val cap = minOf(ceiling, memoryCap, autoCap).coerceAtLeast(128)

    val long = max(srcW, srcH)
    val ratio = if (long <= cap) 1f else cap.toFloat() / long
    val workW = max(1, (srcW * ratio).roundToInt())
    val workH = max(1, (srcH * ratio).roundToInt())

    var tiles = 0
    var cw = workW
    var ch = workH
    repeat(passes) {
        tiles += ceil(cw.toDouble() / valid).toInt() * ceil(ch.toDouble() / valid).toInt()
        cw *= model.scale
        ch *= model.scale
    }

    return SrPlan(
        workW = workW,
        workH = workH,
        outW = workW * targetScale,
        outH = workH * targetScale,
        downscaled = ratio < 1f,
        passes = passes,
        reach = reach,
        tiles = tiles
    )
}

/** Largest bitmap dimension the engine will ever materialise. */
const val MAX_LONG_SIDE = 5120

/**
 * Wall-clock budget for one enhancement run. The working resolution is chosen to
 * fit inside this on the device at hand, so quality degrades gracefully instead of
 * the app appearing to hang.
 */
private const val PASS_BUDGET_MS = 150_000.0

/**
 * Super-resolution model variants shipped inside the APK.
 *
 * Every graph is a 4x upscaler with STATIC tile shapes (mobile accelerators only
 * partition static graphs, and the tiling loop always feeds a fixed window).
 *
 * Model I/O contract (verified offline, per model):
 *   input  float32 [1, 3, T, T]    in [0, 1]
 *   output float32 [1, 3, 4T, 4T]  (may exceed [0,1] - the blit clamps)
 *
 * `tilePad` is the context margin discarded after each inference. The networks
 * have a receptive field of tens of pixels, so a tile's outermost rows/columns
 * are computed from truncated context. Measured on a textured test image, a 12px
 * pad left a visible grid of seams in the difference map; 24px removed it.
 *
 * `workCapCpu` / `workCapAccel` are hard ceilings on the working resolution (long
 * side). The engine normally stays well under them - it derives the real limit from
 * the measured cost of the device - but they stop a fast machine from asking for
 * something that would blow the heap.
 *
 * Tile sizes are the throughput sweet spot measured on this project's test bench:
 * a bigger tile amortises the discarded pad, so 192 beats 128 for x4plus (-20%
 * wall clock) and 192 beats 128 for anime 6B (-27%). Past ~256 the gain flattens.
 */
enum class SrModel(
    val assetName: String,
    val label: String,
    val summary: String,
    val scale: Int,
    val tileSize: Int,
    val tilePad: Int,
    val workCapCpu: Int,
    val workCapAccel: Int
) {
    QUICK(
        assetName = "quicksrnetlarge.onnx",
        label = "极速",
        summary = "Qualcomm QuickSRNet，秒级出图，适合先看效果",
        scale = 4,
        tileSize = 256,
        tilePad = 16,
        workCapCpu = 2048,
        workCapAccel = 2560
    ),
    FAST(
        assetName = "realesrgan_general_x4v3.onnx",
        label = "均衡",
        summary = "Real-ESRGAN x4v3，速度与画质兼顾，日常照片首选",
        scale = 4,
        tileSize = 256,
        tilePad = 24,
        workCapCpu = 1280,
        workCapAccel = 1792
    ),
    QUALITY(
        assetName = "realesrgan_x4plus.onnx",
        label = "画质",
        summary = "Real-ESRGAN x4plus，细节最丰富，耗时最长",
        scale = 4,
        tileSize = 192,
        tilePad = 24,
        workCapCpu = 896,
        workCapAccel = 1280
    ),
    ANIME(
        assetName = "realesrgan_anime6b.onnx",
        label = "动漫",
        summary = "Real-ESRGAN anime 6B，插画/动漫专用，线条干净",
        scale = 4,
        tileSize = 192,
        tilePad = 32,
        workCapCpu = 1024,
        workCapAccel = 1536
    ),
    ANIME_LITE(
        assetName = "realesrgan_anime_lite.onnx",
        label = "动漫轻量",
        summary = "轻量动漫模型，速度接近极速版，适合批量处理",
        scale = 4,
        tileSize = 256,
        tilePad = 24,
        workCapCpu = 1536,
        workCapAccel = 2048
    )
}

class SuperResolutionEngine(private val context: Context) {

    companion object {
        private const val TAG = "SuperResEngine"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var loadedModel: SrModel? = null
    private var loadedNnapi = false
    private var inputName = "image"
    private var outputName = "upscaled_image"

    @Volatile
    var lastUsedNnapi: Boolean = false
        private set

    /** Measured milliseconds for one tile on the loaded session; 0 = not measured. */
    @Volatile
    var tileMillis: Long = 0L
        private set

    /** True when the currently loaded session was created with the NNAPI backend. */
    fun isLoaded(model: SrModel, nnapi: Boolean): Boolean =
        session != null && loadedModel == model && loadedNnapi == nnapi

    fun release() {
        try {
            session?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "session close failed", t)
        }
        session = null
        loadedModel = null
        tileMillis = 0L
    }

    /**
     * Makes sure the requested model is loaded. Returns true when NNAPI was actually used.
     * Falls back to the plain CPU backend if NNAPI is unavailable or fails.
     */
    fun load(model: SrModel, useNnapi: Boolean): Boolean {
        if (isLoaded(model, useNnapi)) return loadedNnapi
        release()

        val file = ensureModelFile(model)
        var nnapiOk = false

        if (useNnapi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                session = createSession(file, nnapi = true)
                inputName = session!!.inputNames.first()
                outputName = session!!.outputNames.first()
                loadedModel = model
                loadedNnapi = true
                lastUsedNnapi = true
                nnapiOk = true
                Log.i(TAG, "loaded ${model.name} with NNAPI")
            } catch (t: Throwable) {
                Log.w(TAG, "NNAPI session failed, falling back to CPU: ${t.message}")
                session = null
            }
        }

        if (!nnapiOk) {
            session = createSession(file, nnapi = false)
            inputName = session!!.inputNames.first()
            outputName = session!!.outputNames.first()
            loadedModel = model
            loadedNnapi = false
            lastUsedNnapi = false
            Log.i(TAG, "loaded ${model.name} on CPU")
        }
        return nnapiOk
    }

    /**
     * Runs a couple of tiles through the live session to learn this device's real
     * per-tile cost. Used for the ETA - a desktop figure would be off by 3-5x.
     */
    fun calibrate() {
        val sess = session ?: return
        val model = loadedModel ?: return
        val tile = model.tileSize
        val input = FloatArray(3 * tile * tile) { i -> ((i * 37) % 255) / 255f }
        val output = FloatArray(3 * tile * model.scale * tile * model.scale)
        try {
            runGraph(sess, input, output, tile)   // warm-up: first run includes setup
            val t0 = SystemClock.elapsedRealtime()
            val reps = 2
            repeat(reps) { runGraph(sess, input, output, tile) }
            tileMillis = (SystemClock.elapsedRealtime() - t0) / reps
            Log.i(TAG, "${model.name} tile(${tile}) = ${tileMillis} ms")
        } catch (t: Throwable) {
            tileMillis = 0L
            Log.w(TAG, "calibration failed", t)
        }
    }

    private fun createSession(file: File, nnapi: Boolean): OrtSession {
        val opts = OrtSession.SessionOptions()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        opts.setIntraOpNumThreads(threads)
        opts.setInterOpNumThreads(1)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (nnapi) {
            opts.addNnapi()
        }
        return env.createSession(file.absolutePath, opts)
    }

    /** Copies the ONNX graph out of assets into app storage (ORT needs a real file path). */
    private fun ensureModelFile(model: SrModel): File {
        val target = File(context.filesDir, model.assetName)
        val expected = try {
            context.assets.openFd(model.assetName).use { it.length }
        } catch (t: Throwable) {
            -1L
        }
        if (target.exists() && (expected <= 0L || target.length() == expected)) {
            return target
        }
        Log.i(TAG, "extracting ${model.assetName} (expected=$expected) -> ${target.absolutePath}")
        context.assets.open(model.assetName).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output, 1 shl 16)
            }
        }
        return target
    }

    /**
     * Runs the model as many times as [targetScale] needs and returns the finished
     * bitmap.
     *
     * Each pass is a full 4x network application, so 8x/16x chain two passes and
     * resample the 16x intermediate down to the requested factor. That supersamples
     * rather than interpolating, which is where the extra detail comes from.
     *
     * @param onProgress fraction in [0,1] across all passes
     */
    fun upscale(
        src: Bitmap,
        model: SrModel,
        targetScale: Int,
        onProgress: (Float) -> Unit
    ): Bitmap {
        val passes = passesFor(model.scale, targetScale)
        var cur = src
        var owned = false

        for (p in 0 until passes) {
            val next = upscaleOnce(cur, model) { f -> onProgress((p + f) / passes) }
            if (owned && next !== cur) cur.recycle()
            cur = next
            owned = true
        }

        val reach = model.scale.toDouble().pow(passes).roundToInt()
        if (reach != targetScale) {
            val ratio = targetScale.toFloat() / reach
            val nw = max(1, (cur.width * ratio).roundToInt())
            val nh = max(1, (cur.height * ratio).roundToInt())
            val scaled = Bitmap.createScaledBitmap(cur, nw, nh, true)
            if (scaled !== cur) {
                if (owned) cur.recycle()
                cur = scaled
                owned = true
            }
        }
        return cur
    }

    /**
     * One 4x pass over [src], tile by tile.
     *
     * @param onProgress fraction in [0,1] for this pass
     */
    private fun upscaleOnce(
        src: Bitmap,
        model: SrModel,
        onProgress: (Float) -> Unit
    ): Bitmap {
        val sess = session ?: error("model not loaded")
        val scale = model.scale
        val tile = model.tileSize
        val pad = model.tilePad
        val valid = tile - pad * 2
        require(valid > 0) { "invalid tile geometry" }

        val w = src.width
        val h = src.height
        val outW = w * scale
        val outH = h * scale

        // Pull the whole source into an int[] once - per-pixel getPixel() is far too slow.
        val srcPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(outW * outH)

        val cols = ceil(w.toDouble() / valid).toInt()
        val rows = ceil(h.toDouble() / valid).toInt()
        val total = cols * rows

        val tilePixels = IntArray(tile * tile)
        val nchw = FloatArray(3 * tile * tile)
        val tileOut = FloatArray(3 * tile * scale * tile * scale)

        var done = 0
        for (ty in 0 until rows) {
            for (tx in 0 until cols) {
                val vx = tx * valid
                val vy = ty * valid
                val vw = min(valid, w - vx)
                val vh = min(valid, h - vy)
                if (vw <= 0 || vh <= 0) continue

                fillTile(srcPixels, w, h, tilePixels, tile, vx - pad, vy - pad)
                toNchw(tilePixels, nchw, tile)
                runGraph(sess, nchw, tileOut, tile)

                blit(
                    dst = outPixels,
                    dstStride = outW,
                    srcPlane = tileOut,
                    srcStride = tile * scale,
                    cropX = pad * scale,
                    cropY = pad * scale,
                    cropW = vw * scale,
                    cropH = vh * scale,
                    dstX = vx * scale,
                    dstY = vy * scale
                )

                done++
                onProgress(done.toFloat() / total)
            }
        }

        out.setPixels(outPixels, 0, outW, 0, 0, outW, outH)

        // Bitmap.hasAlpha() is true for every ARGB_8888 bitmap, including fully opaque
        // photos, so probe the actual pixels before paying for a second upscale pass.
        if (hasTransparency(srcPixels)) {
            applyUpscaledAlpha(src, out, srcPixels)
        }
        return out
    }

    private fun hasTransparency(pixels: IntArray): Boolean {
        for (p in pixels) {
            if (((p ushr 24) and 0xFF) != 0xFF) return true
        }
        return false
    }

    private fun runGraph(sess: OrtSession, input: FloatArray, output: FloatArray, tile: Int) {
        val side = tile.toLong()
        val shape = longArrayOf(1, 3, side, side)
        val buffer = FloatBuffer.wrap(input)
        OnnxTensor.createTensor(env, buffer, shape).use { tensor ->
            sess.run(Collections.singletonMap(inputName, tensor)).use { result ->
                val outTensor = result.get(0) as OnnxTensor
                val fb = outTensor.floatBuffer
                fb.get(output)
            }
        }
    }

    /**
     * Fills a tile x tile window.
     *
     * Reads outside the image are *mirrored* rather than clamped. Clamping hands the
     * network a flat run of identical pixels, which it hallucinates as a soft halo
     * along the outer frame of the result (visible in the residual map as a bright
     * border). Mirroring gives it plausible continuation instead.
     */
    private fun fillTile(
        src: IntArray,
        srcW: Int,
        srcH: Int,
        dst: IntArray,
        tile: Int,
        originX: Int,
        originY: Int
    ) {
        var i = 0
        for (y in 0 until tile) {
            val sy = mirror(originY + y, srcH)
            val row = sy * srcW
            for (x in 0 until tile) {
                val sx = mirror(originX + x, srcW)
                dst[i++] = src[row + sx]
            }
        }
    }

    /** Reflects [v] into [0, n): ... 2 1 | 0 1 2 ... n-1 | n-2 n-3 ... */
    private fun mirror(v: Int, n: Int): Int {
        if (n <= 1) return 0
        val period = 2 * n - 2
        val m = ((v % period) + period) % period
        return if (m < n) m else period - m
    }

    /** ARGB ints -> planar float RGB in [0,1]. */
    private fun toNchw(pixels: IntArray, out: FloatArray, tile: Int) {
        val plane = tile * tile
        val inv = 1f / 255f
        for (i in 0 until plane) {
            val c = pixels[i]
            out[i] = ((c ushr 16) and 0xFF) * inv
            out[plane + i] = ((c ushr 8) and 0xFF) * inv
            out[2 * plane + i] = (c and 0xFF) * inv
        }
    }

    private fun blit(
        dst: IntArray,
        dstStride: Int,
        srcPlane: FloatArray,
        srcStride: Int,
        cropX: Int,
        cropY: Int,
        cropW: Int,
        cropH: Int,
        dstX: Int,
        dstY: Int
    ) {
        val plane = srcStride * srcStride
        var dy = dstY
        for (y in 0 until cropH) {
            val srcRow = (cropY + y) * srcStride + cropX
            var dstIdx = dy * dstStride + dstX
            for (x in 0 until cropW) {
                val si = srcRow + x
                val r = (srcPlane[si].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val g = (srcPlane[plane + si].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val b = (srcPlane[2 * plane + si].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                dst[dstIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            dy++
        }
    }

    /** The model has no alpha channel - scale the mask separately and re-attach it. */
    private fun applyUpscaledAlpha(src: Bitmap, out: Bitmap, srcPixels: IntArray) {
        val w = src.width
        val h = src.height
        val outW = out.width
        val outH = out.height

        val mask = IntArray(w * h)
        for (i in mask.indices) {
            val a = (srcPixels[i] ushr 24) and 0xFF
            mask[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
        }
        val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        maskBmp.setPixels(mask, 0, w, 0, 0, w, h)
        val scaledMask = Bitmap.createScaledBitmap(maskBmp, outW, outH, true)

        val outPixels = IntArray(outW * outH)
        out.getPixels(outPixels, 0, outW, 0, 0, outW, outH)
        val maskPixels = IntArray(outW * outH)
        scaledMask.getPixels(maskPixels, 0, outW, 0, 0, outW, outH)

        for (i in outPixels.indices) {
            val a = (maskPixels[i] ushr 16) and 0xFF
            outPixels[i] = (a shl 24) or (outPixels[i] and 0x00FFFFFF)
        }
        out.setPixels(outPixels, 0, outW, 0, 0, outW, outH)

        maskBmp.recycle()
        scaledMask.recycle()
    }
}
