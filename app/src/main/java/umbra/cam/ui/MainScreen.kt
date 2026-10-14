package umbra.cam.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import umbra.cam.R
import umbra.cam.data.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Immutable
internal data class PipelineSnapshot(
    val app: String = "",
    val route: String = "",
    val timestamp: Long = 0L,
)

/**
 * Stable state holder for MainScreen that isolates state reads and provides stable action
 * methods so child composables never recompose due to lambda allocations.
 */
@Stable
internal class MainUiController(
    private val appContext: Context,
    private val preferences: SharedPreferences,
    private val coroutineScope: CoroutineScope,
) {
    val pipelineUri: Uri = Constants.PIPELINE_CONTENT_URI.toUri()

    var mediaPath by mutableStateOf(preferences.getString(Constants.KEY_MEDIA_PATH, "").orEmpty())
        private set

    var isMirrored by mutableStateOf(preferences.getBoolean(Constants.KEY_IS_MIRRORED, false))
        private set

    var rotationAngle by mutableIntStateOf(preferences.getInt(Constants.KEY_ROTATION_ANGLE, 0))
        private set

    var enableNotifications by mutableStateOf(preferences.getBoolean(Constants.KEY_ENABLE_CAPTURE_NOTIF, true))
        private set

    var isProcessing by mutableStateOf(false)
    var previewBitmap by mutableStateOf<ImageBitmap?>(null)

    var pipeline by mutableStateOf(
        PipelineSnapshot(
            preferences.getString(Constants.KEY_LATEST_PIPELINE_APP, "").orEmpty(),
            preferences.getString(Constants.KEY_LATEST_PIPELINE_ROUTE, "").orEmpty(),
            preferences.getLong(Constants.KEY_LATEST_PIPELINE_TIME, 0L),
        ),
    )

    var scopedAppInfos by mutableStateOf(ImmutableList<InstalledAppInfo>())
    var installedAppInfos by mutableStateOf(ImmutableList<InstalledAppInfo>())
    var isLoadingInstalledApps by mutableStateOf(false)

    var showAppPicker by mutableStateOf(false)
    var appSearchQuery by mutableStateOf("")
    var showSystemApps by mutableStateOf(false)
    var selectedPackages by mutableStateOf(ImmutableSet<String>())

    fun clearMedia() {
        mediaPath = ""
        preferences.edit { putString(Constants.KEY_MEDIA_PATH, "") }
    }

    fun rotateLeft() {
        val next = (rotationAngle - 90 + 360) % 360
        rotationAngle = next
        preferences.edit { putInt(Constants.KEY_ROTATION_ANGLE, next) }
    }

    fun rotateRight() {
        val next = (rotationAngle + 90) % 360
        rotationAngle = next
        preferences.edit { putInt(Constants.KEY_ROTATION_ANGLE, next) }
    }

    fun toggleMirror() {
        val next = !isMirrored
        isMirrored = next
        preferences.edit { putBoolean(Constants.KEY_IS_MIRRORED, next) }
    }

    fun onMediaImported(newPath: String) {
        mediaPath = newPath
        isMirrored = false
        rotationAngle = 0
        preferences.edit {
            putString(Constants.KEY_MEDIA_PATH, newPath)
            putBoolean(Constants.KEY_IS_MIRRORED, false)
            putInt(Constants.KEY_ROTATION_ANGLE, 0)
        }
    }

    fun updateNotificationsEnabled(enabled: Boolean) {
        enableNotifications = enabled
        preferences.edit { putBoolean(Constants.KEY_ENABLE_CAPTURE_NOTIF, enabled) }
    }

    fun resetPipeline() {
        pipeline = PipelineSnapshot()
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.call(pipelineUri, Constants.METHOD_CLEAR_PIPELINE, null, null)
            }
        }
    }

    fun openAppPicker(currentScoped: List<String>) {
        val scopedSet = currentScoped.toSet()
        selectedPackages = ImmutableSet(scopedSet)
        appSearchQuery = ""
        showSystemApps = scopedAppInfos.items.any { it.isSystemApp && it.packageName in scopedSet }
        showAppPicker = true
    }

    fun dismissAppPicker() {
        showAppPicker = false
    }

    fun updateSearchQuery(query: String) {
        appSearchQuery = query
    }

    fun updateShowSystemApps(show: Boolean) {
        showSystemApps = show
    }

    fun togglePackageSelection(packageName: String) {
        val current = selectedPackages.items
        val updated = if (packageName in current) current - packageName else current + packageName
        selectedPackages = ImmutableSet(updated)
    }
}

/**
 * Lightweight card container that caches its rounded fill and 1dp border stroke in a single
 * draw pass without creating offscreen clip RenderNodes on the RenderThread.
 */
@Composable
internal fun UmbraOutlinedCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier.drawWithCache {
                val radiusPx = cornerRadius.toPx()
                val strokeWidthPx = 1.dp.toPx()
                val halfStroke = strokeWidthPx / 2f
                val corner = CornerRadius(radiusPx, radiusPx)
                val strokeTopLeft = Offset(halfStroke, halfStroke)
                val strokeSize =
                    Size(
                        (size.width - strokeWidthPx).coerceAtLeast(0f),
                        (size.height - strokeWidthPx).coerceAtLeast(0f),
                    )
                val strokeStyle = Stroke(width = strokeWidthPx)

                onDrawBehind {
                    if (containerColor != Color.Transparent) {
                        drawRoundRect(
                            color = containerColor,
                            cornerRadius = corner,
                        )
                    }
                    drawRoundRect(
                        color = borderColor,
                        topLeft = strokeTopLeft,
                        size = strokeSize,
                        cornerRadius = corner,
                        style = strokeStyle,
                    )
                }
            },
        content = content,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MainScreen(
    isModuleActive: Boolean,
    serviceFramework: String,
    scopedApps: ImmutableList<String>,
    onApplyScopeSelection: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val preferences =
        remember(appContext) {
            appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        }
    val coroutineScope = rememberCoroutineScope()
    val controller =
        remember(appContext, preferences, coroutineScope) {
            MainUiController(appContext, preferences, coroutineScope)
        }
    val scrollState = rememberScrollState()

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!granted) Toast.makeText(context, R.string.toast_notification_permission_required, Toast.LENGTH_SHORT).show()
        }

    val mediaPickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    controller.isProcessing = true
                    coroutineScope.launch {
                        try {
                            when (val importResult = processMediaSelection(appContext, uri)) {
                                is MediaImportResult.Success -> controller.onMediaImported(importResult.path)
                                is MediaImportResult.Failure -> {
                                    Toast.makeText(context, importResult.messageResource, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } finally {
                            controller.isProcessing = false
                        }
                    }
                }
            }
        }

    LaunchedEffect(controller.mediaPath) {
        controller.previewBitmap = loadPreviewBitmap(controller.mediaPath)
    }

    LaunchedEffect(scopedApps) {
        controller.scopedAppInfos = InstalledAppsRepository.loadScopedApps(appContext, scopedApps.items)
    }

    // Pre-warm user installed apps quietly in the background after initial screen render
    // so opening the target apps sheet is instantaneous without a 60% CPU spike.
    LaunchedEffect(appContext) {
        delay(400)
        val userApps = InstalledAppsRepository.loadInstalledApps(appContext, includeSystemApps = false)
        if (controller.installedAppInfos.isEmpty()) {
            controller.installedAppInfos = userApps
        }
    }

    // Load user apps or system apps on demand when the sheet opens or when system apps toggle changes
    LaunchedEffect(controller.showAppPicker, controller.showSystemApps) {
        if (controller.showAppPicker) {
            val needSystem = controller.showSystemApps
            val currentItems = controller.installedAppInfos.items
            val hasLoadedRequiredTier =
                if (needSystem) {
                    currentItems.any { it.isSystemApp }
                } else {
                    currentItems.isNotEmpty()
                }
            if (!hasLoadedRequiredTier) {
                controller.isLoadingInstalledApps = true
                try {
                    controller.installedAppInfos =
                        InstalledAppsRepository.loadInstalledApps(
                            appContext,
                            includeSystemApps = needSystem,
                        )
                } finally {
                    controller.isLoadingInstalledApps = false
                }
            }
        }
    }

    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    DisposableEffect(appContext, controller, lifecycle) {
        var refreshJob: Job? = null
        var observerRegistered = false

        fun refreshPipeline() {
            refreshJob?.cancel()
            refreshJob =
                coroutineScope.launch {
                    delay(100)
                    val latest = withContext(Dispatchers.IO) { readPipelineSnapshot(appContext, preferences) }
                    if (controller.pipeline != latest) {
                        controller.pipeline = latest
                    }
                }
        }

        val contentObserver =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(
                    selfChange: Boolean,
                    uri: Uri?,
                ) = refreshPipeline()
            }

        fun registerContentObserver() {
            if (!observerRegistered) {
                appContext.contentResolver.registerContentObserver(controller.pipelineUri, false, contentObserver)
                observerRegistered = true
                refreshPipeline()
            }
        }

        fun unregisterContentObserver() {
            if (observerRegistered) {
                appContext.contentResolver.unregisterContentObserver(contentObserver)
                observerRegistered = false
            }
            refreshJob?.cancel()
            refreshJob = null
        }

        val lifecycleObserver =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> registerContentObserver()
                    Lifecycle.Event.ON_STOP -> unregisterContentObserver()
                    else -> Unit
                }
            }
        if (lifecycle != null) lifecycle.addObserver(lifecycleObserver) else registerContentObserver()
        onDispose {
            unregisterContentObserver()
            lifecycle?.removeObserver(lifecycleObserver)
        }
    }

    // Memoize all action lambdas once so child cards can skip recomposition completely
    val onManageAppsAction =
        remember(controller, scopedApps) {
            { controller.openAppPicker(scopedApps.items) }
        }
    val onClearMediaAction = remember(controller) { { controller.clearMedia() } }
    val onRotateLeftAction = remember(controller) { { controller.rotateLeft() } }
    val onToggleMirrorAction = remember(controller) { { controller.toggleMirror() } }
    val onRotateRightAction = remember(controller) { { controller.rotateRight() } }
    val rotationProvider = remember(controller) { { controller.rotationAngle } }
    val mirrorProvider = remember(controller) { { controller.isMirrored } }
    val onChooseMediaAction =
        remember(mediaPickerLauncher) {
            {
                val intent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    }
                mediaPickerLauncher.launch(intent)
            }
        }
    val onToggleNotificationsAction =
        remember(controller, context, notificationPermissionLauncher) {
            { enabled: Boolean ->
                controller.updateNotificationsEnabled(enabled)
                if (enabled &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    val onCopyPipelineAction =
        remember(controller, context) {
            {
                val snap = controller.pipeline
                if (snap.app.isNotEmpty()) {
                    val clipboardText = context.getString(R.string.pipeline_clipboard, snap.app, snap.route)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.label_pipeline_monitor), clipboardText))
                    Toast.makeText(context, R.string.toast_copied, Toast.LENGTH_SHORT).show()
                }
            }
        }
    val onResetPipelineAction = remember(controller) { { controller.resetPipeline() } }

    val colorScheme = MaterialTheme.colorScheme
    val appTitle = stringResource(R.string.title_manager)
    val brandAnnotatedTitle =
        remember(appTitle, colorScheme.onBackground, colorScheme.primary) {
            val splitIndex =
                if (appTitle.startsWith("Umbra", ignoreCase = true) && appTitle.length > 5) {
                    5
                } else {
                    appTitle.length / 2
                }
            buildAnnotatedString {
                // Extra-thick font weight for "Umbra"
                withStyle(
                    SpanStyle(
                        color = colorScheme.onBackground,
                        fontWeight = FontWeight.Black,
                    ),
                ) {
                    append(appTitle.substring(0, splitIndex))
                }
                // Accent color for "Cam"
                withStyle(
                    SpanStyle(
                        color = colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    ),
                ) {
                    append(appTitle.substring(splitIndex))
                }
            }
        }

    Scaffold(containerColor = colorScheme.background, modifier = Modifier.fillMaxSize()) { insets ->
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(insets)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 24.dp)
                        .padding(top = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = brandAnnotatedTitle,
                        fontSize = 32.sp,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(12.dp))
                    ModuleStatusCard(isModuleActive, serviceFramework)
                }

                ScopeAppsCard(
                    scopedApps = scopedApps,
                    appInfos = controller.scopedAppInfos,
                    isModuleActive = isModuleActive,
                    onManageApps = onManageAppsAction,
                )

                MediaSourceCard(
                    previewBitmap = controller.previewBitmap,
                    isProcessing = controller.isProcessing,
                    isMirrored = controller.isMirrored,
                    rotationProvider = rotationProvider,
                    mirrorProvider = mirrorProvider,
                    onClear = onClearMediaAction,
                    onRotateLeft = onRotateLeftAction,
                    onToggleMirror = onToggleMirrorAction,
                    onRotateRight = onRotateRightAction,
                    onChooseMedia = onChooseMediaAction,
                )

                CaptureAlertsCard(
                    enabled = controller.enableNotifications,
                    onEnabledChange = onToggleNotificationsAction,
                )

                PipelineMonitorCard(
                    snapshot = controller.pipeline,
                    onCopy = onCopyPipelineAction,
                    onReset = onResetPipelineAction,
                )

                Text(
                    text = stringResource(R.string.note_restart),
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (controller.showAppPicker) {
        TargetAppsSheet(
            apps = controller.installedAppInfos,
            query = controller.appSearchQuery,
            onQueryChange = remember(controller) { { controller.updateSearchQuery(it) } },
            selectedPackages = controller.selectedPackages,
            showSystemApps = controller.showSystemApps,
            onShowSystemAppsChange = remember(controller) { { controller.updateShowSystemApps(it) } },
            onTogglePackage = remember(controller) { { controller.togglePackageSelection(it) } },
            onDismiss = remember(controller) { { controller.dismissAppPicker() } },
            onApply =
                remember(controller, onApplyScopeSelection) {
                    {
                        onApplyScopeSelection(controller.selectedPackages.items)
                        controller.dismissAppPicker()
                    }
                },
            isLoading = controller.isLoadingInstalledApps,
        )
    }
}

private fun readPipelineSnapshot(
    context: Context,
    preferences: SharedPreferences,
): PipelineSnapshot {
    val bundle =
        runCatching {
            context.contentResolver.call(
                Constants.PIPELINE_CONTENT_URI.toUri(),
                Constants.METHOD_GET_PIPELINE,
                null,
                null,
            )
        }.getOrNull()
    return if (bundle != null) {
        PipelineSnapshot(
            bundle.getString(Constants.KEY_LATEST_PIPELINE_APP).orEmpty(),
            bundle.getString(Constants.KEY_LATEST_PIPELINE_ROUTE).orEmpty(),
            bundle.getLong(Constants.KEY_LATEST_PIPELINE_TIME, 0L),
        )
    } else {
        PipelineSnapshot(
            preferences.getString(Constants.KEY_LATEST_PIPELINE_APP, "").orEmpty(),
            preferences.getString(Constants.KEY_LATEST_PIPELINE_ROUTE, "").orEmpty(),
            preferences.getLong(Constants.KEY_LATEST_PIPELINE_TIME, 0L),
        )
    }
}

@Composable
private fun ModuleStatusCard(
    isActive: Boolean,
    framework: String,
) {
    val colorScheme = MaterialTheme.colorScheme
    UmbraOutlinedCard(
        cornerRadius = 20.dp,
        containerColor = Color.Transparent,
        borderColor = if (isActive) colorScheme.primary else colorScheme.outlineVariant,
    ) {
        Text(
            text =
                when {
                    !isActive -> stringResource(R.string.status_module_inactive)
                    framework.isNotBlank() -> stringResource(R.string.status_module_active_framework, framework)
                    else -> stringResource(R.string.status_module_active)
                },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            fontSize = 14.sp,
            color = if (isActive) colorScheme.primary else colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun MediaSourceCard(
    previewBitmap: ImageBitmap?,
    isProcessing: Boolean,
    isMirrored: Boolean,
    rotationProvider: () -> Int,
    mirrorProvider: () -> Boolean,
    onClear: () -> Unit,
    onRotateLeft: () -> Unit,
    onToggleMirror: () -> Unit,
    onRotateRight: () -> Unit,
    onChooseMedia: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    UmbraOutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.label_media_preview), fontSize = 14.sp, color = colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isProcessing -> CircularProgressIndicator(color = colorScheme.primary)
                    previewBitmap != null -> {
                        // State reads for rotation and mirroring are deferred strictly to the GPU draw phase
                        Image(
                            bitmap = previewBitmap,
                            contentDescription = stringResource(R.string.desc_media_preview),
                            contentScale = ContentScale.Fit,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        rotationZ = rotationProvider().toFloat()
                                        scaleX = if (mirrorProvider()) -1f else 1f
                                    },
                        )
                        IconButton(
                            onClick = onClear,
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    .size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_clear),
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    else ->
                        Text(
                            stringResource(R.string.label_no_media_selected),
                            color = colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                FilledTonalIconButton(
                    onClick = onRotateLeft,
                    colors =
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = colorScheme.surfaceVariant,
                            contentColor = colorScheme.onSurface,
                        ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = stringResource(R.string.desc_rotate_left))
                }
                Spacer(Modifier.size(16.dp))
                FilledTonalIconButton(
                    onClick = onToggleMirror,
                    colors =
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isMirrored) colorScheme.primary else colorScheme.surfaceVariant,
                            contentColor = if (isMirrored) colorScheme.onPrimary else colorScheme.onSurface,
                        ),
                ) {
                    Icon(Icons.Default.Flip, contentDescription = stringResource(R.string.desc_mirror))
                }
                Spacer(Modifier.size(16.dp))
                FilledTonalIconButton(
                    onClick = onRotateRight,
                    colors =
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = colorScheme.surfaceVariant,
                            contentColor = colorScheme.onSurface,
                        ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = stringResource(R.string.desc_rotate_right))
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onChooseMedia,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary,
                        contentColor = colorScheme.onPrimary,
                    ),
            ) {
                Text(stringResource(R.string.btn_select_video), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun CaptureAlertsCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    UmbraOutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.label_capture_notification), fontSize = 16.sp, color = colorScheme.onSurface)
                Text(
                    stringResource(R.string.desc_capture_notification),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
internal fun PipelineMonitorCard(
    snapshot: PipelineSnapshot,
    onCopy: () -> Unit,
    onReset: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val formattedTime =
        remember(snapshot.timestamp) {
            if (snapshot.timestamp > 0L) formatter.format(Date(snapshot.timestamp)) else ""
        }

    UmbraOutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.label_pipeline_monitor),
                    fontSize = 16.sp,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCopy, enabled = snapshot.app.isNotEmpty()) {
                    Text(stringResource(R.string.action_copy), fontSize = 12.sp, color = colorScheme.primary)
                }
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.action_reset), fontSize = 12.sp, color = colorScheme.primary)
                }
            }
            if (snapshot.app.isEmpty()) {
                Text(
                    stringResource(R.string.label_no_pipeline_history),
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                Column(Modifier.padding(top = 12.dp)) {
                    Text(
                        stringResource(R.string.label_last_active_app, snapshot.app),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary,
                    )
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .background(colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                    ) {
                        Text(
                            snapshot.route,
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        stringResource(R.string.label_last_timestamp, formattedTime),
                        fontSize = 11.sp,
                        color = colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}
