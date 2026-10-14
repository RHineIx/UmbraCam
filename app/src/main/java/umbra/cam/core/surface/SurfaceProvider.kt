package umbra.cam.core.surface

import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import umbra.cam.data.Constants.DUMMY_SURFACE_TEXTURE_ID
import umbra.cam.utils.SystemUtils
import java.util.Collections

/**
 * Provides and manages dummy surfaces and textures used for diverting original camera output.
 * Creates per-preview dummy surfaces matching the original surface's configured dimensions
 * to prevent BufferQueue producer collisions across concurrent or switching Camera2 sessions.
 */
class SurfaceProvider(
    private val logAction: (String) -> Unit,
) {
    private var fallbackST: SurfaceTexture? = null
    private var fallbackSurface: Surface? = null
    private var nextTexId = DUMMY_SURFACE_TEXTURE_ID

    private data class DummyEntry(
        val texture: SurfaceTexture,
        val surface: Surface,
        val dummyId: Long,
    )

    private val dummyByTargetId = LinkedHashMap<Long, DummyEntry>()
    private val dummySurfaceIds = Collections.synchronizedSet(mutableSetOf<Long>())

    private fun log(msg: String) = logAction("[SURFACE-PROVIDER] $msg")

    private fun querySurfaceSize(surface: Surface?): Size? {
        if (surface == null || !surface.isValid) return null
        return try {
            val utilsClass = Class.forName("android.hardware.camera2.utils.SurfaceUtils")
            val getSizeMethod = utilsClass.getDeclaredMethod("getSurfaceSize", Surface::class.java)
            getSizeMethod.isAccessible = true
            getSizeMethod.invoke(null, surface) as? Size
        } catch (_: Throwable) {
            null
        }
    }

    private fun createDummyEntry(targetSurface: Surface?): DummyEntry {
        val texId = nextTexId++
        val size = querySurfaceSize(targetSurface)
        val st =
            SurfaceTexture(texId).apply {
                if (size != null && size.width > 0 && size.height > 0) {
                    setDefaultBufferSize(size.width, size.height)
                }
                setOnFrameAvailableListener {
                    try {
                        updateTexImage()
                    } catch (_: Throwable) {
                    }
                }
            }
        try {
            st.detachFromGLContext()
        } catch (_: Throwable) {
        }

        val surface = Surface(st)
        val dummyId = SystemUtils.getSurfaceId(surface)
        dummySurfaceIds.add(dummyId)
        log(
            "Created dummy surface (ID: $dummyId, Size: ${size?.width ?: -1}x${size?.height ?: -1})",
        )
        return DummyEntry(st, surface, dummyId)
    }

    @Synchronized
    fun isDummySurface(surface: Surface?): Boolean {
        if (surface == null) return false
        return dummySurfaceIds.contains(SystemUtils.getSurfaceId(surface))
    }

    @Synchronized
    fun getDummySurfaceFor(targetSurface: Surface?): Surface {
        if (targetSurface == null || !targetSurface.isValid) {
            return getDummySurface()
        }
        val targetId = SystemUtils.getSurfaceId(targetSurface)
        if (dummySurfaceIds.contains(targetId)) {
            return targetSurface
        }

        val existing = dummyByTargetId[targetId]
        if (existing != null && existing.surface.isValid) {
            return existing.surface
        }

        // Evict oldest unused entries if cache grows beyond 6 sessions
        while (dummyByTargetId.size >= 6) {
            val oldestKey = dummyByTargetId.keys.firstOrNull() ?: break
            val removed = dummyByTargetId.remove(oldestKey)
            if (removed != null) {
                dummySurfaceIds.remove(removed.dummyId)
                try {
                    removed.surface.release()
                } catch (_: Throwable) {
                }
                try {
                    removed.texture.release()
                } catch (_: Throwable) {
                }
            }
        }

        val created = createDummyEntry(targetSurface)
        dummyByTargetId[targetId] = created
        return created.surface
    }

    @Synchronized
    fun getDummySurface(): Surface {
        if (fallbackSurface == null || !fallbackSurface!!.isValid || fallbackST == null) {
            val entry = createDummyEntry(null)
            fallbackST = entry.texture
            fallbackSurface = entry.surface
        }
        return fallbackSurface!!
    }

    @Synchronized
    fun getDummySurfaceTexture(): SurfaceTexture {
        getDummySurface()
        return fallbackST!!
    }

    @Synchronized
    fun release() {
        for (entry in dummyByTargetId.values) {
            try {
                entry.surface.release()
            } catch (_: Throwable) {
            }
            try {
                entry.texture.release()
            } catch (_: Throwable) {
            }
        }
        dummyByTargetId.clear()
        dummySurfaceIds.clear()

        try {
            fallbackSurface?.release()
        } catch (_: Throwable) {
        }
        try {
            fallbackST?.release()
        } catch (_: Throwable) {
        }
        fallbackSurface = null
        fallbackST = null
    }
}
