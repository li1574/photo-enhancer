package com.cupai.photoenhancer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cupai.photoenhancer.photo.MediaImage
import com.cupai.photoenhancer.sr.BitmapUtils
import com.cupai.photoenhancer.sr.SrModel
import com.cupai.photoenhancer.sr.SrPlan
import com.cupai.photoenhancer.sr.SuperResolutionEngine
import com.cupai.photoenhancer.sr.planSr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Every magnification the UI offers. */
val SCALE_OPTIONS = listOf(2, 4, 8, 16)

private const val TAG = "EnhanceViewModel"

data class UiState(
    val sourceUri: Uri? = null,
    val source: Bitmap? = null,
    val sourceLabel: String? = null,
    val result: Bitmap? = null,
    val model: SrModel = SrModel.FAST,
    val scale: Int = 4,
    val sharpen: Float = 0f,
    val hardwareAccel: Boolean = true,
    val nnapiActive: Boolean = false,
    val probing: Boolean = false,
    val busy: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val note: String? = null,
    val error: String? = null,
    val savedUri: Uri? = null,
    /** Measured per-tile cost of the loaded model on this device, in ms. */
    val tileMillis: Long = 0L
) {
    val hasSource: Boolean get() = source != null

    /** Only trust NNAPI once a session actually came up on it. */
    val accelerated: Boolean get() = hardwareAccel && nnapiActive
}

class EnhanceViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = SuperResolutionEngine(app)

    /**
     * Serialises everything that touches the ONNX session. The startup probe and an
     * enhancement run must never swap or close the session under each other.
     */
    private val engineLock = Mutex()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Decode size. Larger than any working size so switching settings stays sharp,
     * but bounded so a 50 MP camera shot does not blow the heap on decode.
     */
    private val decodeLongSide = 2048

    init {
        // Resolve the real acceleration status up front so the size estimate the user
        // sees before pressing the button matches what will actually run.
        if (_state.value.hardwareAccel) probeAcceleration()
    }

    fun plan(): SrPlan? {
        val s = _state.value
        val src = s.source ?: return null
        return planSr(src.width, src.height, s.model, s.scale, s.accelerated, s.tileMillis)
    }

    /** Rough wall-clock estimate for the current settings, or null when unknown. */
    fun etaMillis(): Long? {
        val p = plan() ?: return null
        val perTile = _state.value.tileMillis
        if (perTile <= 0L) return null
        return p.tiles * perTile
    }

    /** Loads a picture straight from the built-in browser (no permission needed here). */
    fun pickMedia(image: MediaImage) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(status = "读取图片…", error = null, note = null, savedUri = null) }
            // Decoded directly rather than through the caching helper: a failure here is
            // user-facing, so the real cause has to survive into the message.
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    BitmapUtils.loadOrientedBitmap(getApplication(), image.uri, decodeLongSide)
                }
            }
            outcome.fold(
                onSuccess = { bmp ->
                    _state.update {
                        it.copy(
                            sourceUri = image.uri,
                            source = bmp,
                            sourceLabel = image.name,
                            result = null,
                            status = "",
                            note = null,
                            error = null,
                            savedUri = null
                        )
                    }
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            status = "",
                            error = "读取图片失败：${t.message ?: t.javaClass.simpleName}"
                        )
                    }
                }
            )
        }
    }

    /** Legacy entry point for the system picker and "share into app". */
    fun pickImage(uri: Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(status = "读取图片…", error = null, note = null, savedUri = null) }
            try {
                val bmp = withContext(Dispatchers.IO) {
                    BitmapUtils.loadOrientedBitmap(getApplication(), uri, decodeLongSide)
                }
                _state.update {
                    it.copy(
                        sourceUri = uri,
                        source = bmp,
                        sourceLabel = null,
                        result = null,
                        status = "",
                        note = null,
                        error = null,
                        savedUri = null
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(status = "", error = "读取图片失败：${t.message}") }
            }
        }
    }

    fun setModel(model: SrModel) {
        _state.update {
            it.copy(model = model, result = null, savedUri = null, nnapiActive = false, tileMillis = 0L)
        }
        if (_state.value.hardwareAccel) probeAcceleration()
    }

    fun setScale(scale: Int) =
        _state.update { it.copy(scale = scale, result = null, savedUri = null) }

    fun setSharpen(v: Float) = _state.update { it.copy(sharpen = v) }

    fun setHardwareAccel(enabled: Boolean) {
        _state.update { it.copy(hardwareAccel = enabled, nnapiActive = false, tileMillis = 0L) }
        if (enabled) probeAcceleration()
    }

    /**
     * Brings the current model up (on NNAPI when allowed) so the UI can report the
     * real acceleration status, and measures per-tile cost for the ETA.
     *
     * An NNAPI session can be created successfully and still fail the moment an op it
     * could not partition is executed. Calibration is the cheapest way to find out, so
     * a failed calibration drops us back to CPU rather than lying to the user.
     */
    private fun probeAcceleration() {
        val model = _state.value.model
        viewModelScope.launch {
            _state.update { it.copy(probing = true) }
            val result = withContext(Dispatchers.Default) {
                engineLock.withLock {
                    var ok = runCatching { engine.load(model, useNnapi = true) }.getOrDefault(false)
                    engine.calibrate()
                    if (ok && engine.tileMillis <= 0L) {
                        Log.w(TAG, "NNAPI session could not run, falling back to CPU")
                        ok = runCatching { engine.load(model, useNnapi = false) }.getOrDefault(false)
                        engine.calibrate()
                    }
                    ok to engine.tileMillis
                }
            }
            _state.update { it.copy(probing = false, nnapiActive = result.first, tileMillis = result.second) }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun reset() {
        _state.update {
            UiState(
                model = it.model,
                scale = it.scale,
                sharpen = it.sharpen,
                hardwareAccel = it.hardwareAccel,
                nnapiActive = it.nnapiActive,
                tileMillis = it.tileMillis
            )
        }
    }

    fun enhance() {
        val snapshot = _state.value
        val src = snapshot.source ?: return
        if (snapshot.busy) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    progress = 0f,
                    status = "加载模型…",
                    error = null,
                    note = null,
                    result = null,
                    savedUri = null
                )
            }
            try {
                val model = snapshot.model
                val targetScale = snapshot.scale
                val notes = mutableListOf<String>()

                // 1. Bring the session up first - the real acceleration status decides
                //    how large a working resolution we can afford.
                var accelerated = withContext(Dispatchers.Default) {
                    engineLock.withLock {
                        val ok = engine.load(model, snapshot.hardwareAccel)
                        if (engine.tileMillis <= 0L) engine.calibrate()
                        ok
                    }
                }
                if (snapshot.hardwareAccel && !accelerated) {
                    notes += "本机未能启用硬件加速，已回退到 CPU 运算"
                }
                _state.update { it.copy(nnapiActive = accelerated) }

                // 2. Geometry. The session is calibrated by now, so the plan already
                //    reflects this device's real speed rather than a desktop figure.
                var plan = planSr(
                    src.width, src.height, model, targetScale, accelerated, engine.tileMillis
                )
                var work = withContext(Dispatchers.Default) {
                    BitmapUtils.downscaleTo(src, max(plan.workW, plan.workH))
                }

                val reportProgress: (Float) -> Unit = { p ->
                    val passNo = (p * plan.passes).toInt().coerceIn(0, plan.passes - 1) + 1
                    val label = if (plan.passes > 1) {
                        "第 $passNo/${plan.passes} 遍 · ${(p * 100).toInt()}%"
                    } else {
                        "AI 超分处理中 ${(p * 100).toInt()}%"
                    }
                    _state.update { it.copy(progress = p, status = label) }
                }

                // 3. Neural upscale. An NNAPI session can come up fine and still throw
                //    the moment an unsupported op runs, so a failed accelerated attempt
                //    is retried once on CPU rather than shown to the user as an error.
                _state.update { it.copy(status = "AI 超分处理中… 0%") }
                val upscaled = try {
                    withContext(Dispatchers.Default) {
                        engineLock.withLock {
                            engine.upscale(work, model, targetScale, reportProgress)
                        }
                    }
                } catch (t: Throwable) {
                    if (!accelerated) throw t
                    Log.w(TAG, "accelerated run failed, retrying on CPU", t)
                    notes += "硬件加速执行失败，已自动改用 CPU 重试"
                    accelerated = false
                    _state.update { it.copy(nnapiActive = false, progress = 0f) }

                    // Re-plan against the CPU's real speed before retrying - keeping the
                    // accelerated working size would mean a far longer wait on CPU.
                    val cpuPlan = withContext(Dispatchers.Default) {
                        engineLock.withLock {
                            engine.load(model, useNnapi = false)
                            engine.calibrate()
                            planSr(src.width, src.height, model, targetScale, false, engine.tileMillis)
                        }
                    }
                    if (cpuPlan.workW < plan.workW) {
                        val shrunk = withContext(Dispatchers.Default) {
                            BitmapUtils.downscaleTo(src, max(cpuPlan.workW, cpuPlan.workH))
                        }
                        if (work !== src) work.recycle()
                        work = shrunk
                    }
                    plan = cpuPlan
                    withContext(Dispatchers.Default) {
                        engineLock.withLock {
                            engine.upscale(work, model, targetScale, reportProgress)
                        }
                    }
                }
                if (work !== src) work.recycle()

                if (plan.downscaled) {
                    notes += "为控制耗时与内存，实际按 ${plan.workW}×${plan.workH} 送入模型"
                }
                if (plan.multiPass) {
                    notes += "${targetScale}× 需要连续跑 ${plan.passes} 遍神经网络，耗时约为 4× 的数倍"
                }

                // 4. Optional unsharp mask.
                var final: Bitmap = upscaled
                if (snapshot.sharpen > 0.01f) {
                    _state.update { it.copy(status = "细节增强…") }
                    final = withContext(Dispatchers.Default) {
                        BitmapUtils.sharpen(final, snapshot.sharpen)
                    }
                    if (final !== upscaled) upscaled.recycle()
                }

                _state.update {
                    it.copy(
                        busy = false,
                        progress = 1f,
                        status = "",
                        result = final,
                        note = notes.joinToString("；").ifEmpty { null }
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        busy = false,
                        status = "",
                        progress = 0f,
                        error = "处理失败：${t.message ?: t.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun saveResult(onDone: (String) -> Unit) {
        val bmp = _state.value.result ?: return
        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    BitmapUtils.saveToGallery(getApplication(), bmp)
                }
                if (uri != null) {
                    _state.update { it.copy(savedUri = uri) }
                    onDone("已保存到相册 Pictures/PhotoEnhancer")
                } else {
                    onDone("保存失败")
                }
            } catch (t: Throwable) {
                onDone("保存失败：${t.message}")
            }
        }
    }

    fun shareFile(onReady: (File) -> Unit) {
        val bmp = _state.value.result ?: return
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                BitmapUtils.writeShareFile(getApplication(), bmp)
            }
            onReady(f)
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine.release()
    }
}
