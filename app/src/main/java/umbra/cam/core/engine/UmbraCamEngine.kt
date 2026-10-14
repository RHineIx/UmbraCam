package umbra.cam.core.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import umbra.cam.core.settings.SettingsManager
import umbra.cam.core.surface.SurfaceManager
import umbra.cam.core.surface.SurfaceProvider

@UnstableApi
class UmbraCamEngine(
    private val contextProvider: () -> Context?,
    private val settingsProvider: () -> SettingsManager,
    private val surfaceManager: SurfaceManager,
    private val mediaEngine: MediaEngine,
    private val surfaceProvider: SurfaceProvider,
    private val logAction: (String) -> Unit,
) {
    private val uiHandler = Handler(Looper.getMainLooper())

    private var lastST: SurfaceTexture? = null
    private var lastCamera1Surface: Surface? = null
    private var lastModernSurface: Surface? = null
    private var lastInjectedGen = -1
    private var lastInjectedSurfaceId = -1L

    private fun log(
        tag: String,
        msg: String,
    ) = logAction("[$tag] $msg")

    private fun logPipe(msg: String) = log("PIPELINE", msg)

    fun isPlaying() = mediaEngine.isPlaying

    fun getCurrentPosition() = mediaEngine.currentPosition

    fun stop() {
        uiHandler.removeCallbacksAndMessages(null)
        mediaEngine.stop()
        try {
            lastCamera1Surface?.release()
        } catch (_: Throwable) {
        }
        lastCamera1Surface = null
        lastST = null
        lastModernSurface = null
        lastInjectedSurfaceId = -1L
        lastInjectedGen = -1
    }

    fun handleCamera1Preview(st: SurfaceTexture) {
        if ((st == lastST) && mediaEngine.isPlaying && lastCamera1Surface?.isValid == true) {
            lastInjectedGen = surfaceManager.sessionGeneration
            return
        }

        if (st != lastST || lastCamera1Surface?.isValid != true) {
            try {
                lastCamera1Surface?.release()
            } catch (_: Throwable) {
            }
            lastCamera1Surface = Surface(st)
            lastST = st
        }

        val surface = lastCamera1Surface ?: return
        if (!surface.isValid) return

        surfaceManager.registerPreviewSurface(surface, surfaceProvider.isDummySurface(surface))
        lastInjectedGen = surfaceManager.sessionGeneration
        logPipe("Legacy Hook: Routing SurfaceTexture to preview engine")
        handleModernPreview(surface)
    }

    fun handleModernPreview(surface: Surface) {
        val currentGen = surfaceManager.sessionGeneration
        if (surface == lastModernSurface && mediaEngine.isPlaying) {
            lastInjectedGen = currentGen
            return
        }
        lastModernSurface = surface
        uiHandler.post { processInjection(surface) }
    }

    private fun processInjection(surface: Surface) {
        val context = contextProvider() ?: return
        val path = settingsProvider().mediaPath ?: return
        injectToSurface(surface, context, path)
    }

    fun injectToSurface(
        surface: Surface,
        context: Context,
        path: String,
    ) {
        synchronized(this) {
            if (!surface.isValid) return

            val id =
                umbra.cam.utils.SystemUtils
                    .getSurfaceId(surface)
            val currentGen = surfaceManager.sessionGeneration
            val settings = settingsProvider()

            if (id == lastInjectedSurfaceId && mediaEngine.isPlaying) {
                lastInjectedGen = currentGen
                return
            }

            logPipe("Injection: ID=$id Gen=$currentGen")
            mediaEngine.stop()

            lastInjectedGen = currentGen
            lastInjectedSurfaceId = id
            mediaEngine.play(context, path, surface, "Engine", settings.isMirrored, settings.rotationAngle)
        }
    }

    fun isDummySurface(surface: Surface?): Boolean = surfaceProvider.isDummySurface(surface)

    fun getDummySurfaceFor(targetSurface: Surface?): Surface = surfaceProvider.getDummySurfaceFor(targetSurface)

    fun getDummySurface(): Surface = surfaceProvider.getDummySurface()

    fun getDummySurfaceTexture(): SurfaceTexture = surfaceProvider.getDummySurfaceTexture()
}
