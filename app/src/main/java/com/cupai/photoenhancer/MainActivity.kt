package com.cupai.photoenhancer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cupai.photoenhancer.ui.AppRoot
import com.cupai.photoenhancer.ui.PhotoEnhancerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoEnhancerTheme {
                AppRoot()
            }
        }
    }
}
