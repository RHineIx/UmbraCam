package umbra.cam.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val icon: androidx.compose.ui.graphics.ImageBitmap?,
    val isSystemApp: Boolean,
)

internal object InstalledAppsRepository {
    suspend fun loadInstalledApps(context: Context): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        installedApplications(packageManager)
            .asSequence()
            .filterNot { it.packageName == context.packageName }
            .map { it.toInstalledAppInfo(packageManager) }
            .sortedBy { it.appName.lowercase(Locale.getDefault()) }
            .toList()
    }

    suspend fun loadScopedApps(context: Context, packages: List<String>): List<InstalledAppInfo> =
        withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            packages.map { packageName ->
                runCatching {
                    packageManager.getApplicationInfo(packageName, 0).toInstalledAppInfo(packageManager)
                }.getOrElse {
                    InstalledAppInfo(packageName, packageName, null, isSystemApp = false)
                }
            }
        }

    private fun installedApplications(packageManager: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

    private fun ApplicationInfo.toInstalledAppInfo(packageManager: PackageManager): InstalledAppInfo {
        val label = runCatching { packageManager.getApplicationLabel(this).toString() }
            .getOrDefault(packageName)
        val icon = runCatching {
            packageManager.getApplicationIcon(this).toBitmap(width = 96, height = 96).asImageBitmap()
        }.getOrNull()
        val systemApp = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        return InstalledAppInfo(packageName, label, icon, systemApp)
    }
}

internal fun filterInstalledApps(
    apps: List<InstalledAppInfo>,
    query: String,
    showSystemApps: Boolean,
): List<InstalledAppInfo> = apps.filter { app ->
    (showSystemApps || !app.isSystemApp) &&
        (app.appName.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true))
}
