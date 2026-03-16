package io.github.martinschneider.taipowergrid.ui

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "CameraScreen"

@Composable
fun CameraScreen(
    onImageCaptured: (File) -> Unit,
    onClose: () -> Unit,
    isFrozen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val imageCaptureRef = remember { mutableStateOf<ImageCapture?>(null) }
    val cameraRef = remember { mutableStateOf<Camera?>(null) }
    val previewRef = remember { mutableStateOf<Preview?>(null) }
    val previewViewRef = remember { mutableStateOf<PreviewView?>(null) }

    BackHandler(onBack = onClose)

    // Freeze / unfreeze the live preview without unbinding the camera
    LaunchedEffect(isFrozen) {
        val preview = previewRef.value ?: return@LaunchedEffect
        val previewView = previewViewRef.value ?: return@LaunchedEffect
        if (isFrozen) {
            preview.setSurfaceProvider(null)
        } else {
            preview.setSurfaceProvider(previewView.surfaceProvider)
        }
    }

    // Unbind camera when this composable leaves the composition
    DisposableEffect(Unit) {
        onDispose {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { future.get().unbindAll() },
                ContextCompat.getMainExecutor(context)
            )
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewViewRef.value = this
                    bindCamera(ctx, lifecycleOwner, this, previewRef, imageCaptureRef, cameraRef)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        cameraRef.value?.let { cam ->
                            val state = cam.cameraInfo.zoomState.value ?: return@let
                            val newZoom = (state.zoomRatio * zoom)
                                .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                            cam.cameraControl.setZoomRatio(newZoom)
                        }
                    }
                },
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .statusBarsPadding(),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close camera",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }

        ShutterButton(
            onClick = { capturePhoto(context, imageCaptureRef.value, onImageCaptured) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 48.dp),
        )
    }
}

// ── Camera binding ────────────────────────────────────────────────────────────

private fun bindCamera(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    previewRef: MutableState<Preview?>,
    imageCaptureRef: MutableState<ImageCapture?>,
    cameraRef: MutableState<Camera?>,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = future.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        previewRef.value = preview
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        imageCaptureRef.value = capture
        try {
            provider.unbindAll()
            cameraRef.value = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview, capture,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }, ContextCompat.getMainExecutor(context))
}

private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture?,
    onImageCaptured: (File) -> Unit,
) {
    val capture = imageCapture ?: return
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        .format(System.currentTimeMillis())
    val outputFile = File(context.cacheDir, "$name.jpg")

    capture.takePicture(
        ImageCapture.OutputFileOptions.Builder(outputFile).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Capture failed: ${exc.message}", exc)
            }
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onImageCaptured(outputFile)
            }
        },
    )
}

// ── Custom composables ────────────────────────────────────────────────────────


@Composable
private fun ShutterButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .border(2.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)
                .background(Color.White)
        )
    }
}
