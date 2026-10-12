package umbra.cam.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppsRepositoryTest {
    private val apps = listOf(
        InstalledAppInfo("com.example.camera", "Camera", null, isSystemApp = false),
        InstalledAppInfo("com.android.settings", "Settings", null, isSystemApp = true),
    )

    @Test
    fun hidesSystemAppsByDefault() {
        assertEquals(listOf("com.example.camera"), filterInstalledApps(apps, "", showSystemApps = false).map { it.packageName })
    }

    @Test
    fun includesSystemAppsWhenRequested() {
        assertEquals(2, filterInstalledApps(apps, "", showSystemApps = true).size)
    }

    @Test
    fun searchesByPackageNameCaseInsensitively() {
        assertEquals(listOf("com.android.settings"), filterInstalledApps(apps, "ANDROID.SET", showSystemApps = true).map { it.packageName })
    }
}
