package umbra.cam.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppsRepositoryTest {
    private val apps = listOf(
        InstalledAppInfo("com.example.camera", "Camera", isSystemApp = false),
        InstalledAppInfo("com.android.settings", "Settings", isSystemApp = true),
        InstalledAppInfo("com.vendor.updated", "Updated System App", isSystemApp = true),
    )

    @Test
    fun hidesSystemAppsByDefault() {
        assertEquals(
            listOf("com.example.camera"),
            filterInstalledApps(apps, "", showSystemApps = false).map { it.packageName },
        )
    }

    @Test
    fun includesSystemAndUpdatedSystemAppsWhenRequested() {
        assertEquals(3, filterInstalledApps(apps, "", showSystemApps = true).size)
    }

    @Test
    fun searchesByPackageNameCaseInsensitively() {
        assertEquals(
            listOf("com.android.settings"),
            filterInstalledApps(apps, "ANDROID.SET", showSystemApps = true).map { it.packageName },
        )
    }

    @Test
    fun filtersSystemAppsEvenWhenQueryMatches() {
        assertEquals(emptyList<InstalledAppInfo>(), filterInstalledApps(apps, "settings", showSystemApps = false))
    }

    @Test
    fun installedAppModelContainsNoDecodedIconReferences() {
        assertFalse(InstalledAppInfo::class.java.declaredFields.any { it.type.name.contains("ImageBitmap") })
        assertTrue(apps.all { it.appName.isNotBlank() })
    }
}
