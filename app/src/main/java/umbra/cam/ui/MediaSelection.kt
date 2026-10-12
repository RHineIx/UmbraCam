package umbra.cam.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import umbra.cam.R
import umbra.cam.utils.MediaConverter
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.coroutines.resume

internal sealed interface MediaImportResult {
    data class Success(val path: String) : MediaImportResult
    data class Failure(val messageResource: Int) : MediaImportResult
}

internal suspend fun loadPreviewBitmap(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
    if (path.isEmpty() || !File(path).exists()) return@withContext null
    runCatching {
        if (path.endsWith(".mp4", ignoreCase = true)) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                retriever.getFrameAtTime(1_000_000)?.asImageBitmap()
            } finally {
                retriever.release()
            }
        } else {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        }
    }.getOrNull()
}

internal suspend fun processMediaSelection(context: Context, uri: Uri): MediaImportResult =
    withContext(Dispatchers.IO) {
        try {
            val mimeType = context.contentResolver.getType(uri)
                ?: run {
                    val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                    android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(extension?.lowercase(Locale.ROOT))
                        ?: "application/octet-stream"
                }
            val isImage = mimeType.startsWith("image/")
            val isVideo = mimeType.startsWith("video/")
            if (!isImage && !isVideo) {
                return@withContext MediaImportResult.Failure(R.string.toast_unsupported_media)
            }

            context.filesDir.listFiles { _, name -> name.startsWith("virtual.") }
                ?.forEach { it.delete() }
            val extension = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType)
                ?.takeIf(String::isNotBlank)
                ?: "bin"
            val internalFile = File(context.filesDir, "virtual.$extension")
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext MediaImportResult.Failure(R.string.toast_media_processing_failed)
            input.use { source ->
                FileOutputStream(internalFile).use { output -> source.copyTo(output) }
            }

            if (isVideo) return@withContext MediaImportResult.Success(internalFile.absolutePath)

            val outputVideo = File(context.filesDir, "virtual.mp4")
            val success = suspendCancellableCoroutine { continuation ->
                Handler(Looper.getMainLooper()).post {
                    try {
                        MediaConverter.convertImageToMp4(context, Uri.fromFile(internalFile), outputVideo) { result ->
                            if (continuation.isActive) continuation.resume(result)
                        }
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }
            internalFile.delete()
            if (success) MediaImportResult.Success(outputVideo.absolutePath)
            else MediaImportResult.Failure(R.string.toast_image_conversion_failed)
        } catch (_: Exception) {
            MediaImportResult.Failure(R.string.toast_media_processing_failed)
        }
    }
