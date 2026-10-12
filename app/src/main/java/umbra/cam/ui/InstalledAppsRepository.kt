package umbra.cam.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.coroutineContext

internal data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
)

internal object InstalledAppsRepository {
    suspend fun loadInstalledApps(context: Context): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val packageManager = appContext.packageManager
        val coroutineContext = coroutineContext
        val apps = ArrayList<InstalledAppInfo>()
        for (applicationInfo in installedApplications(packageManager)) {
            coroutineContext.ensureActive()
            if (applicationInfo.packageName == appContext.packageName) continue
            val label = runCatching { packageManager.getApplicationLabel(applicationInfo).toString() }
                .getOrDefault(applicationInfo.packageName)
            apps += applicationInfo.toInstalledAppInfo(label)
        }
        apps.sortedBy { it.appName.lowercase(Locale.getDefault()) }
    }

    suspend fun loadScopedApps(context: Context, packages: List<String>): List<InstalledAppInfo> =
        withContext(Dispatchers.IO) {
            val packageManager = context.applicationContext.packageManager
            val coroutineContext = coroutineContext
            packages.map { packageName ->
                coroutineContext.ensureActive()
                runCatching {
                    val info = packageManager.getApplicationInfo(packageName, 0)
                    val label = packageManager.getApplicationLabel(info).toString()
                    info.toInstalledAppInfo(label)
                }.getOrElse {
                    InstalledAppInfo(packageName, packageName, isSystemApp = false)
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

    private fun ApplicationInfo.toInstalledAppInfo(label: String): InstalledAppInfo {
        val systemApp = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        return InstalledAppInfo(packageName, label, systemApp)
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
