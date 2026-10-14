package umbra.cam.core.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaMetadataRetriever
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import android.graphics.Matrix as AndroidMatrix

object UmbraCamCapture {
    /**
     * Converts an encoded JPEG frame into the packed NV21 layout used by Camera1 preview
     * callbacks. The dimensions must be even because Camera1 preview buffers are chroma-subsampled.
     */
    fun jpegToNv21(
        jpeg: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray? {
        if (jpeg.isEmpty() || width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            return null
        }
        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
            val bitmap =
                if (decoded.width == width && decoded.height == height) {
                    decoded
                } else {
                    Bitmap.createScaledBitmap(decoded, width, height, true).also { scaled = it }
                }
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val ySize = width * height
            val nv21 = ByteArray(ySize + ySize / 2)

            for (row in 0 until height) {
                for (col in 0 until width) {
                    val color = pixels[row * width + col]
                    val r = (color shr 16) and 0xff
                    val g = (color shr 8) and 0xff
                    val b = color and 0xff
                    nv21[row * width + col] =
                        (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
                            .coerceIn(0, 255)
                            .toByte()
                }
            }

            var chromaIndex = ySize
            for (row in 0 until height step 2) {
                for (col in 0 until width step 2) {
                    var uSum = 0
                    var vSum = 0
                    for (dy in 0..1) {
                        for (dx in 0..1) {
                            val color = pixels[(row + dy) * width + col + dx]
                            val r = (color shr 16) and 0xff
                            val g = (color shr 8) and 0xff
                            val b = color and 0xff
                            uSum += (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128)
                            vSum += (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
                        }
                    }
                    nv21[chromaIndex++] = (vSum / 4).coerceIn(0, 255).toByte()
                    nv21[chromaIndex++] = (uSum / 4).coerceIn(0, 255).toByte()
                }
            }
            nv21
        } catch (_: Throwable) {
            null
        } finally {
            if (scaled != null && scaled !== decoded) scaled?.recycle()
            decoded?.recycle()
        }
    }

    fun yuvFrameToBitmap(frame: MediaCodecYuvDecoder.YuvFrame): Bitmap? =
        try {
            val width = frame.width
            val height = frame.height
            val nv21 = ByteArray(width * height * 3 / 2)
            System.arraycopy(frame.yPlane, 0, nv21, 0, width * height)

            val chromaSize = (width * height) / 4
            val u = frame.uPlane
            val v = frame.vPlane
            var nvIndex = width * height
            val minChroma = minOf(chromaSize, minOf(u.size, v.size))
            for (i in 0 until minChroma) {
                nv21[nvIndex++] = v[i]
                nv21[nvIndex++] = u[i]
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 95, out)
            val jpegBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (_: Throwable) {
            null
        }

    fun createJpeg(
        context: Context,
        path: String,
        targetW: Int,
        targetH: Int,
        rotation: Int,
        mirrored: Boolean,
        timeMs: Int = 1000,
        maxSizeBytes: Int = Int.MAX_VALUE,
        printLog: (String) -> Unit,
        liveFrame: MediaCodecYuvDecoder.YuvFrame? = null,
    ): ByteArray? {
        printLog("Capture Process: Starting for $path (Time:$timeMs ms, Live: ${liveFrame != null}, MaxSize:$maxSizeBytes)")

        var retriever: MediaMetadataRetriever? = null
        return try {
            var rawBitmap: Bitmap? = null

            if (liveFrame != null) {
                rawBitmap = yuvFrameToBitmap(liveFrame)
                if (rawBitmap != null) {
                    printLog("Capture Process: Using live preview frame directly (${rawBitmap.width}x${rawBitmap.height})")
                }
            }

            if (rawBitmap == null) {
                if (path.lowercase().endsWith(".mp4")) {
                    retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, path.toUri())

                    val durationMs =
                        retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    val loopedTimeMs = if (durationMs > 0L) timeMs.toLong() % durationMs else timeMs.toLong()

                    val targetUs = loopedTimeMs * 1000L
                    rawBitmap = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)

                    if (rawBitmap == null) {
                        rawBitmap = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }

                    if (rawBitmap == null) {
                        rawBitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_NEXT_SYNC)
                    }
                } else {
                    context.contentResolver.openInputStream(path.toUri())?.use { BitmapFactory.decodeStream(it) }?.let {
                        rawBitmap = it
                    }
                }
            }

            if (rawBitmap == null) return null

            val sourceW = rawBitmap.width
            val sourceH = rawBitmap.height
            val rotatedSourceW = if (rotation % 180 != 0) sourceH else sourceW
            val rotatedSourceH = if (rotation % 180 != 0) sourceW else sourceH

            val (resolvedTargetW, resolvedTargetH) =
                if ((rotatedSourceW < rotatedSourceH && targetW > targetH) ||
                    (rotatedSourceW > rotatedSourceH && targetW < targetH)
                ) {
                    Pair(targetH, targetW)
                } else {
                    Pair(targetW, targetH)
                }

            val scale = Math.max(resolvedTargetW.toFloat() / rotatedSourceW, resolvedTargetH.toFloat() / rotatedSourceH)
            val matrix =
                AndroidMatrix().apply {
                    postScale(scale, scale)
                    if (rotation != 0) postRotate(rotation.toFloat())
                    if (mirrored) postScale(-1f, 1f)
                }

            val transformedSource = Bitmap.createBitmap(rawBitmap, 0, 0, sourceW, sourceH, matrix, true)

            val finalBitmap = createBitmap(resolvedTargetW, resolvedTargetH)
            android.graphics.Canvas(finalBitmap).apply {
                val left = (resolvedTargetW - transformedSource.width) / 2f
                val top = (resolvedTargetH - transformedSource.height) / 2f
                drawBitmap(transformedSource, left, top, null)
            }

            var result: ByteArray
            var quality = 100
            do {
                val out = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                result = out.toByteArray()
                if (result.size <= maxSizeBytes) break

                quality -= 5
                printLog("Capture Process: Target size exceeded (${result.size} >$maxSizeBytes). Retrying with quality $quality")
            } while (quality > 5)

            printLog("Capture Process: SUCCESS (${result.size} bytes, quality$quality)")

            if (rawBitmap != transformedSource) rawBitmap.recycle()
            transformedSource.recycle()
            finalBitmap.recycle()

            result
        } catch (e: Exception) {
            printLog("Capture Process: ERROR - ${e.message}")
            null
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {
            }
        }
    }
}
