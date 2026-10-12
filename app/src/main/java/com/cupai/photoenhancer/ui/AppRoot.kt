package com.cupai.photoenhancer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cupai.photoenhancer.EnhanceViewModel

/**
 * Two destinations, no navigation library: the enhancer and the built-in gallery.
 *
 * The ViewModel is hoisted here so the picked photo survives the trip through the
 * browser, and so both screens share one ONNX session.
 */
@Composable
fun AppRoot() {
    val vm: EnhanceViewModel = viewModel()
    var showBrowser by rememberSaveable { mutableStateOf(false) }

    val systemPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(vm::pickImage) }

    val openSystemPicker: () -> Unit = {
        systemPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    if (showBrowser) {
        PhotoBrowserScreen(
            onPick = { image ->
                vm.pickMedia(image)
                showBrowser = false
            },
            onBack = { showBrowser = false },
            onUseSystemPicker = {
                showBrowser = false
                openSystemPicker()
            }
        )
    } else {
        EnhanceScreen(
            vm = vm,
            onOpenBrowser = { showBrowser = true },
            onOpenSystemPicker = openSystemPicker
        )
    }
}
