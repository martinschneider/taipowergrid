package io.github.martinschneider.taipowergrid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.martinschneider.taipowergrid.ocr.PaddleOcrEngine
import io.github.martinschneider.taipowergrid.utils.CoordinateParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Integration tests for the PP-OCRv4 pipeline.
 *
 * **Real pole photos** (baseline tests) — loaded from the `training-data/` folder
 * which is mapped as an androidTest asset source in `build.gradle.kts`.
 * All `.jpg` files in that folder are tested automatically. File names encode the
 * expected coordinate:
 *   • `{GRID}_{CELL}.jpg`   → expects "GRID CELL"
 *   • `{GRID}_{CELL}_{n}.jpg` → also expects "GRID CELL" (duplicate / variant shots)
 *
 * **Synthetic image** — a clean black-on-white bitmap generated at runtime.
 * Validates the full [PaddleOcrEngine] → [CoordinateParser] pipeline with no
 * dependency on physical photos.
 *
 * Run with: `./gradlew connectedAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class OcrIntegrationTest {

    private lateinit var ocrEngine: PaddleOcrEngine
    private lateinit var parser: CoordinateParser
    private lateinit var targetContext: Context

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        ocrEngine = PaddleOcrEngine(targetContext)
        runBlocking { ocrEngine.initialize() }
        parser = CoordinateParser()
    }

    @After
    fun tearDown() {
        ocrEngine.close()
    }

    // ── Real pole photo baseline tests ────────────────────────────────────────

    /**
     * Discovers all `.jpg` files in the androidTest assets (mapped from `training-data/`)
     * and verifies that the OCR pipeline returns the coordinate encoded in the file name.
     *
     * Naming convention: `{GRID}_{CELL}[_{n}].jpg`
     * The optional `_{n}` suffix is ignored — it marks duplicate shots of the same pole.
     */
    @Test
    fun realPhotos_allTrainingData_recognizeCoordinates() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val photos = assets.list("")
            ?.filter { it.endsWith(".jpg") }
            ?.sorted()
            ?: emptyList()

        assertTrue("No training photos found in androidTest assets", photos.isNotEmpty())

        val failures = mutableListOf<String>()

        for (filename in photos) {
            val expected = coordinateFromFilename(filename)
            val text = recognizeAsset(filename)
            val coordinates = parser.parseText(text)
            if (coordinates.none { it.formatted == expected }) {
                failures += "$filename: expected '$expected', " +
                    "got ${coordinates.map { it.formatted }}, OCR: '${text.take(120)}'"
            }
        }

        assertTrue(
            "OCR failures (${failures.size}/${photos.size}):\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    // ── Synthetic image test ──────────────────────────────────────────────────

    /**
     * Black monospace text on white background — sanity-checks the pipeline
     * without requiring physical pole photos.
     */
    @Test
    fun syntheticSign_plainText_recognizes_coordinate() {
        val expected = "G7825 FB24"
        val bitmap = createPoleSignBitmap(expected, textSizeSp = 56f)
        val tempFile = bitmapToTempFile(bitmap)
        bitmap.recycle()
        try {
            val text = runBlocking { ocrEngine.recognizeFile(tempFile) }
            val coordinates = parser.parseText(text)
            assertFalse(
                "OCR on synthetic '$expected' returned no coordinates. OCR output: '$text'",
                coordinates.isEmpty(),
            )
            assertEquals(expected, coordinates.first().formatted)
        } finally {
            tempFile.delete()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Derive the expected coordinate string from a training-photo filename.
     *
     * Examples:
     *   `G7353_DE81.jpg`   → `"G7353 DE81"`
     *   `Z1465_AA47_1.jpg` → `"Z1465 AA47"`  (trailing `_n` suffix ignored)
     */
    private fun coordinateFromFilename(filename: String): String {
        val stem = filename.removeSuffix(".jpg")
        val parts = stem.split("_")
        return "${parts[0]} ${parts[1]}"
    }

    /**
     * Copy a training-data asset to a temp file and run OCR on it.
     * Assets come from the `training-data/` folder (mapped via androidTest.assets.srcDir).
     * The test APK's asset manager is used, not the app's.
     */
    private fun recognizeAsset(filename: String): String {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val tempFile = File(targetContext.cacheDir, "ocr_test_$filename")
        assets.open(filename).use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        }
        return try {
            runBlocking { ocrEngine.recognizeFile(tempFile) }
        } finally {
            tempFile.delete()
        }
    }

    /** Compress [bitmap] to a JPEG temp file so [PaddleOcrEngine.recognizeFile] can read it. */
    private fun bitmapToTempFile(bitmap: Bitmap): File {
        val file = File(targetContext.cacheDir, "ocr_test_synthetic_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return file
    }

    /**
     * Render [text] as black monospace on a white background — mimics a clean
     * pole-sign plaque.
     */
    private fun createPoleSignBitmap(text: String, textSizeSp: Float = 56f): Bitmap {
        val lines = text.split("\n")
        val density = targetContext.resources.displayMetrics.density
        val textSizePx = textSizeSp * density

        val paint = Paint().apply {
            color = Color.BLACK
            this.textSize = textSizePx
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        val lineHeight = paint.fontSpacing
        val maxWidth = lines.maxOf { paint.measureText(it) }
        val margin = 40f

        val bitmapWidth = (maxWidth + margin * 2).toInt().coerceAtLeast(400)
        val bitmapHeight = (lineHeight * lines.size + margin * 2).toInt().coerceAtLeast(150)

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        lines.forEachIndexed { i, line ->
            canvas.drawText(
                line,
                margin,
                margin + lineHeight * (i + 1) - paint.descent(),
                paint,
            )
        }
        return bitmap
    }
}
