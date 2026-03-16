package io.github.martinschneider.taipowergrid

import io.github.martinschneider.taipowergrid.model.TaipowerCoordinate

sealed class UiState {
    /** Home screen — two action buttons */
    object Home : UiState()

    /** Full-screen camera preview */
    object CameraActive : UiState()

    /** OCR running — camera frozen */
    data class Processing(val message: String = "Finding coordinates\u2026") : UiState()

    /** OCR succeeded — editable result sheet */
    data class Result(
        val coordinate: TaipowerCoordinate,
        val fromCamera: Boolean = false
    ) : UiState()

    /** No coordinate found, or manual entry requested — editable empty sheet */
    data class ManualEntry(
        val prefill: String? = null,
        val fromCamera: Boolean = false
    ) : UiState()

    /** Fullscreen help / about overlay */
    object Help : UiState()
}
