package io.github.martinschneider.taipowergrid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import io.github.martinschneider.taipowergrid.ui.AppScreen

/**
 * Single-Activity host for the Compose UI.
 *
 * All navigation logic lives in [MainViewModel]; all UI lives in Composables
 * under [AppScreen].  No ActionBar is used — the UI is fully edge-to-edge.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AppScreen(viewModel = viewModel)
        }
    }
}
