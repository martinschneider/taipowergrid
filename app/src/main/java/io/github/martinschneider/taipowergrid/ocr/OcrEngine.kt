package io.github.martinschneider.taipowergrid.ocr

import java.io.File

/**
 * Common interface for OCR engines so the active implementation can be swapped
 * without touching any other code.
 *
 * Current engine: [PaddleOcrEngine] — PP-OCRv4 via ONNX Runtime (MIT + Apache 2.0)
 */
interface OcrEngine {

    /**
     * One-time setup (model download, tessdata copy, warm-up inference).
     * Safe to call multiple times; subsequent calls are no-ops.
     */
    suspend fun initialize()

    /**
     * Read all text from [imageFile].
     * EXIF rotation must be honoured by the implementation.
     */
    suspend fun recognizeFile(imageFile: File): String

    /** Release native resources. */
    fun close()
}
