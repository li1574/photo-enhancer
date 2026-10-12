package com.cupai.photoenhancer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cupai.photoenhancer.photo.GalleryPermission
import com.cupai.photoenhancer.photo.MediaImage
import com.cupai.photoenhancer.photo.PhotoRepository
import com.cupai.photoenhancer.photo.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val THUMB_PX = 384
private const val PREVIEW_PX = 1280

/**
 * The built-in gallery.
 *
 * Deliberately self-contained: it owns the permission flow, the MediaStore query and
 * the two-level bitmap cache, and hands back a single [MediaImage] when the user
 * commits to one. Nothing is copied into the app before that point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoBrowserScreen(
    onPick: (MediaImage) -> Unit,
    onBack: () -> Unit,
    onUseSystemPicker: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { PhotoRepository(context) }

    var granted by remember { mutableStateOf(GalleryPermission.granted(context)) }
    var partial by remember { mutableStateOf(GalleryPermission.partial(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var images by remember { mutableStateOf<List<MediaImage>?>(null) }
    var reloadToken by remember { mutableStateOf(0) }
    var previewAt by remember { mutableStateOf<Int?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val ok = GalleryPermission.granted(context)
        granted = ok
        partial = GalleryPermission.partial(context)
        denied = !ok
        if (ok) reloadToken++
    }

    // Ask once, automatically - a browser that greets you with a button feels broken.
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(GalleryPermission.required())
    }

    LaunchedEffect(granted, reloadToken) {
        if (!granted) {
            images = emptyList()
            return@LaunchedEffect
        }
        images = null
        val rows = withContext(Dispatchers.IO) { repo.query() }
        images = rows
    }

    BackHandler(enabled = previewAt != null) { previewAt = null }
    BackHandler(enabled = previewAt == null) { onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("照片", fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                !granted -> "未获得相册权限"
                                partial -> "仅可访问你选择的照片"
                                else -> "${images?.size ?: 0} 张照片"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (granted) {
                        IconButton(onClick = { reloadToken++ }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                !granted -> PermissionGate(
                    denied = denied,
                    onRequest = { permissionLauncher.launch(GalleryPermission.required()) },
                    onSystemPicker = onUseSystemPicker
                )

                images == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                images!!.isEmpty() -> EmptyGallery(
                    partial = partial,
                    onPickMore = { permissionLauncher.launch(GalleryPermission.required()) },
                    onSystemPicker = onUseSystemPicker
                )

                else -> PhotoGrid(
                    images = images!!,
                    repo = repo,
                    onOpen = { previewAt = it }
                )
            }

            previewAt?.let { start ->
                PreviewOverlay(
                    images = images.orEmpty(),
                    startIndex = start,
                    onClose = { previewAt = null },
                    onUse = { img ->
                        previewAt = null
                        onPick(img)
                    }
                )
            }
        }
    }
}

/* ---------------------------------------------------------------- grid */

private sealed interface GridEntry {
    val key: String

    data class Header(val label: String, val count: Int) : GridEntry {
        override val key: String get() = "h:$label"
    }

    data class Photo(val image: MediaImage, val index: Int) : GridEntry {
        override val key: String get() = "p:${image.id}"
    }
}

@Composable
private fun PhotoGrid(
    images: List<MediaImage>,
    repo: PhotoRepository,
    onOpen: (Int) -> Unit
) {
    // Day buckets, newest first. Grouping is what makes a 3000-photo library navigable.
    val entries = remember(images) {
        val out = ArrayList<GridEntry>(images.size + 64)
        var lastLabel: String? = null
        var bucketCount = 0
        var bucketLabel = ""
        images.forEachIndexed { index, img ->
            val label = repo.dayLabel(img.dateAdded)
            if (label != lastLabel) {
                if (lastLabel != null) out += GridEntry.Header(bucketLabel, bucketCount)
                bucketLabel = label
                bucketCount = 0
                lastLabel = label
            }
            bucketCount++
            out += GridEntry.Photo(img, index)
        }
        if (lastLabel != null) out += GridEntry.Header(bucketLabel, bucketCount)
        out
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = entries,
            key = { it.key },
            span = { entry ->
                if (entry is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
            }
        ) { entry ->
            when (entry) {
                is GridEntry.Header -> DayHeader(entry.label, entry.count)
                is GridEntry.Photo -> PhotoCell(
                    image = entry.image,
                    onClick = { onOpen(entry.index) }
                )
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$count 张",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PhotoCell(image: MediaImage, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        Thumb(image, Modifier.fillMaxSize())
    }
}

/** Loads a grid-sized bitmap through the shared cache. */
@Composable
private fun Thumb(image: MediaImage, modifier: Modifier) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(initialValue = null, image.id) {
        value = Thumbnails.thumb(context, image, THUMB_PX)?.asImageBitmap()
    }
    bmp?.let {
        Image(
            bitmap = it,
            contentDescription = image.name,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}

/* ------------------------------------------------------------- preview */

/**
 * Full-screen swipe-through viewer. Tapping a thumbnail lands here rather than
 * selecting immediately, because the whole point of a browser is looking first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewOverlay(
    images: List<MediaImage>,
    startIndex: Int,
    onClose: () -> Unit,
    onUse: (MediaImage) -> Unit
) {
    if (images.isEmpty()) return
    val safeStart = startIndex.coerceIn(0, images.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeStart) { images.size }
    val current = images.getOrNull(pagerState.currentPage) ?: return

    Surface(color = Color(0xFF000000), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                PageImage(images[page])
            }

            // Top chrome
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        current.name,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${pagerState.currentPage + 1} / ${images.size} · ${current.sizeLabel}",
                        color = Color(0xFFBDBDBD),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Bottom action
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(16.dp)
            ) {
                Text(
                    "左右滑动浏览 · 原图 ${current.sizeLabel}",
                    color = Color(0xFFBDBDBD),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                Button(
                    onClick = { onUse(current) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("使用这张照片", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun PageImage(image: MediaImage) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(initialValue = null, image.id) {
        value = Thumbnails.preview(context, image, PREVIEW_PX)?.asImageBitmap()
    }
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        val b = bmp
        if (b == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Image(
                bitmap = b,
                contentDescription = image.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp, bottom = 110.dp)
            )
        }
    }
}

/* ------------------------------------------------------- gate / empty */

@Composable
private fun PermissionGate(
    denied: Boolean,
    onRequest: () -> Unit,
    onSystemPicker: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    if (denied) "需要相册权限" else "浏览本机照片",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "内置照片浏览器需要读取相册才能列出照片。图片只在本机处理，" +
                        "不会上传到任何服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRequest,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) { Text("授予相册权限") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onSystemPicker,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) { Text("改用系统选择器（无需权限）") }
    }
}

@Composable
private fun EmptyGallery(
    partial: Boolean,
    onPickMore: () -> Unit,
    onSystemPicker: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (partial) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            if (partial) "还没有选择照片" else "相册里没有找到照片",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (partial) {
                "你只授权了部分照片。点下面的按钮再选一些，或者用系统选择器挑一张。"
            } else {
                "如果照片存在云端或 SD 卡，可能需要先让系统相册扫描到它们。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onPickMore,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (partial) "选择更多照片" else "重新扫描") }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onSystemPicker) { Text("使用系统选择器") }
    }
}
