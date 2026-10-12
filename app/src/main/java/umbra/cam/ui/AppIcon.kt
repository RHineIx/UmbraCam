package umbra.cam.ui

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ICON_SIZE_PX = 96
private const val ICON_CACHE_KIB = 2 * 1024

internal object AppIconCache {
    private val cache = object : LruCache<String, ImageBitmap>(ICON_CACHE_KIB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.width.toLong() * value.height * 4L / 1024L).toInt().coerceAtLeast(1)
    }

    fun get(packageName: String): ImageBitmap? = cache.get(packageName)

    fun put(packageName: String, icon: ImageBitmap) {
        cache.put(packageName, icon)
    }

    fun clear() = cache.evictAll()
}

@Composable
internal fun AppIcon(
    packageName: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val iconState = produceState<ImageBitmap?>(initialValue = AppIconCache.get(packageName), packageName) {
        value = AppIconCache.get(packageName)
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                AppIconCache.get(packageName) ?: runCatching {
                    appContext.packageManager.getApplicationIcon(packageName)
                        .toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX)
                        .asImageBitmap()
                        .also { AppIconCache.put(packageName, it) }
                }.getOrNull()
            }
        }
    }

    if (iconState.value != null) {
        Image(
            bitmap = iconState.value!!,
            contentDescription = contentDescription,
            modifier = modifier.clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Android, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
        }
    }
}
