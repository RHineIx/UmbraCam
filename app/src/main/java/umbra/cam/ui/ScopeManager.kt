package umbra.cam.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import umbra.cam.R
import umbra.cam.utils.RootUtils
import java.util.Locale
import kotlin.coroutines.coroutineContext

// ==========================================
// 0. IMMUTABLE COLLECTION WRAPPERS
// ==========================================

@Immutable
internal data class ImmutableList<out T>(
    val items: List<T> = emptyList(),
) {
    val size: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    fun isNotEmpty(): Boolean = items.isNotEmpty()
}

@Immutable
internal data class ImmutableSet<out T>(
    val items: Set<T> = emptySet(),
) {
    val size: Int get() = items.size

    operator fun contains(element: @UnsafeVariance T): Boolean = items.contains(element)
}

// ==========================================
// 1. STATE & LSPOSED COMMAND MANAGER
// ==========================================

@Stable
class ScopeManager {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal var scopedApps by mutableStateOf(ImmutableList<String>())
        private set

    fun updateFromService(
        service: XposedService,
        packageName: String,
    ) {
        val list = runCatching { service.scope.filterNot { it == packageName } }.getOrDefault(emptyList())
        scopedApps = ImmutableList(list)
    }

    fun clearApps() {
        scopedApps = ImmutableList()
    }

    fun applyScopeSelection(
        context: Context,
        service: XposedService?,
        currentPackage: String,
        selected: Set<String>,
    ) {
        if (service == null) {
            Toast.makeText(context, R.string.toast_scope_service_disconnected, Toast.LENGTH_SHORT).show()
            return
        }

        val current = scopedApps.items.toSet()
        val added = (selected - current).toList().sorted()
        val removed = (current - selected).toList().sorted()

        try {
            if (removed.isNotEmpty()) {
                service.removeScope(removed)
                scopedApps = ImmutableList((current - removed.toSet()).toList().sorted())
                // Automatically force-stop removed apps in the background so active hooks are unloaded cleanly
                backgroundScope.launch {
                    RootUtils.forceStopApps(removed)
                }
            }
            if (added.isNotEmpty()) {
                requestScope(context, service, currentPackage, added)
            } else if (removed.isNotEmpty()) {
                Toast
                    .makeText(
                        context,
                        context.resources.getQuantityString(R.plurals.toast_scope_removed, removed.size, removed.size),
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        } catch (error: Throwable) {
            Toast
                .makeText(
                    context,
                    context.getString(R.string.toast_scope_update_failed, error.message.orEmpty()),
                    Toast.LENGTH_LONG,
                ).show()
        }
    }

    private fun requestScope(
        context: Context,
        service: XposedService,
        currentPackage: String,
        packages: List<String>,
    ) {
        try {
            service.requestScope(
                packages,
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) {
                        Handler(Looper.getMainLooper()).post {
                            val merged =
                                (scopedApps.items + approved)
                                    .filterNot { it == currentPackage }
                                    .distinct()
                                    .sorted()
                            scopedApps = ImmutableList(merged)
                            Toast
                                .makeText(
                                    context,
                                    context.resources.getQuantityString(R.plurals.toast_scope_updated, approved.size, approved.size),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    }

                    override fun onScopeRequestFailed(message: String) {
                        Handler(Looper.getMainLooper()).post {
                            Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.toast_scope_request_failed, message),
                                    Toast.LENGTH_LONG,
                                ).show()
                        }
                    }
                },
            )
        } catch (error: Throwable) {
            Toast
                .makeText(
                    context,
                    context.getString(R.string.toast_scope_request_failed, error.message.orEmpty()),
                    Toast.LENGTH_LONG,
                ).show()
        }
    }
}

// ==========================================
// 2. REPOSITORY & DATA MODELS
// ==========================================

@Immutable
internal data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val displaySubtitle: String,
    val lowerAppName: String = appName.lowercase(Locale.ROOT),
    val lowerPackageName: String = packageName.lowercase(Locale.ROOT),
)

@OptIn(ExperimentalCoroutinesApi::class)
internal object InstalledAppsRepository {
    // Limit background PackageManager label resolution to 2 threads so it never saturates all CPU cores
    private val repositoryDispatcher = Dispatchers.IO.limitedParallelism(2)

    @Volatile
    private var cachedUserApps: ImmutableList<InstalledAppInfo>? = null

    @Volatile
    private var cachedAllApps: ImmutableList<InstalledAppInfo>? = null

    /**
     * Loads installed applications in two tiers:
     * - When includeSystemApps is false, skips APK label parsing for all system apps (~80% fewer IPC/disk reads).
     * - When includeSystemApps is true, reuses already-resolved user apps and resolves system apps on demand.
     */
    suspend fun loadInstalledApps(
        context: Context,
        includeSystemApps: Boolean,
    ): ImmutableList<InstalledAppInfo> =
        withContext(repositoryDispatcher) {
            if (!includeSystemApps) {
                cachedUserApps?.let { return@withContext it }
            } else {
                cachedAllApps?.let { return@withContext it }
            }

            val appContext = context.applicationContext
            val packageManager = appContext.packageManager
            val coroutineContext = coroutineContext
            val rawList = installedApplications(packageManager)

            if (!includeSystemApps) {
                val userApps = ArrayList<InstalledAppInfo>(96)
                for (applicationInfo in rawList) {
                    coroutineContext.ensureActive()
                    if (applicationInfo.packageName == appContext.packageName) continue
                    val isSystem = applicationInfo.isSystemPackage()
                    if (isSystem) continue
                    val label =
                        runCatching { packageManager.getApplicationLabel(applicationInfo).toString() }
                            .getOrDefault(applicationInfo.packageName)
                    userApps +=
                        InstalledAppInfo(
                            packageName = applicationInfo.packageName,
                            appName = label,
                            isSystemApp = false,
                            displaySubtitle = applicationInfo.packageName,
                        )
                }
                userApps.sortBy { it.lowerAppName }
                ImmutableList(userApps).also { cachedUserApps = it }
            } else {
                val existingUserMap = cachedUserApps?.items?.associateBy { it.packageName }.orEmpty()
                val allApps = ArrayList<InstalledAppInfo>(rawList.size)
                val userApps = ArrayList<InstalledAppInfo>(96)

                for (applicationInfo in rawList) {
                    coroutineContext.ensureActive()
                    val pkg = applicationInfo.packageName
                    if (pkg == appContext.packageName) continue
                    val existing = existingUserMap[pkg]
                    if (existing != null) {
                        allApps += existing
                        userApps += existing
                        continue
                    }
                    val isSystem = applicationInfo.isSystemPackage()
                    val label =
                        runCatching { packageManager.getApplicationLabel(applicationInfo).toString() }
                            .getOrDefault(pkg)
                    val subtitle =
                        if (isSystem) {
                            appContext.getString(R.string.app_package_system, pkg)
                        } else {
                            pkg
                        }
                    val info =
                        InstalledAppInfo(
                            packageName = pkg,
                            appName = label,
                            isSystemApp = isSystem,
                            displaySubtitle = subtitle,
                        )
                    allApps += info
                    if (!isSystem) userApps += info
                }
                allApps.sortBy { it.lowerAppName }
                if (cachedUserApps == null) {
                    userApps.sortBy { it.lowerAppName }
                    cachedUserApps = ImmutableList(userApps)
                }
                ImmutableList(allApps).also { cachedAllApps = it }
            }
        }

    suspend fun loadScopedApps(
        context: Context,
        packages: List<String>,
    ): ImmutableList<InstalledAppInfo> =
        withContext(repositoryDispatcher) {
            if (packages.isEmpty()) return@withContext ImmutableList()
            val appContext = context.applicationContext
            val packageManager = appContext.packageManager
            val coroutineContext = coroutineContext
            val mapped =
                packages.map { packageName ->
                    coroutineContext.ensureActive()
                    runCatching {
                        val info = packageManager.getApplicationInfo(packageName, 0)
                        val label = packageManager.getApplicationLabel(info).toString()
                        val isSystem = info.isSystemPackage()
                        val subtitle =
                            if (isSystem) {
                                appContext.getString(R.string.app_package_system, packageName)
                            } else {
                                packageName
                            }
                        InstalledAppInfo(packageName, label, isSystem, subtitle)
                    }.getOrElse {
                        InstalledAppInfo(packageName, packageName, isSystemApp = false, displaySubtitle = packageName)
                    }
                }
            ImmutableList(mapped)
        }

    fun hasSystemAppInList(
        context: Context,
        packages: Collection<String>,
    ): Boolean {
        if (packages.isEmpty()) return false
        val pm = context.applicationContext.packageManager
        for (pkg in packages) {
            val isSys = runCatching { pm.getApplicationInfo(pkg, 0).isSystemPackage() }.getOrDefault(false)
            if (isSys) return true
        }
        return false
    }

    private fun installedApplications(packageManager: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

    private fun ApplicationInfo.isSystemPackage(): Boolean =
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}

internal fun filterInstalledApps(
    apps: List<InstalledAppInfo>,
    query: String,
    showSystemApps: Boolean,
): List<InstalledAppInfo> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    if (normalizedQuery.isEmpty() && !showSystemApps) {
        return apps.filter { !it.isSystemApp }
    }
    if (normalizedQuery.isEmpty() && showSystemApps) {
        return apps
    }
    return apps.filter { app ->
        (showSystemApps || !app.isSystemApp) &&
            (app.lowerAppName.contains(normalizedQuery) || app.lowerPackageName.contains(normalizedQuery))
    }
}

// ==========================================
// 3. UI COMPONENTS
// ==========================================

@OptIn(ExperimentalCoroutinesApi::class)
internal object AppIconCache {
    private const val ICON_SIZE_PX = 72
    private const val ICON_CORNER_RADIUS_PX = 18f
    private const val ICON_CACHE_KIB = 6 * 1024

    // Restrict concurrent icon decoding to at most 2 background threads to prevent CPU spikes while scrolling
    val iconDispatcher = Dispatchers.IO.limitedParallelism(2)

    private val cache =
        object : LruCache<String, ImageBitmap>(ICON_CACHE_KIB) {
            override fun sizeOf(
                key: String,
                value: ImageBitmap,
            ): Int = (value.width.toLong() * value.height * 4L / 1024L).toInt().coerceAtLeast(1)
        }

    fun get(packageName: String): ImageBitmap? = cache.get(packageName)

    fun put(
        packageName: String,
        icon: ImageBitmap,
    ) {
        cache.put(packageName, icon)
    }

    fun clear() = cache.evictAll()

    fun loadOrDecodeIcon(
        packageManager: PackageManager,
        packageName: String,
    ): ImageBitmap? {
        get(packageName)?.let { return it }
        return runCatching {
            val raw =
                packageManager
                    .getApplicationIcon(packageName)
                    .toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX, config = Bitmap.Config.ARGB_8888)
            val output = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    shader = BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                }
            val rect = RectF(0f, 0f, ICON_SIZE_PX.toFloat(), ICON_SIZE_PX.toFloat())
            canvas.drawRoundRect(rect, ICON_CORNER_RADIUS_PX, ICON_CORNER_RADIUS_PX, paint)
            if (raw !== output) {
                raw.recycle()
            }
            output.asImageBitmap().also { put(packageName, it) }
        }.getOrNull()
    }
}

@Composable
internal fun AppIcon(
    packageName: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    debounceMs: Long = 0L,
) {
    val appContext = LocalContext.current.applicationContext
    var iconBitmap by remember(packageName) { mutableStateOf(AppIconCache.get(packageName)) }

    if (iconBitmap == null) {
        LaunchedEffect(packageName) {
            // During fast scrolling, rows leave the viewport in <30ms; delaying 45ms cancels
            // the coroutine before touching PackageManager or decoding bitmaps for flung rows.
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            val loaded =
                withContext(AppIconCache.iconDispatcher) {
                    AppIconCache.loadOrDecodeIcon(appContext.packageManager, packageName)
                }
            if (loaded != null) {
                iconBitmap = loaded
            }
        }
    }

    val currentBitmap = iconBitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap,
            contentDescription = contentDescription,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Android, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun ScopeAppsCard(
    scopedApps: ImmutableList<String>,
    appInfos: ImmutableList<InstalledAppInfo>,
    isModuleActive: Boolean,
    onManageApps: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val rowScrollState = rememberScrollState()
    var expandedMenuApp by remember { mutableStateOf<String?>(null) }

    UmbraOutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.label_active_scopes), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text =
                            if (scopedApps.isEmpty()) {
                                stringResource(R.string.label_no_apps_selected)
                            } else {
                                pluralStringResource(R.plurals.label_apps_in_scope, scopedApps.size, scopedApps.size)
                            },
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
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rowScrollState),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (app in appInfos.items) {
                        key(app.packageName) {
                            Box {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier =
                                        Modifier
                                            .width(72.dp)
                                            .clickable { expandedMenuApp = app.packageName }
                                            .padding(vertical = 6.dp),
                                ) {
                                    AppIcon(
                                        packageName = app.packageName,
                                        contentDescription = app.appName,
                                        modifier = Modifier.size(48.dp),
                                        debounceMs = 0L,
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

                                if (expandedMenuApp == app.packageName) {
                                    DropdownMenu(
                                        expanded = true,
                                        onDismissRequest = { expandedMenuApp = null },
                                        modifier = Modifier.background(colorScheme.surface),
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Launch", fontSize = 16.sp, fontWeight = FontWeight.Medium) },
                                            onClick = {
                                                expandedMenuApp = null
                                                RootUtils.launchApp(context, app.packageName)
                                            },
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
                                                        Toast
                                                            .makeText(
                                                                context,
                                                                "Root access required to force stop",
                                                                Toast.LENGTH_SHORT,
                                                            ).show()
                                                    }
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Restart", fontSize = 16.sp, fontWeight = FontWeight.Medium) },
                                            onClick = {
                                                expandedMenuApp = null
                                                coroutineScope.launch {
                                                    val success = RootUtils.restartApp(context, app.packageName)
                                                    if (!success) {
                                                        Toast
                                                            .makeText(
                                                                context,
                                                                "Failed to restart or missing root access",
                                                                Toast.LENGTH_SHORT,
                                                            ).show()
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lightweight, zero-animation checkbox drawn via drawWithCache to avoid allocating
 * Material3 Transition, 5 animations, and PathMeasure objects per row while scrolling.
 */
@Composable
private fun FastCheckbox(
    checked: Boolean,
    primaryColor: Color,
    checkmarkColor: Color,
    outlineColor: Color,
) {
    Spacer(
        modifier =
            Modifier
                .size(20.dp)
                .drawWithCache {
                    val strokeWidthPx = 2.dp.toPx()
                    val cornerRadiusPx = 2.dp.toPx()
                    val corner = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                    val halfStroke = strokeWidthPx / 2f
                    val boxTopLeft = Offset(halfStroke, halfStroke)
                    val boxSize = Size(size.width - strokeWidthPx, size.height - strokeWidthPx)
                    val borderStroke = Stroke(width = strokeWidthPx)
                    val checkStroke =
                        Stroke(
                            width = strokeWidthPx,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        )
                    val checkPath =
                        Path().apply {
                            moveTo(size.width * 0.22f, size.height * 0.52f)
                            lineTo(size.width * 0.43f, size.height * 0.72f)
                            lineTo(size.width * 0.78f, size.height * 0.30f)
                        }

                    onDrawBehind {
                        if (checked) {
                            drawRoundRect(
                                color = primaryColor,
                                cornerRadius = corner,
                            )
                            drawPath(
                                path = checkPath,
                                color = checkmarkColor,
                                style = checkStroke,
                            )
                        } else {
                            drawRoundRect(
                                color = outlineColor,
                                topLeft = boxTopLeft,
                                size = boxSize,
                                cornerRadius = corner,
                                style = borderStroke,
                            )
                        }
                    }
                },
    )
}

/**
 * Flat, single-pass row for TargetAppsSheet using debounced icon loading and FastCheckbox.
 */
@Composable
private fun AppPickerRow(
    app: InstalledAppInfo,
    checked: Boolean,
    onToggle: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val onRowClick =
        remember(app.packageName, onToggle) {
            { onToggle(app.packageName) }
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onRowClick)
                .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            packageName = app.packageName,
            contentDescription = app.appName,
            modifier = Modifier.size(42.dp),
            debounceMs = 45L,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.displaySubtitle,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        FastCheckbox(
            checked = checked,
            primaryColor = colorScheme.primary,
            checkmarkColor = colorScheme.onPrimary,
            outlineColor = colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun TargetAppsSheet(
    apps: ImmutableList<InstalledAppInfo>,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedPackages: ImmutableSet<String>,
    showSystemApps: Boolean,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onTogglePackage: (String) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    isLoading: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme

    // Capture a static snapshot of initially selected packages when the sheet opens
    // so items do not jump while the user toggles checkboxes during scrolling.
    val initialSelectedPackages = remember { selectedPackages.items }

    val filteredApps by remember(apps, query, showSystemApps, initialSelectedPackages) {
        derivedStateOf {
            filterInstalledApps(apps.items, query, showSystemApps)
                .sortedByDescending { it.packageName in initialSelectedPackages }
        }
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
                modifier =
                    Modifier
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
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (filteredApps.isEmpty()) {
                Text(
                    stringResource(R.string.label_no_apps_match),
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                )
            } else {
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .heightIn(max = 440.dp),
                    ) {
                        items(
                            items = filteredApps,
                            key = { it.packageName },
                            contentType = { "installed-app" },
                        ) { app ->
                            val checked = app.packageName in selectedPackages
                            AppPickerRow(
                                app = app,
                                checked = checked,
                                onToggle = onTogglePackage,
                            )
                        }
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
