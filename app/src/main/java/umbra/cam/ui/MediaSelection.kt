package umbra.cam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import umbra.cam.R
import umbra.cam.utils.MediaConverter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

private const val MAX_PREVIEW_DIMENSION = 384
private const val PREVIEW_CORNER_RADIUS_PX = 24f

internal sealed interface MediaImportResult {
    data class Success(
        val path: String,
    ) : MediaImportResult

    data class Failure(
        val messageResource: Int,
    ) : MediaImportResult
}

/**
 * Center-crops and pre-rounds the preview bitmap into a fixed-size square software bitmap
 * so the UI RenderThread does not need to perform ContentScale.Crop or outline clipping per frame.
 */
private fun prepareSquareRoundedPreview(raw: Bitmap): ImageBitmap {
    val sw =
        if (raw.config == Bitmap.Config.HARDWARE) {
            raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
        } else {
            raw
        }

    val targetSize = MAX_PREVIEW_DIMENSION
    val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
    val scale =
        max(
            targetSize.toFloat() / sw.width.coerceAtLeast(1),
            targetSize.toFloat() / sw.height.coerceAtLeast(1),
        )
    val dx = (targetSize - sw.width * scale) * 0.5f
    val dy = (targetSize - sw.height * scale) * 0.5f

    val shaderMatrix =
        Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
    val shader =
        BitmapShader(sw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(shaderMatrix)
        }
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
        }

    val canvas = Canvas(output)
    val rect = RectF(0f, 0f, targetSize.toFloat(), targetSize.toFloat())
    canvas.drawRoundRect(rect, PREVIEW_CORNER_RADIUS_PX, PREVIEW_CORNER_RADIUS_PX, paint)

    if (sw !== output) {
        sw.recycle()
    }
    return output.asImageBitmap()
}

internal suspend fun loadPreviewBitmap(path: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        if (path.isEmpty() || !File(path).exists()) return@withContext null
        try {
            val rawBitmap: Bitmap? =
                if (path.endsWith(".mp4", ignoreCase = true)) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(path)
                        val width =
                            retriever
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                ?.toIntOrNull()
                                ?.coerceAtLeast(1) ?: MAX_PREVIEW_DIMENSION
                        val height =
                            retriever
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                                ?.toIntOrNull()
                                ?.coerceAtLeast(1) ?: MAX_PREVIEW_DIMENSION
                        val scale =
                            min(
                                1f,
                                min(MAX_PREVIEW_DIMENSION.toFloat() / width, MAX_PREVIEW_DIMENSION.toFloat() / height),
                            )
                        retriever.getScaledFrameAtTime(
                            1_000_000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            (width * scale).toInt().coerceAtLeast(1),
                            (height * scale).toInt().coerceAtLeast(1),
                        )
                    } finally {
                        retriever.release()
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    var sampleSize = 1
                    while (bounds.outWidth / sampleSize > MAX_PREVIEW_DIMENSION ||
                        bounds.outHeight / sampleSize > MAX_PREVIEW_DIMENSION
                    ) {
                        sampleSize *= 2
                    }
                    BitmapFactory.decodeFile(
                        path,
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSize.coerceAtLeast(1)
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        },
                    )
                }
            rawBitmap?.let { prepareSquareRoundedPreview(it) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

@OptIn(UnstableApi::class)
internal suspend fun processMediaSelection(
    context: Context,
    uri: Uri,
): MediaImportResult =
    withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        var temporaryImage: File? = null
        try {
            val mimeType =
                appContext.contentResolver.getType(uri)
                    ?: run {
                        val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                        android.webkit.MimeTypeMap
                            .getSingleton()
                            .getMimeTypeFromExtension(extension?.lowercase(Locale.ROOT))
                            ?: "application/octet-stream"
                    }
            val isImage = mimeType.startsWith("image/")
            val isVideo = mimeType.startsWith("video/")
            if (!isImage && !isVideo) {
                return@withContext MediaImportResult.Failure(R.string.toast_unsupported_media)
            }

            appContext.filesDir
                .listFiles { _, name -> name.startsWith("virtual.") }
                ?.forEach { it.delete() }
            val extension =
                android.webkit.MimeTypeMap
                    .getSingleton()
                    .getExtensionFromMimeType(mimeType)
                    ?.takeIf(String::isNotBlank)
                    ?: "bin"
            val internalFile = File(appContext.filesDir, "virtual.$extension")
            temporaryImage = if (isImage) internalFile else null
            val input =
                appContext.contentResolver.openInputStream(uri)
                    ?: return@withContext MediaImportResult.Failure(R.string.toast_media_processing_failed)
            input.use { source ->
                FileOutputStream(internalFile).use { output -> source.copyToCancellable(output) }
            }

            if (isVideo) return@withContext MediaImportResult.Success(internalFile.absolutePath)

            val outputVideo = File(appContext.filesDir, "virtual.mp4")
            val transformer = AtomicReference<Transformer?>(null)
            val handler = Handler(Looper.getMainLooper())
            val success =
                suspendCancellableCoroutine { continuation ->
                    val conversionRunnable =
                        Runnable {
                            if (!continuation.isActive) return@Runnable
                            transformer.set(
                                MediaConverter.convertImageToMp4(
                                    appContext,
                                    Uri.fromFile(internalFile),
                                    outputVideo,
                                    onTransformerCreated = { created ->
                                        transformer.set(created)
                                        continuation.isActive
                                    },
                                    onComplete = { result ->
                                        if (continuation.isActive) continuation.resume(result)
                                    },
                                ),
                            )
                        }
                    handler.post(conversionRunnable)
                    continuation.invokeOnCancellation {
                        handler.removeCallbacks(conversionRunnable)
                        transformer.getAndSet(null)?.cancel()
                        internalFile.delete()
                        outputVideo.delete()
                    }
                }
            internalFile.delete()
            temporaryImage = null
            if (success) {
                MediaImportResult.Success(outputVideo.absolutePath)
            } else {
                MediaImportResult.Failure(R.string.toast_image_conversion_failed)
            }
        } catch (cancellation: CancellationException) {
            temporaryImage?.delete()
            throw cancellation
        } catch (_: Exception) {
            temporaryImage?.delete()
            MediaImportResult.Failure(R.string.toast_media_processing_failed)
        }
    }

private suspend fun InputStream.copyToCancellable(output: OutputStream) {
    val coroutineContext = coroutineContext
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        coroutineContext.ensureActive()
        val bytesRead = read(buffer)
        if (bytesRead < 0) break
        output.write(buffer, 0, bytesRead)
    }
}
