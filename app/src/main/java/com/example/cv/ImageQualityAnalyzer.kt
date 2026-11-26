package com.example.cv

import android.graphics.Bitmap
import com.example.domain.model.CaptureCommandType
import com.example.domain.model.QualityIssue
import com.example.domain.model.QualityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Calibrated thresholds for on-device frame quality gates at 320px normalized width:
 * - Blur uses 4-neighbor Laplacian variance; HVAC/electrical nameplates require sharper
 *   micro-typography (95.0) than wider structural damage frames (65.0).
 * - Darkness uses BT.601 luminance: mean < 45.0 or >65% of pixels below luma 28.0 indicates
 *   severe underexposure where nameplate/mold detail drops into sensor noise.
 * - Overexposure flags washed-out glare frames (mean > 230.0 or >45% clipped above luma 242.0).
 * - Framing compares Sobel edge density inside the central 72% reticle ROI against the outer
 *   14% border with an 0.85 border weight to catch off-center or blank-center shots.
 */
object QualityThresholds {
    const val NORMALIZED_MAX_WIDTH = 320
    const val BLUR_VARIANCE_NAMEPLATE = 95.0
    const val BLUR_VARIANCE_GENERAL = 65.0
    const val LUMA_MIN_MEAN = 45.0
    const val LUMA_DARK_PIXEL_CUTOFF = 28.0
    const val DARK_PIXEL_MAX_RATIO = 0.65
    const val LUMA_MAX_MEAN = 230.0
    const val LUMA_CLIPPED_PIXEL_CUTOFF = 242.0
    const val CLIPPED_PIXEL_MAX_RATIO = 0.45
    const val FRAMING_MIN_SCORE = 0.36
    const val CENTER_ROI_INSET_RATIO = 0.14
    const val CENTER_EDGE_WEAK_MAX = 8.0
    const val BORDER_EDGE_DOMINANCE_MIN = 18.0
}

/**
 * Pure on-device computer vision image quality gate for field inspection frames.
 * Evaluates blur (Laplacian variance), darkness/exposure (BT.601 luminance histogram),
 * and framing (central ROI vs outer border Sobel edge distribution).
 */
class ImageQualityAnalyzer {

    suspend fun analyze(
        bitmap: Bitmap,
        commandType: CaptureCommandType
    ): QualityReport = withContext(Dispatchers.Default) {
        val startNs = System.nanoTime()

        // Downsample deterministically to max 320px width for fast, consistent scoring
        val targetW = min(QualityThresholds.NORMALIZED_MAX_WIDTH, max(64, bitmap.width))
        val targetH = max(64, (bitmap.height * (targetW.toFloat() / max(1, bitmap.width))).toInt())
        val scaled = if (bitmap.width != targetW || bitmap.height != targetH) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        analyzePixels(pixels, w, h, commandType, startNs)
    }

    fun analyzePixels(
        pixels: IntArray,
        w: Int,
        h: Int,
        commandType: CaptureCommandType,
        startNs: Long = System.nanoTime()
    ): QualityReport {

        val luma = DoubleArray(w * h)
        var sumLuma = 0.0
        var darkCount = 0
        var clippedCount = 0

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = 0.299 * r + 0.587 * g + 0.114 * b
            luma[i] = y
            sumLuma += y
            if (y < QualityThresholds.LUMA_DARK_PIXEL_CUTOFF) darkCount++
            if (y > QualityThresholds.LUMA_CLIPPED_PIXEL_CUTOFF) clippedCount++
        }

        val n = pixels.size.toDouble()
        val meanLuma = sumLuma / n
        val darkRatio = darkCount / n
        val clippedRatio = clippedCount / n

        // Laplacian variance for sharpness / motion blur
        var lapSum = 0.0
        var lapSqSum = 0.0
        var lapCount = 0

        // Sobel edge distribution for center vs border framing check
        var centerEdgeSum = 0.0
        var centerCount = 0
        var borderEdgeSum = 0.0
        var borderCount = 0

        val inset = QualityThresholds.CENTER_ROI_INSET_RATIO
        val xMinCenter = (w * inset).toInt()
        val xMaxCenter = (w * (1.0 - inset)).toInt()
        val yMinCenter = (h * inset).toInt()
        val yMaxCenter = (h * (1.0 - inset)).toInt()

        for (y in 1 until h - 1) {
            val row = y * w
            val rowUp = (y - 1) * w
            val rowDown = (y + 1) * w
            for (x in 1 until w - 1) {
                val center = luma[row + x]
                val up = luma[rowUp + x]
                val down = luma[rowDown + x]
                val left = luma[row + x - 1]
                val right = luma[row + x + 1]

                val lap = up + down + left + right - 4.0 * center
                lapSum += lap
                lapSqSum += lap * lap
                lapCount++

                val gx = (luma[rowUp + x + 1] + 2.0 * right + luma[rowDown + x + 1]) -
                    (luma[rowUp + x - 1] + 2.0 * left + luma[rowDown + x - 1])
                val gy = (luma[rowDown + x - 1] + 2.0 * down + luma[rowDown + x + 1]) -
                    (luma[rowUp + x - 1] + 2.0 * up + luma[rowUp + x + 1])
                val mag = sqrt(gx * gx + gy * gy)

                val inCenter = x in xMinCenter..xMaxCenter && y in yMinCenter..yMaxCenter
                if (inCenter) {
                    centerEdgeSum += mag
                    centerCount++
                } else {
                    borderEdgeSum += mag
                    borderCount++
                }
            }
        }

        val lapMean = if (lapCount > 0) lapSum / lapCount else 0.0
        val lapVariance = if (lapCount > 0) (lapSqSum / lapCount) - (lapMean * lapMean) else 0.0

        val centerMeanEdge = if (centerCount > 0) centerEdgeSum / centerCount else 0.0
        val borderMeanEdge = if (borderCount > 0) borderEdgeSum / borderCount else 0.0
        val framingScore = if (centerMeanEdge + borderMeanEdge > 1e-3) {
            (centerMeanEdge / (centerMeanEdge + borderMeanEdge * 0.85)).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val blurThreshold = when (commandType) {
            CaptureCommandType.NAMEPLATE -> QualityThresholds.BLUR_VARIANCE_NAMEPLATE
            CaptureCommandType.DAMAGE, CaptureCommandType.NOTE -> QualityThresholds.BLUR_VARIANCE_GENERAL
        }

        val issues = mutableListOf<QualityIssue>()
        if (lapVariance < blurThreshold) {
            issues += QualityIssue.BLURRY
        }
        if (meanLuma < QualityThresholds.LUMA_MIN_MEAN || darkRatio > QualityThresholds.DARK_PIXEL_MAX_RATIO) {
            issues += QualityIssue.TOO_DARK
        } else if (meanLuma > QualityThresholds.LUMA_MAX_MEAN || clippedRatio > QualityThresholds.CLIPPED_PIXEL_MAX_RATIO) {
            issues += QualityIssue.OVEREXPOSED
        }
        if (framingScore < QualityThresholds.FRAMING_MIN_SCORE ||
            (centerMeanEdge < QualityThresholds.CENTER_EDGE_WEAK_MAX && borderMeanEdge > QualityThresholds.BORDER_EDGE_DOMINANCE_MIN)
        ) {
            issues += QualityIssue.POOR_FRAMING
        }

        val summary = if (issues.isEmpty()) {
            "PASS • Sharpness ${lapVariance.toInt()} • Luma ${meanLuma.toInt()} • Framing ${(framingScore * 100).toInt()}%"
        } else {
            val reasons = issues.joinToString(", ") { issue ->
                when (issue) {
                    QualityIssue.BLURRY -> "Blur (${lapVariance.toInt()} < ${blurThreshold.toInt()})"
                    QualityIssue.TOO_DARK -> "Dark (Luma ${meanLuma.toInt()} < ${QualityThresholds.LUMA_MIN_MEAN.toInt()})"
                    QualityIssue.OVEREXPOSED -> "Overexposed (Luma ${meanLuma.toInt()})"
                    QualityIssue.POOR_FRAMING -> "Subject off-center (${(framingScore * 100).toInt()}%)"
                }
            }
            "CHECK FAILED • $reasons"
        }

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

        return QualityReport(
            passed = issues.isEmpty(),
            blurScore = lapVariance,
            brightnessScore = meanLuma,
            framingScore = framingScore,
            darkPixelRatio = darkRatio,
            clippedPixelRatio = clippedRatio,
            issues = issues,
            summaryMessage = summary,
            latencyMs = elapsedMs
        )
    }
}
