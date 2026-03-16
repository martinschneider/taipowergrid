package io.github.martinschneider.taipowergrid.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import io.github.martinschneider.taipowergrid.MainViewModel
import io.github.martinschneider.taipowergrid.UiState

/**
 * Root composable — owns permission handling and routes screens based on [UiState].
 *
 * Architecture note: all Android platform dependencies (Context, permissions, Toast)
 * live here or in screen composables.  [MainViewModel] stays platform-agnostic,
 * making the business logic portable to iOS via Kotlin Multiplatform.
 */
@Composable
fun AppScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onCameraClick()
        else Toast.makeText(context, "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    fun requestCamera() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PermissionChecker.PERMISSION_GRANTED
        if (granted) viewModel.onCameraClick()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    TaipowerTheme {
        Box(modifier.fillMaxSize()) {

            // ── Layer 1: Home OR Camera (never both simultaneously) ──────────
            //
            // Use a single `if` for each composable so Compose preserves identity
            // across UiState transitions that keep the camera active.  If we used
            // separate `when` branches, Compose would recreate CameraScreen on
            // every CameraActive→Processing→Result transition.

            val showCamera = uiState is UiState.CameraActive
                    || uiState is UiState.Processing
                    || (uiState is UiState.Result && (uiState as UiState.Result).fromCamera)
                    || (uiState is UiState.ManualEntry && (uiState as UiState.ManualEntry).fromCamera)

            val isFrozen = uiState is UiState.Processing
                    || (uiState is UiState.Result && (uiState as UiState.Result).fromCamera)
                    || (uiState is UiState.ManualEntry && (uiState as UiState.ManualEntry).fromCamera)

            if (!showCamera && uiState !is UiState.Help) {
                HomeScreen(
                    onCameraClick = ::requestCamera,
                    onManualEntryClick = viewModel::onManualEntryClick,
                    onHelpClick = viewModel::onHelpClick,
                    onFileBug = viewModel::fileBug,
                )
            }

            if (showCamera) {
                CameraScreen(
                    onImageCaptured = viewModel::processImageFile,
                    onClose = viewModel::onBack,
                    isFrozen = isFrozen,
                )
            }

            // ── Layer 2: Processing spinner (overlays camera) ────────────────
            if (uiState is UiState.Processing) {
                ProcessingOverlay(
                    message = (uiState as UiState.Processing).message,
                    onCancel = viewModel::onCancelProcessing,
                )
            }

            // ── Layer 3: Result / manual-entry bottom sheet ──────────────────
            val showSheet = uiState is UiState.Result || uiState is UiState.ManualEntry
            if (showSheet) {
                val initialText = when (val s = uiState) {
                    is UiState.Result      -> s.coordinate.formatted
                    is UiState.ManualEntry -> s.prefill.orEmpty()
                    else                   -> ""
                }
                ResultBottomSheet(
                    initialText = initialText,
                    onShowOnMap = { coordText ->
                        val result = viewModel.showOnMap(coordText)
                        if (result.isFailure) {
                            Toast.makeText(
                                context,
                                "No maps app found or invalid coordinate",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onDismiss = viewModel::onBack,
                )
            }

            // ── Layer 4: Full-screen help overlay ────────────────────────────
            if (uiState is UiState.Help) {
                HelpScreen(onClose = viewModel::onBack)
            }
        }
    }
}

// ── Processing overlay ────────────────────────────────────────────────────────

@Composable
private fun ProcessingOverlay(
    message: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.82f)),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                CircularProgressIndicator(color = Color.White)
                Text(message, color = Color.White)
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}
