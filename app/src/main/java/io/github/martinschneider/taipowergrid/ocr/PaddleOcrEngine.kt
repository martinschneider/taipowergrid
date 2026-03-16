package io.github.martinschneider.taipowergrid.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * OCR engine backed by PaddleOCR PP-OCRv4 via ONNX Runtime (MIT + Apache 2.0 — F-Droid eligible).
 *
 * Pipeline:
 *   1. DB text detection  → bounding boxes
 *   2. Perspective-crop each box
 *   3. SVTR text recognition → character strings
 *   4. Concatenate and return all text
 *
 * Models in assets/models/:
 *   det.onnx   — en_PP-OCRv3_det_infer  (2.4 MB, DBNet detector)
 *   rec.onnx   — en_PP-OCRv3_rec_infer  (8.6 MB, SVTR/CRNN recogniser)
 *   en_dict.txt — 95-char English character dictionary
 */
class PaddleOcrEngine(private val context: Context) : OcrEngine {

    private val ortEnv = OrtEnvironment.getEnvironment()
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var characterDict: List<String> = emptyList()

    companion object {
        private const val TAG = "PaddleOcrEngine"

        // Detection constants
        private const val DET_LIMIT_SIDE = 960
        private const val DET_THRESH = 0.3f
        private const val DET_BOX_THRESH = 0.5f
        private const val DET_UNCLIP_RATIO = 1.6f
        private const val DET_MIN_SIZE = 3
        private const val DET_MAX_CANDIDATES = 1000
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)   // ImageNet, BGR order
        private val DET_STD  = floatArrayOf(0.229f, 0.224f, 0.225f)

        // Recognition constants
        private const val REC_IMG_H = 48
        private const val REC_BATCH = 6
        private const val REC_CONF_THRESHOLD = 0.5f
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (detSession != null) return@withContext
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        detSession = context.assets.open("models/det.onnx").use {
            ortEnv.createSession(it.readBytes(), opts)
        }
        recSession = context.assets.open("models/rec.onnx").use {
            ortEnv.createSession(it.readBytes(), opts)
        }
        characterDict = buildList {
            add("")   // index 0 = CTC blank
            context.assets.open("models/en_dict.txt").bufferedReader().forEachLine { line ->
                if (line.isNotEmpty()) add(line)
            }
        }
        Log.d(TAG, "PP-OCRv4 ready — dict size=${characterDict.size}")
    }

    override fun close() {
        detSession?.close(); detSession = null
        recSession?.close(); recSession = null
    }

    // ── Public entry point ────────────────────────────────────────────────────

    override suspend fun recognizeFile(imageFile: File): String =
        withContext(Dispatchers.Default) {
            val bitmap = loadBitmapRespectingExif(imageFile) ?: return@withContext ""
            try {
                val boxes = detectTextRegions(bitmap)
                Log.d(TAG, "Detected ${boxes.size} text regions")
                if (boxes.isEmpty()) return@withContext ""
                val crops = boxes.map { cropTextRegion(bitmap, it.points) }
                val text = recognizeCrops(crops).joinToString("\n")
                crops.forEach { it.recycle() }
                text
            } finally {
                bitmap.recycle()
            }
        }

    // ── Image loading ─────────────────────────────────────────────────────────

    private fun loadBitmapRespectingExif(file: File): Bitmap? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val rotation = try {
            when (ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        if (rotation == 0f) return bitmap
        val m = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        bitmap.recycle()
        return rotated
    }

    // ── Detection ─────────────────────────────────────────────────────────────

    private data class Pt(val x: Float, val y: Float)
    private data class TextBox(val points: List<Pt>, val score: Float)

    private fun detectTextRegions(bitmap: Bitmap): List<TextBox> {
        val det = detSession ?: return emptyList()

        // Resize keeping longest side ≤ DET_LIMIT_SIDE, both dims multiple of 32
        val origW = bitmap.width; val origH = bitmap.height
        val scale = min(1f, DET_LIMIT_SIDE.toFloat() / max(origW, origH))
        val dstW = max(32, (origW * scale / 32).toInt() * 32)
        val dstH = max(32, (origH * scale / 32).toInt() * 32)

        val resized = Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
        val pixels = IntArray(dstW * dstH).also { resized.getPixels(it, 0, dstW, 0, 0, dstW, dstH) }
        if (resized !== bitmap) resized.recycle()

        // Build NCHW float tensor with ImageNet normalisation, BGR channel order
        val imageSize = dstW * dstH
        val inputArray = FloatArray(3 * imageSize)
        for (i in 0 until imageSize) {
            val px = pixels[i]
            val b = (px and 0xFF)          / 255f
            val g = ((px shr 8)  and 0xFF) / 255f
            val r = ((px shr 16) and 0xFF) / 255f
            inputArray[i]               = (b - DET_MEAN[0]) / DET_STD[0]
            inputArray[imageSize + i]   = (g - DET_MEAN[1]) / DET_STD[1]
            inputArray[2 * imageSize + i] = (r - DET_MEAN[2]) / DET_STD[2]
        }

        val shape = longArrayOf(1L, 3L, dstH.toLong(), dstW.toLong())
        val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(inputArray), shape)
        val inputName = det.inputNames.iterator().next()
        val rawOutput: FloatArray
        det.run(mapOf(inputName to tensor)).use { results ->
            val out = results[0] as OnnxTensor
            val buf = out.floatBuffer
            rawOutput = FloatArray(buf.remaining()).also { buf.get(it) }
        }
        tensor.close()

        // Probability map → boxes
        val probMap = Array(dstH) { y -> FloatArray(dstW) { x -> rawOutput[y * dstW + x] } }
        val binary  = Array(dstH) { y -> BooleanArray(dstW) { x -> probMap[y][x] > DET_THRESH } }

        val scaleX = origW.toFloat() / dstW
        val scaleY = origH.toFloat() / dstH

        return extractComponents(binary, dstW, dstH)
            .sortedByDescending { it.size }
            .take(DET_MAX_CANDIDATES)
            .mapNotNull { component ->
                if (component.size < 4) return@mapNotNull null
                val hull = convexHull(component)
                if (hull.size < 3) return@mapNotNull null
                val rect = minAreaRect(hull)
                if (rect.isEmpty()) return@mapNotNull null
                val score = boxScore(probMap, rect)
                if (score < DET_BOX_THRESH) return@mapNotNull null
                val expanded = unclip(rect, DET_UNCLIP_RATIO)
                if (expanded.isEmpty()) return@mapNotNull null
                val expandedRect = minAreaRect(expanded)
                if (expandedRect.isEmpty()) return@mapNotNull null
                val minSide = min(dist(expandedRect[0], expandedRect[1]),
                                  dist(expandedRect[1], expandedRect[2]))
                if (minSide < DET_MIN_SIZE) return@mapNotNull null

                val ordered = orderClockwise(
                    expandedRect.map { pt ->
                        Pt((pt.x.coerceIn(0f, (dstW - 1).toFloat())) * scaleX,
                           (pt.y.coerceIn(0f, (dstH - 1).toFloat())) * scaleY)
                    }
                )
                TextBox(ordered, score)
            }
            .sortedWith(compareBy({ it.points.minOf { p -> p.y } },
                                  { it.points.minOf { p -> p.x } }))
    }

    // BFS connected components
    private fun extractComponents(binary: Array<BooleanArray>, w: Int, h: Int): List<List<Pt>> {
        val visited = Array(h) { BooleanArray(w) }
        val result = mutableListOf<List<Pt>>()
        val dx = intArrayOf(0, 0, 1, -1); val dy = intArrayOf(1, -1, 0, 0)
        for (sy in 0 until h) for (sx in 0 until w) {
            if (!binary[sy][sx] || visited[sy][sx]) continue
            val comp = mutableListOf<Pt>()
            val q = ArrayDeque<Pair<Int, Int>>()
            q.add(sx to sy); visited[sy][sx] = true
            while (q.isNotEmpty()) {
                val (cx, cy) = q.removeFirst()
                comp.add(Pt(cx.toFloat(), cy.toFloat()))
                for (d in 0..3) {
                    val nx = cx + dx[d]; val ny = cy + dy[d]
                    if (nx in 0 until w && ny in 0 until h && binary[ny][nx] && !visited[ny][nx]) {
                        visited[ny][nx] = true; q.add(nx to ny)
                    }
                }
            }
            result.add(comp)
        }
        return result
    }

    // Andrew's monotone-chain convex hull
    private fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun convexHull(pts: List<Pt>): List<Pt> {
        val s = pts.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = mutableListOf<Pt>()
        for (p in s) { while (lower.size >= 2 && cross(lower[lower.size-2], lower.last(), p) <= 0f) lower.removeAt(lower.size - 1); lower.add(p) }
        val upper = mutableListOf<Pt>()
        for (p in s.reversed()) { while (upper.size >= 2 && cross(upper[upper.size-2], upper.last(), p) <= 0f) upper.removeAt(upper.size - 1); upper.add(p) }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun dist(a: Pt, b: Pt) = sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2))

    // Rotating-calipers minimum-area rectangle
    private fun minAreaRect(hull: List<Pt>): List<Pt> {
        if (hull.size < 3) return emptyList()
        var best = emptyList<Pt>(); var minArea = Float.MAX_VALUE
        for (i in hull.indices) {
            val a = hull[i]; val b = hull[(i + 1) % hull.size]
            val len = dist(a, b); if (len < 1e-6f) continue
            val ex = (b.x - a.x) / len; val ey = (b.y - a.y) / len
            val nx = -ey; val ny = ex
            var minP = Float.MAX_VALUE; var maxP = -Float.MAX_VALUE
            var minO = Float.MAX_VALUE; var maxO = -Float.MAX_VALUE
            for (pt in hull) {
                val rx = pt.x - a.x; val ry = pt.y - a.y
                val p = rx * ex + ry * ey; val o = rx * nx + ry * ny
                if (p < minP) minP = p; if (p > maxP) maxP = p
                if (o < minO) minO = o; if (o > maxO) maxO = o
            }
            val area = (maxP - minP) * (maxO - minO)
            if (area < minArea && (maxP - minP) > 1e-3f && (maxO - minO) > 1e-3f) {
                minArea = area
                fun c(dp: Float, dO: Float) = Pt(a.x + ex * dp + nx * dO, a.y + ey * dp + ny * dO)
                best = listOf(c(minP, minO), c(maxP, minO), c(maxP, maxO), c(minP, maxO))
            }
        }
        return best
    }

    // Average probability inside a polygon (scanline rasterisation)
    private fun boxScore(prob: Array<FloatArray>, box: List<Pt>): Float {
        val h = prob.size; val w = prob[0].size
        val xMin = box.minOf { it.x }.coerceIn(0f, (w - 1).toFloat()).toInt()
        val xMax = box.maxOf { it.x }.coerceIn(0f, (w - 1).toFloat()).toInt()
        val yMin = box.minOf { it.y }.coerceIn(0f, (h - 1).toFloat()).toInt()
        val yMax = box.maxOf { it.y }.coerceIn(0f, (h - 1).toFloat()).toInt()
        var sum = 0f; var cnt = 0
        for (y in yMin..yMax) {
            val crossings = mutableListOf<Float>()
            for (k in box.indices) {
                val p1 = box[k]; val p2 = box[(k + 1) % box.size]
                if ((p1.y <= y && p2.y > y) || (p2.y <= y && p1.y > y)) {
                    crossings.add(p1.x + (y - p1.y) / (p2.y - p1.y) * (p2.x - p1.x))
                }
            }
            crossings.sort()
            var i = 0; while (i + 1 < crossings.size) {
                for (x in crossings[i].toInt().coerceIn(xMin, xMax)..crossings[i+1].toInt().coerceIn(xMin, xMax)) {
                    sum += prob[y][x]; cnt++
                }; i += 2
            }
        }
        return if (cnt > 0) sum / cnt else 0f
    }

    // Outward polygon offset (Clipper-style, pure Kotlin)
    private fun polyArea(pts: List<Pt>): Float {
        var a = 0f
        for (i in pts.indices) { val j = (i + 1) % pts.size; a += pts[i].x * pts[j].y - pts[j].x * pts[i].y }
        return abs(a / 2f)
    }

    private fun polyPerimeter(pts: List<Pt>) = pts.indices.sumOf { dist(pts[it], pts[(it + 1) % pts.size]).toDouble() }.toFloat()

    private fun unclip(pts: List<Pt>, ratio: Float): List<Pt> {
        val area = polyArea(pts); val perim = polyPerimeter(pts)
        if (perim < 1e-6f) return emptyList()
        val offset = area * ratio / perim
        val n = pts.size
        data class Line(val px: Float, val py: Float, val dx: Float, val dy: Float)
        val lines = (0 until n).map { i ->
            val a = pts[i]; val b = pts[(i + 1) % n]
            val ex = b.x - a.x; val ey = b.y - a.y
            val len = sqrt(ex * ex + ey * ey); if (len < 1e-6f) return@map Line(a.x, a.y, ex, ey)
            val nx = ey / len; val ny = -ex / len
            Line(a.x + nx * offset, a.y + ny * offset, ex, ey)
        }
        return (0 until n).map { i ->
            val l1 = lines[i]; val l2 = lines[(i + 1) % n]
            val denom = l1.dx * l2.dy - l1.dy * l2.dx
            if (abs(denom) < 1e-6f) Pt(l2.px, l2.py)
            else { val t = ((l2.px - l1.px) * l2.dy - (l2.py - l1.py) * l2.dx) / denom; Pt(l1.px + t * l1.dx, l1.py + t * l1.dy) }
        }
    }

    // Order 4 points: TL → TR → BR → BL
    private fun orderClockwise(pts: List<Pt>): List<Pt> {
        val x = pts.sortedBy { it.x }
        val left  = x.take(2).sortedBy { it.y }
        val right = x.drop(2).sortedBy { it.y }
        return listOf(left[0], right[0], right[1], left[1])
    }

    // ── Crop ──────────────────────────────────────────────────────────────────

    private fun cropTextRegion(bitmap: Bitmap, pts: List<Pt>): Bitmap {
        val w = max(1, max(dist(pts[0], pts[1]), dist(pts[3], pts[2])).toInt())
        val h = max(1, max(dist(pts[0], pts[3]), dist(pts[1], pts[2])).toInt())
        val src = floatArrayOf(pts[0].x, pts[0].y, pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[3].x, pts[3].y)
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val m = Matrix().also { it.setPolyToPoly(src, 0, dst, 0, 4) }
        val cropped = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(cropped).drawBitmap(bitmap, m, Paint(Paint.ANTI_ALIAS_FLAG))
        // Auto-rotate tall crops (e.g. vertical text columns)
        return if (h.toFloat() / w >= 1.5f) {
            val rot = Matrix().apply { postRotate(90f) }
            Bitmap.createBitmap(cropped, 0, 0, w, h, rot, true).also { cropped.recycle() }
        } else cropped
    }

    // ── Recognition ───────────────────────────────────────────────────────────

    /**
     * Normalise a crop for the recogniser:
     *   1. Convert to grayscale (eliminates colour confusion on blue/black plaques).
     *   2. Contrast-stretch to the full 0-255 range.
     *   3. Invert if the background is darker than the text, so the model always
     *      sees dark-text-on-light-background (the dominant training distribution).
     */
    private fun normaliseCrop(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h) { i ->
            val px = pixels[i]
            ((0.299f * ((px shr 16) and 0xFF) +
              0.587f * ((px shr 8)  and 0xFF) +
              0.114f * ( px         and 0xFF)).toInt()).coerceIn(0, 255)
        }

        val minV = gray.minOrNull() ?: 0
        val maxV = gray.maxOrNull() ?: 255
        val range = (maxV - minV).coerceAtLeast(1)
        val stretched = IntArray(w * h) { ((gray[it] - minV) * 255 / range).coerceIn(0, 255) }

        // Invert when background is predominantly dark (light text on dark background)
        val darkCount = stretched.count { it < 128 }
        val needInvert = darkCount > w * h / 2

        val out = IntArray(w * h) { i ->
            val v = if (needInvert) 255 - stretched[i] else stretched[i]
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun recognizeCrops(crops: List<Bitmap>): List<String> {
        val rec = recSession ?: return crops.map { "" }
        val results = mutableListOf<String>()
        crops.chunked(REC_BATCH).forEach { batch ->
            // Normalise each crop, then resize to height=REC_IMG_H
            val resized = batch.map { bmp ->
                val normalised = normaliseCrop(bmp)
                val tw = max(1, ceil(REC_IMG_H.toFloat() * normalised.width / normalised.height).toInt())
                Bitmap.createScaledBitmap(normalised, tw, REC_IMG_H, true)
                    .also { if (normalised !== bmp) normalised.recycle() }
            }
            val batchW = resized.maxOf { it.width }
            val batchN = resized.size
            val imageSize = REC_IMG_H * batchW
            val inputArray = FloatArray(batchN * 3 * imageSize)  // zero-padded
            for ((bi, bmp) in resized.withIndex()) {
                val pixels = IntArray(bmp.width * REC_IMG_H).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, REC_IMG_H) }
                bmp.recycle()
                val base = bi * 3 * imageSize
                for (y in 0 until REC_IMG_H) for (x in 0 until bmp.width) {
                    val px = pixels[y * bmp.width + x]
                    val b = (px and 0xFF)          / 255f
                    val g = ((px shr 8)  and 0xFF) / 255f
                    val r = ((px shr 16) and 0xFF) / 255f
                    val pos = base + y * batchW + x
                    inputArray[pos]                     = (b - 0.5f) / 0.5f
                    inputArray[pos + imageSize]         = (g - 0.5f) / 0.5f
                    inputArray[pos + 2 * imageSize]     = (r - 0.5f) / 0.5f
                }
            }
            val shape = longArrayOf(batchN.toLong(), 3L, REC_IMG_H.toLong(), batchW.toLong())
            val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(inputArray), shape)
            val inputName = rec.inputNames.iterator().next()
            val rawOut: FloatArray
            val numClasses: Int
            rec.run(mapOf(inputName to tensor)).use { res ->
                val out = res[0] as OnnxTensor
                // Shape is [batchN, seqLen, numClasses] — read numClasses from the tensor
                // rather than assuming it equals characterDict.size (they can differ by 1
                // if the model was trained with use_space_char=True).
                numClasses = out.info.shape[2].toInt()
                val buf = out.floatBuffer
                rawOut = FloatArray(buf.remaining()).also { buf.get(it) }
            }
            tensor.close()

            val seqLen = rawOut.size / (batchN * numClasses)
            for (bi in 0 until batchN) {
                val (text, conf) = ctcDecode(rawOut, bi, seqLen, numClasses)
                results.add(if (conf >= REC_CONF_THRESHOLD) text else "")
            }
        }
        return results
    }

    private fun ctcDecode(output: FloatArray, batchIdx: Int, seqLen: Int, numClasses: Int): Pair<String, Float> {
        val base = batchIdx * seqLen * numClasses
        val sb = StringBuilder(); val probs = mutableListOf<Float>()
        var t = 0; var prevIdx = -1
        while (t < seqLen) {
            var maxIdx = 0; var maxVal = output[base + t * numClasses]
            for (c in 1 until numClasses) {
                val v = output[base + t * numClasses + c]; if (v > maxVal) { maxVal = v; maxIdx = c }
            }
            if (maxIdx != 0 && maxIdx != prevIdx && maxIdx < characterDict.size) {
                sb.append(characterDict[maxIdx]); probs.add(maxVal)
            }
            prevIdx = maxIdx; t++
        }
        val conf = if (probs.isNotEmpty()) probs.average().toFloat() else 0f
        return sb.toString() to conf
    }
}
