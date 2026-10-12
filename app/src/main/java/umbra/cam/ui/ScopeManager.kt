package umbra.cam.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import umbra.cam.R
import umbra.cam.utils.RootUtils
import java.util.Locale
import kotlin.coroutines.coroutineContext

// ==========================================
// 1. STATE & LSPOSED COMMAND MANAGER
// ==========================================

class ScopeManager {
    var scopedApps by mutableStateOf<List<String>>(emptyList())
        private set

    fun updateFromService(service: XposedService, packageName: String) {
        scopedApps = runCatching { service.scope.filterNot { it == packageName } }.getOrDefault(emptyList())
    }

    fun clearApps() {
        scopedApps = emptyList()
    }

    fun applyScopeSelection(context: Context, service: XposedService?, currentPackage: String, selected: Set<String>) {
        if (service == null) {
            Toast.makeText(context, R.string.toast_scope_service_disconnected, Toast.LENGTH_SHORT).show()
            return
        }

        val current = scopedApps.toSet()
        val added = (selected - current).toList().sorted()
        val removed = (current - selected).toList().sorted()

        try {
            if (removed.isNotEmpty()) {
                service.removeScope(removed)
                scopedApps = (current - removed.toSet()).toList().sorted()
            }
            if (added.isNotEmpty()) {
                requestScope(context, service, currentPackage, added)
            } else if (removed.isNotEmpty()) {
                Toast.makeText(
                    context,
                    context.resources.getQuantityString(R.plurals.toast_scope_removed, removed.size, removed.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (error: Throwable) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_scope_update_failed, error.message.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestScope(context: Context, service: XposedService, currentPackage: String, packages: List<String>) {
        try {
            service.requestScope(packages, object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    Handler(Looper.getMainLooper()).post {
                        scopedApps = (scopedApps + approved)
                            .filterNot { it == currentPackage }
                            .distinct()
                            .sorted()
                        Toast.makeText(
                            context,
                            context.resources.getQuantityString(R.plurals.toast_scope_updated, approved.size, approved.size),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onScopeRequestFailed(message: String) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_scope_request_failed, message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })
        } catch (error: Throwable) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_scope_request_failed, error.message.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

// ==========================================
// 2. REPOSITORY & DATA MODELS
// ==========================================

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

// ==========================================
// 3. UI COMPONENTS
// ==========================================

internal object AppIconCache {
    private const val ICON_SIZE_PX = 96
    private const val ICON_CACHE_KIB = 2 * 1024

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
                        .toBitmap(width = 96, height = 96)
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

@Composable
internal fun ScopeAppsCard(
    scopedApps: List<String>,
    appInfos: List<InstalledAppInfo>,
    isModuleActive: Boolean,
    onManageApps: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var expandedMenuApp by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.label_active_scopes), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (scopedApps.isEmpty()) stringResource(R.string.label_no_apps_selected)
                        else pluralStringResource(R.plurals.label_apps_in_scope, scopedApps.size, scopedApps.size),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onManageApps, enabled = isModuleActive) {
                    Icon(
                        Icons.Default.Apps,
                        contentDescription = stringResource(R.string.desc_manage_target_apps),
                        tint = if (isModuleActive) colorScheme.primary else colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (appInfos.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(appInfos, key = { it.packageName }, contentType = { "scoped-app" }) { app ->
                        Box {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { expandedMenuApp = app.packageName }
                                    .padding(vertical = 6.dp)
                            ) {
                                AppIcon(
                                    packageName = app.packageName,
                                    contentDescription = app.appName,
                                    modifier = Modifier.size(48.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    app.appName,
                                    fontSize = 11.sp,
                                    color = colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            
                            DropdownMenu(
                                expanded = expandedMenuApp == app.packageName,
                                onDismissRequest = { expandedMenuApp = null },
                                modifier = Modifier.background(colorScheme.surface)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Launch", fontSize = 16.sp, fontWeight = FontWeight.Medium) },
                                    onClick = {
                                        expandedMenuApp = null
                                        RootUtils.launchApp(context, app.packageName)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Force stop", fontSize = 16.sp, fontWeight = FontWeight.Medium) },
                                    onClick = {
                                        expandedMenuApp = null
                                        coroutineScope.launch {
                                            val success = RootUtils.forceStopApp(app.packageName)
                                            if (success) {
                                                Toast.makeText(context, "${app.appName} force stopped", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Root access required to force stop", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Restart", fontSize = 16.sp, fontWeight = FontWeight.Medium) },
                                    onClick = {
                                        expandedMenuApp = null
                                        coroutineScope.launch {
                                            val success = RootUtils.restartApp(context, app.packageName)
                                            if (!success) {
                                                Toast.makeText(context, "Failed to restart or missing root access", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TargetAppsSheet(
    apps: List<InstalledAppInfo>,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedPackages: Set<String>,
    showSystemApps: Boolean,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onTogglePackage: (String) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    isLoading: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme
    
    // الحل هنا: التقاط التطبيقات المحددة كـ Snapshot ثابت عند أول مرة تُفتح فيها النافذة.
    // هذا سيمنع التطبيقات من القفز والاختفاء فجأة أثناء التمرير والتحديد.
    val initialSelectedPackages = remember { selectedPackages.toSet() }
    
    val filteredApps = remember(apps, query, showSystemApps, initialSelectedPackages) {
        filterInstalledApps(apps, query, showSystemApps)
            .sortedByDescending { it.packageName in initialSelectedPackages }
    }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.title_choose_target_apps), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.desc_choose_target_apps),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onShowSystemAppsChange(!showSystemApps) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.label_show_system_apps), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.desc_show_system_apps),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = showSystemApps, onCheckedChange = onShowSystemAppsChange)
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search)) },
                placeholder = { Text(stringResource(R.string.hint_search_apps)) },
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (isLoading) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (filteredApps.isEmpty()) {
                Text(
                    stringResource(R.string.label_no_apps_match),
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filteredApps, key = { it.packageName }, contentType = { "installed-app" }) { app ->
                        val checked = app.packageName in selectedPackages
                        ListItem(
                            headlineContent = {
                                Text(app.appName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(
                                    if (app.isSystemApp) stringResource(R.string.app_package_system, app.packageName)
                                    else app.packageName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                AppIcon(
                                    packageName = app.packageName,
                                    contentDescription = app.appName,
                                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)),
                                )
                            },
                            trailingContent = {
                                Checkbox(checked = checked, onCheckedChange = { onTogglePackage(app.packageName) })
                            },
                            modifier = Modifier.clickable { onTogglePackage(app.packageName) },
                        )
                    }
                }
            }
            Button(
                onClick = onApply,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 24.dp).height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(stringResource(R.string.action_apply_scope, selectedPackages.size))
            }
        }
    }
}
