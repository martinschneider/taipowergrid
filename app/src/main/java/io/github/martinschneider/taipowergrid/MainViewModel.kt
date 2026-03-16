package io.github.martinschneider.taipowergrid

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.martinschneider.taipowergrid.model.TaipowerCoordinate
import io.github.martinschneider.taipowergrid.ocr.PaddleOcrEngine
import io.github.martinschneider.taipowergrid.utils.CoordinateConverter
import io.github.martinschneider.taipowergrid.utils.CoordinateParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<UiState>(UiState.Home)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val coordinateParser = CoordinateParser()
    private val coordinateConverter = CoordinateConverter()
    private val ocrEngine = PaddleOcrEngine(application)

    var lastCapturedImageFile: File? = null
        private set

    companion object {
        private const val TAG = "MainViewModel"
    }

    init {
        viewModelScope.launch {
            try {
                ocrEngine.initialize()
                Log.d(TAG, "OCR engine ready")
            } catch (e: Exception) {
                Log.e(TAG, "OCR engine initialisation failed", e)
            }
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun onCameraClick() {
        _uiState.value = UiState.CameraActive
    }

    fun onManualEntryClick() {
        _uiState.value = UiState.ManualEntry()
    }

    fun onHelpClick() {
        _uiState.value = UiState.Help
    }

    /** Navigate backwards one level depending on current state. */
    fun onBack() {
        _uiState.value = when (val s = _uiState.value) {
            is UiState.Result     -> if (s.fromCamera) UiState.CameraActive else UiState.Home
            is UiState.ManualEntry -> if (s.fromCamera) UiState.CameraActive else UiState.Home
            is UiState.Processing -> UiState.CameraActive
            is UiState.CameraActive, is UiState.Help -> UiState.Home
            else -> UiState.Home
        }
    }

    fun onCancelProcessing() {
        _uiState.value = UiState.CameraActive
    }

    // ── OCR processing ────────────────────────────────────────────────────────

    fun processImageFile(imageFile: File) {
        lastCapturedImageFile = imageFile
        _uiState.value = UiState.Processing()

        viewModelScope.launch {
            try {
                val text = ocrEngine.recognizeFile(imageFile)
                Log.i(TAG, "OCR result: ${text.replace("\n", " ")}")

                val coordinates = coordinateParser.parseText(text)
                _uiState.value = if (coordinates.isNotEmpty()) {
                    UiState.Result(coordinates.first(), fromCamera = true)
                } else {
                    val prefill = text.trim()
                        .replace(Regex("[^A-Z0-9 ]"), "")
                        .take(10)
                        .trim()
                    UiState.ManualEntry(prefill.ifEmpty { null }, fromCamera = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image processing failed", e)
                _uiState.value = UiState.CameraActive
            }
        }
    }

    // ── Map / coordinate actions ──────────────────────────────────────────────

    /**
     * Convert [coordinateString] and launch the system maps app.
     * Returns a failure result if the string is invalid or no maps app is installed.
     */
    fun showOnMap(coordinateString: String): Result<Unit> {
        val coordinate = TaipowerCoordinate.parse(coordinateString)
            ?: return Result.failure(IllegalArgumentException("Invalid coordinate"))

        return try {
            val gps = coordinateConverter.convertToWGS84(coordinate)
            val label = Uri.encode("Taipower $coordinateString")
            val geoUri = Uri.parse(
                "geo:${gps.latitude},${gps.longitude}" +
                "?q=${gps.latitude},${gps.longitude}($label)"
            )
            val intent = Intent(Intent.ACTION_VIEW, geoUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Map launch failed", e)
            Result.failure(e)
        }
    }

    // ── Bug reporting ─────────────────────────────────────────────────────────

    fun fileBug() {
        val app = getApplication<Application>()
        val logcat = collectRecentLogcat()
        val subject = "Bug Report: TaipowerGrid"

        val base = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("taipowergrid@5164.at"))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, logcat)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            lastCapturedImageFile?.takeIf { it.exists() }?.let { file ->
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        app, "${app.packageName}.fileprovider", file
                    )
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not attach screenshot", e)
                }
            }
        }

        // Try Gmail first, fall back to generic chooser
        try {
            app.startActivity(base.setPackage("com.google.android.gm"))
        } catch (_: Exception) {
            try {
                app.startActivity(
                    Intent.createChooser(base, "Send bug report")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.e(TAG, "No email app available", e)
            }
        }
    }

    private fun collectRecentLogcat(): String = try {
        val process = ProcessBuilder()
            .command("logcat", "-d", "-v", "time", "*:I")
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        lines.takeLast(20).joinToString("\n").ifEmpty { "No log messages found." }
    } catch (e: Exception) {
        "Failed to collect logcat: ${e.message}"
    }

    override fun onCleared() {
        super.onCleared()
        ocrEngine.close()
    }
}
