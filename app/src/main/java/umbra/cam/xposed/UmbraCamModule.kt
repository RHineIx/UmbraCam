package umbra.cam.xposed

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import umbra.cam.core.audio.MediaCodecAudioDecoder
import umbra.cam.core.capture.CaptureManager
import umbra.cam.core.capture.MediaCodecYuvDecoder
import umbra.cam.core.capture.YuvFrameProcessor
import umbra.cam.core.engine.MediaEngine
import umbra.cam.core.engine.UmbraCamEngine
import umbra.cam.core.settings.SettingsManager
import umbra.cam.core.surface.SurfaceManager
import umbra.cam.core.surface.SurfaceProvider
import umbra.cam.core.telemetry.PipelineTracker
import umbra.cam.data.Constants
import umbra.cam.utils.Logger
import umbra.cam.utils.SystemUtils
import umbra.cam.utils.UIUtils

@UnstableApi
class UmbraCamModule : XposedModule() {
    private var isInitialized = false
    private var mContext: Context? = null
    private var hooksInstalled = false
    private val ignoreHooks = ThreadLocal.withInitial { false }
    private var yuvDecoder: MediaCodecYuvDecoder? = null
    private var audioDecoder: MediaCodecAudioDecoder? = null

    private val injectors = UmbraCamInjectors(this)
    private val settings = SettingsManager()
    private val surfaceManager = SurfaceManager { printLog(it) }
    private val surfaceProvider = SurfaceProvider { printLog(it) }
    private val mediaEngine = MediaEngine { printLog(it) }
    private val yuvProcessor = YuvFrameProcessor()

    private val captureManager =
        CaptureManager(
            contextProvider = { mContext },
            refreshSettingsAction = { settings.refreshSettings(it) },
        ) { printLog(it) }

    private val engine =
        UmbraCamEngine(
            contextProvider = { mContext },
            settingsProvider = { settings },
            surfaceManager = surfaceManager,
            mediaEngine = mediaEngine,
            surfaceProvider = surfaceProvider,
        ) { printLog(it) }

    fun isIgnoringHooks(): Boolean = ignoreHooks.get() ?: false

    fun setIgnoringHooks(ignore: Boolean) {
        ignoreHooks.set(ignore)
    }

    val context: Context? get() = mContext
    val mediaPath: String? get() = settings.mediaPath
    var previewSwapped: Boolean
        get() = surfaceManager.previewSwapped
        set(value) {
            surfaceManager.previewSwapped = value
        }

    fun printLog(
        msg: String,
        tr: Throwable? = null,
    ) {
        if (tr != null || (msg.contains("Error") || msg.contains("failed") || msg.contains("FATAL"))) {
            Logger.e(this, msg, tr)
        } else {
            Logger.i(this, msg)
        }
    }

    fun logInit(msg: String) = Logger.i(this, "[INIT] $msg")

    fun logHook(msg: String) = Logger.d(this, "[HOOK] $msg")

    fun showToast(message: String) = UIUtils.showToast(mContext, message) { printLog(it) }

    fun isCapturingState() = captureManager.isCapturing

    fun triggerCaptureState() {
        captureManager.triggerCaptureState { engine.getCurrentPosition().toInt() }
    }

    fun getCurrentPosition(): Long = engine.getCurrentPosition()

    fun registerPreviewSurface(s: Surface) = surfaceManager.registerPreviewSurface(s)

    fun registerImageReaderSurface(
        s: Surface,
        f: Int,
        w: Int,
        h: Int,
    ) = surfaceManager.registerImageReaderSurface(s, f, w, h)

    fun isPreviewSurface(s: Surface?) = surfaceManager.isPreviewSurface(s)

    fun logSessionOutput(s: Surface) = surfaceManager.logSessionOutput(s)

    fun incrementSessionGeneration() = surfaceManager.incrementSessionGeneration()

    fun clearPreviewSurfaces() = surfaceManager.clearPreviewSurfaces(engine.isPlaying())

    fun recordPipelineNode(node: String) {
        PipelineTracker.recordNode(mContext, node)
    }

    @JvmOverloads
    fun reportPipelineCapture(
        width: Int = 0,
        height: Int = 0,
        thumbnail: ByteArray? = null,
    ) {
        PipelineTracker.reportCapture(mContext, width, height, thumbnail)
    }

    fun injectYuvFrame(
        image: android.media.Image,
        width: Int,
        height: Int,
    ) {
        val path = settings.mediaPath
        val ctx = mContext
        if (path != null && ctx != null && path.lowercase().endsWith(".mp4")) {
            if (yuvDecoder == null) {
                yuvDecoder = MediaCodecYuvDecoder(ctx, path) { printLog(it) }.apply { start() }
            }
            val frame = yuvDecoder?.latestFrame
            if (frame != null) {
                yuvProcessor.injectYuvFrame(image, frame)
                return
            }
        }

        val jpeg = handleStreamFrame(width, height) ?: return
        yuvProcessor.injectToImage(image, jpeg)
    }

    fun stopEngine() {
        yuvDecoder?.stop()
        yuvDecoder = null
        audioDecoder?.stop()
        audioDecoder = null
        engine.stop()
        captureManager.reset()
        injectors.imageReaderHook.reset()
    }

    fun handleCamera1Preview(st: SurfaceTexture) = engine.handleCamera1Preview(st)

    fun handleModernPreview(s: Surface) = engine.handleModernPreview(s)

    fun getDummySurface() = engine.getDummySurface()

    fun injectAudioBytes(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        if (audioDecoder == null) {
            audioDecoder = MediaCodecAudioDecoder(ctx, path) { printLog(it) }.apply { start() }
        }
        audioDecoder?.readBytes(buffer, offset, length)
    }

    fun injectAudioShorts(
        buffer: ShortArray,
        offset: Int,
        length: Int,
    ) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        if (audioDecoder == null) {
            audioDecoder = MediaCodecAudioDecoder(ctx, path) { printLog(it) }.apply { start() }
        }
        audioDecoder?.readShorts(buffer, offset, length)
    }

    fun injectAudioByteBuffer(
        buffer: java.nio.ByteBuffer,
        length: Int,
    ) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        if (audioDecoder == null) {
            audioDecoder = MediaCodecAudioDecoder(ctx, path) { printLog(it) }.apply { start() }
        }
        audioDecoder?.readByteBuffer(buffer, length)
    }

    @JvmOverloads
    fun handleCapture(
        w: Int = 0,
        h: Int = 0,
        maxSize: Int = Int.MAX_VALUE,
    ): ByteArray? {
        val irW = injectors.imageReaderHook.activeCaptureWidth
        val irH = injectors.imageReaderHook.activeCaptureHeight
        val targetW =
            if (w > 0) {
                w
            } else if (irW > 0) {
                irW
            } else {
                Constants.DEFAULT_CAPTURE_WIDTH
            }
        val targetH =
            if (h > 0) {
                h
            } else if (irH > 0) {
                irH
            } else {
                Constants.DEFAULT_CAPTURE_HEIGHT
            }
        return captureManager.handleCapture(
            settings.mediaPath,
            targetW,
            targetH,
            settings.rotationAngle,
            settings.isMirrored,
            maxSize,
            { isIgnoringHooks() },
            { setIgnoringHooks(it) },
            yuvDecoder?.latestFrame,
        )
    }

    fun handleStreamFrame(
        w: Int,
        h: Int,
    ) = captureManager.handleStreamFrame(
        settings.mediaPath,
        w,
        h,
        settings.rotationAngle,
        settings.isMirrored,
        { isIgnoringHooks() },
        { setIgnoringHooks(it) },
    )

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val processName = SystemUtils.getProcessNameStrict()

        // API 102 isolation prevents self-hooking; execution halts here if target is the module app itself
        if (param.packageName == "umbra.cam") {
            return
        }

        if (!processName.contains(param.packageName)) return
        if (hooksInstalled) return
        hooksInstalled = true

        incrementSessionGeneration()
        clearPreviewSurfaces()
        PipelineTracker.reset()
        logInit(">>> ACTIVE IN: $processName (API ${Build.VERSION.SDK_INT}) <<<")
        hookContextInit()
        injectors.install(param)
    }

    private fun hookContextInit() {
        val currentApp = SystemUtils.getCurrentApplication()
        if (currentApp != null && !isInitialized) {
            mContext = currentApp
            logInit("Context immediately resolved from ActivityThread: ${mContext?.packageName}")
            mContext?.let { settings.refreshSettings(it) }
            isInitialized = true
        }

        try {
            val attachMethod =
                Class
                    .forName("android.content.ContextWrapper")
                    .getDeclaredMethod("attachBaseContext", Context::class.java)

            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                if (!isInitialized) {
                    mContext = chain.thisObject as? Context
                    logInit("Context Initialized: ${mContext?.packageName}")
                    mContext?.let { settings.refreshSettings(it) }
                    isInitialized = true
                }
                result
            }
        } catch (e: Exception) {
            logInit("Context hook failure: ${e.message}")
        }
    }
}
