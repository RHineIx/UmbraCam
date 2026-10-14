package umbra.cam.core.capture

import android.graphics.BitmapFactory
import android.media.Image
import androidx.core.graphics.scale
import kotlin.math.max

/**
 * Handles conversion and geometric transformation of YUV_420_888 and JPEG/Bitmap frames
 * for injection into YUV_420_888 and RGBA_8888 ImageReader streams.
 */
class YuvFrameProcessor {
    private var cachedSource: ByteArray? = null
    private var cachedYuv: YuvData? = null

    private var cachedTransformKey: TransformKey? = null
    private var cachedRotXTable: IntArray? = null
    private var cachedRotYTable: IntArray? = null

    private data class TransformKey(
        val srcW: Int,
        val srcH: Int,
        val dstW: Int,
        val dstH: Int,
        val normRot: Int,
        val isMirrored: Boolean,
    )

    private class YuvData(
        val width: Int,
        val height: Int,
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray,
    )

    /**
     * Injects a decoded YUV_420_888 frame into the target Image, applying center-crop scaling,
     * rotation (0, 90, 180, 270), and horizontal mirroring in YUV space.
     */
    @JvmOverloads
    fun injectYuvFrame(
        image: Image,
        frame: MediaCodecYuvDecoder.YuvFrame,
        rotationAngle: Int = 0,
        isMirrored: Boolean = false,
    ) {
        val planes = image.planes
        if (planes.size < 3) return

        val dstW = image.width
        val dstH = image.height
        val srcW = frame.width
        val srcH = frame.height
        if (dstW <= 0 || dstH <= 0 || srcW <= 0 || srcH <= 0) return

        val normRot = ((rotationAngle % 360) + 360) % 360

        // Fast path when dimensions match and no transformation is active
        if (normRot == 0 && !isMirrored && srcW == dstW && srcH == dstH) {
            val chromaW = (dstW + 1) / 2
            val chromaH = (dstH + 1) / 2
            writePlane(planes[0], frame.yPlane, dstW, dstH)
            writePlane(planes[1], frame.uPlane, chromaW, chromaH)
            writePlane(planes[2], frame.vPlane, chromaW, chromaH)
            return
        }

        val (rotXTable, rotYTable) = getOrBuildLookupTables(srcW, srcH, dstW, dstH, normRot, isMirrored)

        val dstYPlane = ByteArray(dstW * dstH)
        val dstChromaW = (dstW + 1) / 2
        val dstChromaH = (dstH + 1) / 2
        val dstUPlane = ByteArray(dstChromaW * dstChromaH)
        val dstVPlane = ByteArray(dstChromaW * dstChromaH)

        val srcChromaW = (srcW + 1) / 2
        val srcChromaH = (srcH + 1) / 2
        val srcYPlane = frame.yPlane
        val srcUPlane = frame.uPlane
        val srcVPlane = frame.vPlane

        for (y in 0 until dstH) {
            val rotY = rotYTable[y]
            val dstRowOffset = y * dstW
            for (x in 0 until dstW) {
                val rotX = rotXTable[x]
                val srcX: Int
                val srcY: Int
                when (normRot) {
                    90 -> {
                        srcX = rotY
                        srcY = srcH - 1 - rotX
                    }
                    180 -> {
                        srcX = srcW - 1 - rotX
                        srcY = srcH - 1 - rotY
                    }
                    270 -> {
                        srcX = srcW - 1 - rotY
                        srcY = rotX
                    }
                    else -> {
                        srcX = rotX
                        srcY = rotY
                    }
                }
                val yIdx = (srcY * srcW + srcX).coerceIn(0, srcYPlane.size - 1)
                dstYPlane[dstRowOffset + x] = srcYPlane[yIdx]
            }
        }

        for (cy in 0 until dstChromaH) {
            val y = (cy * 2).coerceAtMost(dstH - 1)
            val rotY = rotYTable[y]
            val dstChromaRowOffset = cy * dstChromaW
            for (cx in 0 until dstChromaW) {
                val x = (cx * 2).coerceAtMost(dstW - 1)
                val rotX = rotXTable[x]
                val srcX: Int
                val srcY: Int
                when (normRot) {
                    90 -> {
                        srcX = rotY
                        srcY = srcH - 1 - rotX
                    }
                    180 -> {
                        srcX = srcW - 1 - rotX
                        srcY = srcH - 1 - rotY
                    }
                    270 -> {
                        srcX = srcW - 1 - rotY
                        srcY = rotX
                    }
                    else -> {
                        srcX = rotX
                        srcY = rotY
                    }
                }
                val srcCx = (srcX / 2).coerceIn(0, srcChromaW - 1)
                val srcCy = (srcY / 2).coerceIn(0, srcChromaH - 1)
                val uvIdx = (srcCy * srcChromaW + srcCx).coerceIn(0, minOf(srcUPlane.size, srcVPlane.size) - 1)
                dstUPlane[dstChromaRowOffset + cx] = srcUPlane[uvIdx]
                dstVPlane[dstChromaRowOffset + cx] = srcVPlane[uvIdx]
            }
        }

        writePlane(planes[0], dstYPlane, dstW, dstH)
        writePlane(planes[1], dstUPlane, dstChromaW, dstChromaH)
        writePlane(planes[2], dstVPlane, dstChromaW, dstChromaH)
    }

    /**
     * Injects an RGBA_8888 (format 0x1) frame into a single-plane ImageReader Image.
     * Supports direct conversion from live YuvFrame or fallback JPEG bytes.
     */
    fun injectRgbaFrame(
        image: Image,
        frame: MediaCodecYuvDecoder.YuvFrame?,
        jpeg: ByteArray?,
        rotationAngle: Int = 0,
        isMirrored: Boolean = false,
    ): Boolean {
        val planes = image.planes
        if (planes.isEmpty()) return false
        val plane = planes[0]
        val buffer = plane.buffer ?: return false
        if (buffer.isReadOnly) return false

        val dstW = image.width
        val dstH = image.height
        if (dstW <= 0 || dstH <= 0) return false

        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(4)
        val start = buffer.position()
        val limit = buffer.limit()

        if (frame != null && frame.width > 0 && frame.height > 0) {
            val srcW = frame.width
            val srcH = frame.height
            val normRot = ((rotationAngle % 360) + 360) % 360
            val (rotXTable, rotYTable) = getOrBuildLookupTables(srcW, srcH, dstW, dstH, normRot, isMirrored)

            val srcChromaW = (srcW + 1) / 2
            val srcChromaH = (srcH + 1) / 2
            val srcYPlane = frame.yPlane
            val srcUPlane = frame.uPlane
            val srcVPlane = frame.vPlane
            val maxUvIdx = minOf(srcUPlane.size, srcVPlane.size) - 1

            for (y in 0 until dstH) {
                val rotY = rotYTable[y]
                val dstRowBase = start + y * rowStride
                for (x in 0 until dstW) {
                    val dstPixelOffset = dstRowBase + x * pixelStride
                    if (dstPixelOffset + 3 >= limit) break

                    val rotX = rotXTable[x]
                    val srcX: Int
                    val srcY: Int
                    when (normRot) {
                        90 -> {
                            srcX = rotY
                            srcY = srcH - 1 - rotX
                        }
                        180 -> {
                            srcX = srcW - 1 - rotX
                            srcY = srcH - 1 - rotY
                        }
                        270 -> {
                            srcX = srcW - 1 - rotY
                            srcY = rotX
                        }
                        else -> {
                            srcX = rotX
                            srcY = rotY
                        }
                    }

                    val yIdx = (srcY * srcW + srcX).coerceIn(0, srcYPlane.size - 1)
                    val srcCx = (srcX / 2).coerceIn(0, srcChromaW - 1)
                    val srcCy = (srcY / 2).coerceIn(0, srcChromaH - 1)
                    val uvIdx = (srcCy * srcChromaW + srcCx).coerceIn(0, maxUvIdx)

                    val yVal = (srcYPlane[yIdx].toInt() and 0xFF) - 16
                    val uVal = (srcUPlane[uvIdx].toInt() and 0xFF) - 128
                    val vVal = (srcVPlane[uvIdx].toInt() and 0xFF) - 128

                    val c = if (yVal < 0) 0 else yVal * 298
                    val r = ((c + 409 * vVal + 128) shr 8).coerceIn(0, 255)
                    val g = ((c - 100 * uVal - 208 * vVal + 128) shr 8).coerceIn(0, 255)
                    val b = ((c + 516 * uVal + 128) shr 8).coerceIn(0, 255)

                    buffer.put(dstPixelOffset, r.toByte())
                    buffer.put(dstPixelOffset + 1, g.toByte())
                    buffer.put(dstPixelOffset + 2, b.toByte())
                    buffer.put(dstPixelOffset + 3, 0xFF.toByte())
                }
            }
            return true
        }

        if (jpeg != null && jpeg.isNotEmpty()) {
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return false
            val bitmap = if (decoded.width == dstW && decoded.height == dstH) {
                decoded
            } else {
                decoded.scale(dstW, dstH)
            }
            try {
                val pixels = IntArray(dstW * dstH)
                bitmap.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
                for (y in 0 until dstH) {
                    val dstRowBase = start + y * rowStride
                    val srcRowBase = y * dstW
                    for (x in 0 until dstW) {
                        val dstPixelOffset = dstRowBase + x * pixelStride
                        if (dstPixelOffset + 3 >= limit) break
                        val color = pixels[srcRowBase + x]
                        val a = (color ushr 24) and 0xFF
                        val r = (color ushr 16) and 0xFF
                        val g = (color ushr 8) and 0xFF
                        val b = color and 0xFF
                        buffer.put(dstPixelOffset, r.toByte())
                        buffer.put(dstPixelOffset + 1, g.toByte())
                        buffer.put(dstPixelOffset + 2, b.toByte())
                        buffer.put(dstPixelOffset + 3, a.toByte())
                    }
                }
                return true
            } finally {
                if (bitmap !== decoded) bitmap.recycle()
                decoded.recycle()
            }
        }

        return false
    }

    fun injectToImage(image: Image, jpeg: ByteArray) {
        val width = image.width
        val height = image.height

        val yuv = if ((cachedSource === jpeg) && (cachedYuv?.width == width) && (cachedYuv?.height == height)) {
            cachedYuv!!
        } else {
            process(jpeg, width, height).also {
                cachedSource = jpeg
                cachedYuv = it
            }
        }

        val planes = image.planes
        if (planes.size < 3) return

        writePlane(planes[0], yuv.y, width, height)
        writePlane(planes[1], yuv.u, (width + 1) / 2, (height + 1) / 2)
        writePlane(planes[2], yuv.v, (width + 1) / 2, (height + 1) / 2)
    }

    @Synchronized
    private fun getOrBuildLookupTables(
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        normRot: Int,
        isMirrored: Boolean,
    ): Pair<IntArray, IntArray> {
        val key = TransformKey(srcW, srcH, dstW, dstH, normRot, isMirrored)
        if (key == cachedTransformKey && cachedRotXTable != null && cachedRotYTable != null) {
            return Pair(cachedRotXTable!!, cachedRotYTable!!)
        }

        val rotW = if (normRot == 90 || normRot == 270) srcH else srcW
        val rotH = if (normRot == 90 || normRot == 270) srcW else srcH
        val scale = max(dstW.toFloat() / rotW.toFloat(), dstH.toFloat() / rotH.toFloat())

        val rotXTable = IntArray(dstW)
        val rotYTable = IntArray(dstH)

        for (x in 0 until dstW) {
            val effX = if (isMirrored) (dstW - 1 - x) else x
            val mappedX = ((effX + 0.5f - dstW * 0.5f) / scale + rotW * 0.5f).toInt()
            rotXTable[x] = mappedX.coerceIn(0, rotW - 1)
        }

        for (y in 0 until dstH) {
            val mappedY = ((y + 0.5f - dstH * 0.5f) / scale + rotH * 0.5f).toInt()
            rotYTable[y] = mappedY.coerceIn(0, rotH - 1)
        }

        cachedTransformKey = key
        cachedRotXTable = rotXTable
        cachedRotYTable = rotYTable
        return Pair(rotXTable, rotYTable)
    }

    private fun process(jpeg: ByteArray, width: Int, height: Int): YuvData {
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: throw IllegalStateException("Failed to decode frame")

        val bitmap = if (decoded.width == width && decoded.height == height) decoded
        else decoded.scale(width, height)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val y = ByteArray(width * height)
        val u = ByteArray(((width + 1) / 2) * ((height + 1) / 2))
        val v = ByteArray(((width + 1) / 2) * ((height + 1) / 2))

        for (row in 0 until height) {
            for (col in 0 until width) {
                val color = pixels[row * width + col]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                y[row * width + col] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
            }
        }

        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                val x = (col * 2).coerceAtMost(width - 1)
                val yPos = (row * 2).coerceAtMost(height - 1)
                val color = pixels[yPos * width + x]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                val idx = row * chromaW + col
                u[idx] = (((-43 * r - 85 * g + 128 * b) shr 8) + 128).toByte()
                v[idx] = (((128 * r - 107 * g - 21 * b) shr 8) + 128).toByte()
            }
        }

        if (bitmap != decoded) bitmap.recycle()
        decoded.recycle()

        return YuvData(width, height, y, u, v)
    }

    private fun writePlane(plane: Image.Plane, values: ByteArray, width: Int, height: Int) {
        val buffer = plane.buffer ?: return
        if (buffer.isReadOnly) return
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val start = buffer.position()
        val limit = buffer.limit()

        for (row in 0 until height) {
            val dstRow = start + row * rowStride
            val srcRow = row * width
            for (col in 0 until width) {
                val dstIdx = dstRow + col * pixelStride
                val srcIdx = srcRow + col
                if (dstIdx in start until limit && srcIdx < values.size) {
                    buffer.put(dstIdx, values[srcIdx])
                }
            }
        }
    }
}