package umbra.cam.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import umbra.cam.R
import umbra.cam.data.Constants

internal data class PipelineSnapshot(val app: String, val route: String, val timestamp: Long)

@Composable
internal fun MainScreen(
    isModuleActive: Boolean,
    serviceFramework: String,
    scopedApps: List<String>,
    onApplyScopeSelection: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val preferences = remember(appContext) {
        appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val coroutineScope = rememberCoroutineScope()

    var mediaPath by remember { mutableStateOf(preferences.getString(Constants.KEY_MEDIA_PATH, "").orEmpty()) }
    var isMirrored by remember { mutableStateOf(preferences.getBoolean(Constants.KEY_IS_MIRRORED, false)) }
    var rotationAngle by remember { mutableStateOf(preferences.getInt(Constants.KEY_ROTATION_ANGLE, 0)) }
    var enableNotifications by remember { mutableStateOf(preferences.getBoolean(Constants.KEY_ENABLE_CAPTURE_NOTIF, true)) }
    var isProcessing by remember { mutableStateOf(false) }
    var pipeline by remember {
        mutableStateOf(
            PipelineSnapshot(
                preferences.getString(Constants.KEY_LATEST_PIPELINE_APP, "").orEmpty(),
                preferences.getString(Constants.KEY_LATEST_PIPELINE_ROUTE, "").orEmpty(),
                preferences.getLong(Constants.KEY_LATEST_PIPELINE_TIME, 0L),
            ),
        )
    }
    var previewBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var scopedAppInfos by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var installedAppInfos by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var installedAppsLoaded by remember { mutableStateOf(false) }
    var isLoadingInstalledApps by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var appSearchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) Toast.makeText(context, R.string.toast_notification_permission_required, Toast.LENGTH_SHORT).show()
    }
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                isProcessing = true
                coroutineScope.launch {
                    try {
                        when (val importResult = processMediaSelection(appContext, uri)) {
                            is MediaImportResult.Success -> {
                                mediaPath = importResult.path
                                isMirrored = false
                                rotationAngle = 0
                                preferences.edit {
                                    putString(Constants.KEY_MEDIA_PATH, importResult.path)
                                    putBoolean(Constants.KEY_IS_MIRRORED, false)
                                    putInt(Constants.KEY_ROTATION_ANGLE, 0)
                                }
                            }
                            is MediaImportResult.Failure -> {
                                Toast.makeText(context, importResult.messageResource, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } finally {
                        isProcessing = false
                    }
                }
            }
        }
    }

    LaunchedEffect(mediaPath) { previewBitmap = loadPreviewBitmap(mediaPath) }
    LaunchedEffect(scopedApps) { scopedAppInfos = InstalledAppsRepository.loadScopedApps(appContext, scopedApps) }
    LaunchedEffect(showAppPicker) {
        if (showAppPicker && !installedAppsLoaded) {
            isLoadingInstalledApps = true
            try {
                installedAppInfos = InstalledAppsRepository.loadInstalledApps(appContext)
                installedAppsLoaded = true
            } finally {
                isLoadingInstalledApps = false
            }
        }
    }

    val pipelineUri = remember { Constants.PIPELINE_CONTENT_URI.toUri() }
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    DisposableEffect(appContext, pipelineUri, lifecycle) {
        var refreshJob: Job? = null
        var observerRegistered = false
        fun refreshPipeline() {
            refreshJob?.cancel()
            refreshJob = coroutineScope.launch {
                delay(100)
                val latest = withContext(Dispatchers.IO) { readPipelineSnapshot(appContext, preferences) }
                pipeline = latest
            }
        }
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = refreshPipeline()
        }
        fun registerContentObserver() {
            if (!observerRegistered) {
                appContext.contentResolver.registerContentObserver(pipelineUri, false, contentObserver)
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
        val lifecycleObserver = LifecycleEventObserver { _, event ->
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

    val colorScheme = MaterialTheme.colorScheme
    val appTitle = stringResource(R.string.title_manager)
    Scaffold(containerColor = colorScheme.background, modifier = Modifier.fillMaxSize()) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "app-title", contentType = "header") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = colorScheme.primary)) { append(appTitle.take(1)) }
                            append(appTitle.drop(1))
                        },
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(12.dp))
                    ModuleStatusCard(isModuleActive, serviceFramework)
                }
            }
            item(key = "scope-apps", contentType = "card") {
                ScopeAppsCard(
                    scopedApps = scopedApps,
                    appInfos = scopedAppInfos,
                    isModuleActive = isModuleActive,
                    onManageApps = {
                        selectedPackages = scopedApps.toSet()
                        appSearchQuery = ""
                        showSystemApps = installedAppInfos.any { it.isSystemApp && it.packageName in scopedApps }
                        showAppPicker = true
                    },
                )
            }
            item(key = "media-source", contentType = "card") {
                MediaSourceCard(
                    previewBitmap = previewBitmap,
                    isProcessing = isProcessing,
                    isMirrored = isMirrored,
                    rotationAngle = rotationAngle,
                    onClear = {
                        mediaPath = ""
                        preferences.edit { putString(Constants.KEY_MEDIA_PATH, "") }
                    },
                    onRotateLeft = {
                        rotationAngle = (rotationAngle - 90 + 360) % 360
                        preferences.edit { putInt(Constants.KEY_ROTATION_ANGLE, rotationAngle) }
                    },
                    onToggleMirror = {
                        isMirrored = !isMirrored
                        preferences.edit { putBoolean(Constants.KEY_IS_MIRRORED, isMirrored) }
                    },
                    onRotateRight = {
                        rotationAngle = (rotationAngle + 90) % 360
                        preferences.edit { putInt(Constants.KEY_ROTATION_ANGLE, rotationAngle) }
                    },
                    onChooseMedia = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                        }
                        mediaPickerLauncher.launch(intent)
                    },
                )
            }
            item(key = "capture-alerts", contentType = "card") {
                CaptureAlertsCard(
                    enabled = enableNotifications,
                    onEnabledChange = { enabled ->
                        enableNotifications = enabled
                        preferences.edit { putBoolean(Constants.KEY_ENABLE_CAPTURE_NOTIF, enabled) }
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
            }
            item(key = "pipeline-monitor", contentType = "card") {
                PipelineMonitorCard(
                    app = pipeline.app,
                    route = pipeline.route,
                    timestamp = pipeline.timestamp,
                    onCopy = {
                        if (pipeline.app.isNotEmpty()) {
                            val clipboardText = context.getString(R.string.pipeline_clipboard, pipeline.app, pipeline.route)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.label_pipeline_monitor), clipboardText))
                            Toast.makeText(context, R.string.toast_copied, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onReset = {
                        appContext.contentResolver.call(pipelineUri, Constants.METHOD_CLEAR_PIPELINE, null, null)
                        pipeline = PipelineSnapshot("", "", 0L)
                    },
                )
            }
            item(key = "restart-note", contentType = "footer") {
                Text(
                    text = stringResource(R.string.note_restart),
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showAppPicker) {
        TargetAppsSheet(
            apps = installedAppInfos,
            query = appSearchQuery,
            onQueryChange = { appSearchQuery = it },
            selectedPackages = selectedPackages,
            showSystemApps = showSystemApps,
            onShowSystemAppsChange = { showSystemApps = it },
            onTogglePackage = { packageName ->
                selectedPackages = if (packageName in selectedPackages) selectedPackages - packageName
                else selectedPackages + packageName
            },
            onDismiss = { showAppPicker = false },
            onApply = {
                onApplyScopeSelection(selectedPackages)
                showAppPicker = false
            },
            isLoading = isLoadingInstalledApps,
        )
    }
}

private fun readPipelineSnapshot(context: Context, preferences: android.content.SharedPreferences): PipelineSnapshot {
    val bundle = runCatching {
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
private fun ModuleStatusCard(isActive: Boolean, framework: String) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, if (isActive) colorScheme.primary else colorScheme.outlineVariant),
    ) {
        Text(
            text = when {
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
