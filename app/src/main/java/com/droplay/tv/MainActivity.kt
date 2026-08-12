package com.droplay.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droplay.tv.ui.DroplayApp
import com.droplay.tv.ui.DroplayTheme

class MainActivity : ComponentActivity() {
    private val viewModel: DroplayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            DroplayTheme { DroplayApp(state, viewModel) }
        }
    }
}
