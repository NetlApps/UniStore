package ru.updatehub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.updatehub.app.ui.AppViewModel
import ru.updatehub.app.ui.UpdateHubRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()

        setContent {
            val viewModel: AppViewModel = viewModel()
            UpdateHubRoot(viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        CurrentActivity.value = this
    }

    override fun onPause() {
        if (CurrentActivity.value === this) {
            CurrentActivity.value = null
        }
        super.onPause()
    }
}
