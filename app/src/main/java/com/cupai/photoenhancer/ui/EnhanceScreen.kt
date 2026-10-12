package com.cupai.photoenhancer.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.cupai.photoenhancer.EnhanceViewModel
import com.cupai.photoenhancer.SCALE_OPTIONS
import com.cupai.photoenhancer.UiState
import com.cupai.photoenhancer.sr.SrModel
import com.cupai.photoenhancer.sr.SrPlan
import com.cupai.photoenhancer.sr.planSr
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhanceScreen(
    vm: EnhanceViewModel,
    onOpenBrowser: () -> Unit,
    onOpenSystemPicker: () -> Unit
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Handle "share image into this app"
    LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        val intent = activity.intent
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            if (uri != null) vm.pickImage(uri)
        }
    }

    val view = LocalView.current
    DisposableEffect(state.busy) {
        view.keepScreenOn = state.busy
        onDispose { view.keepScreenOn = false }
    }

    // Recomputed whenever a knob moves, so the size estimate always matches what the
    // engine would actually do - including the device-speed budget.
    val plan = remember(
        state.source, state.model, state.scale, state.accelerated, state.tileMillis
    ) {
        state.source?.let {
            planSr(it.width, it.height, state.model, state.scale, state.accelerated, state.tileMillis)
        }
    }
    val etaMs = plan?.let { p ->
        if (state.tileMillis > 0L) p.tiles * state.tileMillis else null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI 画质增强", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Real-ESRGAN 本地超分 · 最高 16× · 无需联网",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ImageCard(
                state = state,
                onPick = onOpenBrowser
            )

            if (state.hasSource) {
                SizeInfo(state, plan, etaMs)

                SettingsCard(state = state, vm = vm)

                ActionArea(
                    state = state,
                    onEnhance = vm::enhance,
                    onSave = {
                        vm.saveResult { msg ->
                            scope.launch { snackbar.showSnackbar(msg) }
                        }
                    },
                    onShare = {
                        vm.shareFile { file ->
                            shareFile(context, file)
                        }
                    },
                    onReset = vm::reset
                )
            } else {
                HintCard(onOpenBrowser = onOpenBrowser, onSystemPicker = onOpenSystemPicker)
            }
        }
    }

    state.error?.let { msg ->
        LaunchedEffect(msg) {
            snackbar.showSnackbar(msg)
            vm.dismissError()
        }
    }
}

/* ------------------------------------------------------------------ */
/* Image card: placeholder / source preview / before-after comparison  */
/* ------------------------------------------------------------------ */

@Composable
private fun ImageCard(state: UiState, onPick: () -> Unit) {
    val source = state.source
    val result = state.result

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        when {
            source == null -> PlaceholderBox(onPick)

            result != null -> {
                Column {
                    CompareView(source, result)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "拖动分割线对比效果",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = onPick) { Text("换一张") }
                    }
                }
            }

            else -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    val aspect = source.width.toFloat() / source.height.toFloat()
                    FittedImageBox(aspect) {
                        Image(
                            bitmap = remember(source) { source.asImageBitmap() },
                            contentDescription = "原图预览",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        state.sourceLabel?.let { "已选择 $it" } ?: "已选择原图",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onPick) { Text("换一张") }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderBox(onPick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clipToBounds()
            .clickable(onClick = onPick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "浏览相册并选择照片",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "老照片 / 模糊图 / 低分辨率截图",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Sizes a box to [aspect], shrinking the width when the result would be too tall. */
@Composable
private fun FittedImageBox(aspect: Float, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxHeight = 400.dp
        val widthAtFull = maxWidth
        val heightAtFull = widthAtFull / aspect
        if (heightAtFull <= maxHeight) {
            Box(Modifier.width(widthAtFull).height(heightAtFull)) { content() }
        } else {
            val w = maxHeight * aspect
            Box(Modifier.width(w).height(maxHeight).align(Alignment.Center)) { content() }
        }
    }
}

@Composable
private fun CompareView(before: Bitmap, after: Bitmap) {
    var fraction by remember { mutableFloatStateOf(0.5f) }
    val beforeIb: ImageBitmap = remember(before) { before.asImageBitmap() }
    val afterIb: ImageBitmap = remember(after) { after.asImageBitmap() }
    val aspect = before.width.toFloat() / before.height.toFloat()

    FittedImageBox(aspect) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDrag = { change, _ ->
                            fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    )
                }
        ) {
            Image(
                bitmap = afterIb,
                contentDescription = "增强后",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            Image(
                bitmap = beforeIb,
                contentDescription = "原图",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() }
                    }
            )
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * fraction
                drawLine(
                    color = Color.White,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 3f
                )
                drawCircle(color = Color.White, radius = 11f, center = Offset(x, size.height / 2f))
                drawCircle(color = Color(0xFF3D5AFE), radius = 7f, center = Offset(x, size.height / 2f))
            }
            CornerLabel("原图", Alignment.TopStart)
            CornerLabel("增强后", Alignment.TopEnd)
        }
    }
}

@Composable
private fun CornerLabel(text: String, align: Alignment) {
    Box(Modifier.fillMaxSize().padding(10.dp)) {
        Surface(
            color = Color(0x99000000),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.align(align)
        ) {
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

private fun fmtDuration(ms: Long): String = when {
    ms <= 0L -> "—"
    ms < 1500L -> "1 秒内"
    ms < 60_000L -> "约 ${(ms / 1000.0).roundToInt()} 秒"
    else -> "约 ${(ms / 60_000.0).roundToInt()} 分钟"
}

@Composable
private fun SizeInfo(state: UiState, plan: SrPlan?, etaMs: Long?) {
    val src = state.source ?: return
    if (plan == null) return

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "原图 ${src.width}×${src.height}  →  输出 ${plan.outW}×${plan.outH}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    buildString {
                        if (plan.downscaled) append("送入模型 ${plan.workW}×${plan.workH} · ")
                        append("${plan.tiles} 分块")
                        if (plan.passes > 1) append(" · ${plan.passes} 遍")
                        append(if (state.accelerated) " · 硬件加速" else " · CPU")
                        append(" · 预计 ${fmtDuration(etaMs ?: -1L)}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(state: UiState, vm: EnhanceViewModel) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle("模型")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SrModel.entries.forEach { m ->
                    FilterChip(
                        selected = state.model == m,
                        onClick = { vm.setModel(m) },
                        enabled = !state.busy,
                        label = { Text(m.label) }
                    )
                }
            }
            val heavy = state.model.workCapCpu <= 640
            Text(
                buildString {
                    append(state.model.summary)
                    if (heavy && !state.accelerated) {
                        append("　⚠ 计算量很大，未启用硬件加速时可能耗时数分钟")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (heavy && !state.accelerated) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            SectionTitle("放大倍数")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SCALE_OPTIONS.forEachIndexed { index, s ->
                    SegmentedButton(
                        selected = state.scale == s,
                        onClick = { vm.setScale(s) },
                        enabled = !state.busy,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = SCALE_OPTIONS.size)
                    ) { Text("${s}×") }
                }
            }
            Text(
                when (state.scale) {
                    2 -> "1 遍神经网络，输出 2×（4× 后重采样）"
                    4 -> "1 遍神经网络，原生 4× 输出"
                    8 -> "连续 2 遍神经网络（16× 超采样后降到 8×），耗时明显增加"
                    else -> "连续 2 遍神经网络，画质最强，耗时最长；建议原图不超过 400 万像素"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("细节增强")
                    Text(
                        if (state.sharpen <= 0.01f) "关闭" else "${(state.sharpen * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = state.sharpen,
                    onValueChange = vm::setSharpen,
                    valueRange = 0f..1.5f,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1.6f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("硬件加速 (NNAPI)")
                    Text(
                        when {
                            state.probing -> "正在检测…"
                            !state.hardwareAccel -> "已关闭，使用 CPU 运算"
                            state.nnapiActive -> "已启用，正在使用 NPU/DSP"
                            else -> "本机不可用，已回退到 CPU"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.hardwareAccel,
                    onCheckedChange = vm::setHardwareAccel,
                    enabled = !state.busy
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun ActionArea(
    state: UiState,
    onEnhance: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.busy) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            state.status.ifEmpty { "处理中…" },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "处理期间请勿退出，大图可能需要几分钟",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Button(
                onClick = onEnhance,
                enabled = !state.busy,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    if (state.result == null) "开始增强" else "重新处理",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        if (state.result != null && !state.busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onSave,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("保存到相册")
                }
                FilledTonalButton(
                    onClick = onShare,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("分享")
                }
            }
            Text(
                "输出 ${state.result.width}×${state.result.height}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = onReset,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("重新选择照片")
            }
        }

        state.note?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HintCard(onOpenBrowser: () -> Unit, onSystemPicker: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            elevation = CardDefaults.cardElevation(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("工作原理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "内置 5 个超分辨率模型（QuickSRNet / Real-ESRGAN x4v3 / x4plus / anime 6B / anime 轻量），" +
                        "全部在手机本地运行，照片不会上传到任何服务器。图像会分块送入神经网络推理后无缝拼接，" +
                        "8× 与 16× 通过连续两次推理实现。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "提示：输入分辨率越高，处理时间越长。建议先用「极速」模型试效果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        OutlinedButton(
            onClick = onOpenBrowser,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) { Text("打开照片浏览器") }
        TextButton(onClick = onSystemPicker, modifier = Modifier.fillMaxWidth()) {
            Text("使用系统选择器")
        }
    }
}

/* ------------------------------------------------------------------ */

private fun shareFile(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享增强后的照片"))
}
