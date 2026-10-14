package umbra.cam.core.surface

import android.view.Surface
import umbra.cam.data.Constants.MIN_SESSION_DEBOUNCE_MS
import umbra.cam.utils.SystemUtils
import java.util.Collections

/**
 * Manages Surface identification and classification (Preview vs. ImageReader).
 * Uses native object IDs to track surfaces across different API hooks.
 */
class SurfaceManager(
    private val logAction: (String) -> Unit,
) {
    private val previewIds = Collections.synchronizedSet(mutableSetOf<Long>())
    private val nonPreviewIds = Collections.synchronizedSet(mutableSetOf<Long>())
    private val hijackedPreviewIds = Collections.synchronizedSet(mutableSetOf<Long>())

    @Volatile
    var previewSwapped = false

    @Volatile
    var sessionGeneration = 0
        private set

    private var lastIncrementTime = 0L
    private var lastClearedGen = -1

    private val Surface.id get() = SystemUtils.getSurfaceId(this)

    private fun log(msg: String) = logAction("[SURFACE] $msg")

    private fun isExplicitPreviewName(surface: Surface): Boolean {
        val sStr = surface.toString()
        return sStr.contains("SurfaceTexture") ||
            sStr.contains("SurfaceView") ||
            sStr.contains("TextureView")
    }

    @Synchronized
    fun incrementSessionGeneration(): Boolean {
        val now = System.currentTimeMillis()
        if ((now - lastIncrementTime) < MIN_SESSION_DEBOUNCE_MS) return false

        sessionGeneration++
        lastIncrementTime = now
        log("[*] NEW CAMERA SESSION (Gen $sessionGeneration)")
        return true
    }

    @Synchronized
    fun registerPreviewSurface(
        surface: Surface,
        isDummy: Boolean = false,
    ) {
        if (!surface.isValid || isDummy) return
        val id = surface.id

        if (isExplicitPreviewName(surface)) {
            nonPreviewIds.remove(id)
        }

        if (!nonPreviewIds.contains(id) && previewIds.add(id)) {
            log("[+] Registered PREVIEW | ID: $id")
        }
    }

    @Synchronized
    fun markPreviewHijacked(surface: Surface) {
        val id = surface.id
        nonPreviewIds.remove(id)
        previewIds.add(id)
        hijackedPreviewIds.add(id)
    }

    @Synchronized
    fun isHijackedPreview(surface: Surface?): Boolean {
        val id = surface?.id ?: return false
        return hijackedPreviewIds.contains(id)
    }

    @Synchronized
    fun registerImageReaderSurface(
        surface: Surface,
        format: Int,
        width: Int,
        height: Int,
    ) {
        val id = surface.id
        if (surface.isValid) {
            nonPreviewIds.add(id)
            previewIds.remove(id)
            hijackedPreviewIds.remove(id)
            log("[+] ImageReader Registered | ID: $id | $width x $height | 0x${format.toString(16)}")
        }
    }

    @Synchronized
    fun unregisterImageReaderSurface(surfaceId: Long) {
        if (surfaceId != -1L && nonPreviewIds.remove(surfaceId)) {
            log("[-] ImageReader Unregistered | ID: $surfaceId")
        }
    }

    @Synchronized
    fun isPreviewSurface(
        surface: Surface?,
        isDummy: Boolean = false,
    ): Boolean {
        if (surface == null || !surface.isValid || isDummy) return false
        val id = surface.id

        if (isExplicitPreviewName(surface)) {
            nonPreviewIds.remove(id)
            registerPreviewSurface(surface, false)
            return true
        }

        if (nonPreviewIds.contains(id)) return false
        return previewIds.contains(id)
    }

    fun logSessionOutput(surface: Surface) {
        log("[*] SESSION OUTPUT: id=${surface.id} preview=${previewIds.contains(surface.id)}")
    }

    @Synchronized
    fun onSessionConfigured() {
        previewSwapped = false
    }

    @Synchronized
    fun clearPreviewSurfaces(isEnginePlaying: Boolean) {
        if (lastClearedGen == sessionGeneration) return
        lastClearedGen = sessionGeneration
        previewSwapped = false

        log("[*] Clearing Surface IDs (EnginePlaying=$isEnginePlaying)")
        if (!isEnginePlaying) {
            previewIds.clear()
            hijackedPreviewIds.clear()
        }
    }

    @Synchronized
    fun resetAll() {
        previewSwapped = false
        previewIds.clear()
        hijackedPreviewIds.clear()
    }
}
