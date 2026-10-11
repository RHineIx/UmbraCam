package umbra.cam.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import umbra.cam.data.Constants
import umbra.cam.ui.theme.UmbraCamTheme
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: ImageBitmap?,
)

class MainActivity :
    ComponentActivity(),
    XposedServiceHelper.OnServiceListener {
    private var isModuleActive = mutableStateOf(false)
    private var serviceFramework = mutableStateOf("")
    private var scopedApps = mutableStateOf<List<String>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        XposedServiceHelper.registerListener(this)

        setContent {
            UmbraCamTheme {
                MainScreen()
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        isModuleActive.value = true
        serviceFramework.value = service.frameworkName ?: ""
        scopedApps.value =
            try {
                service.scope.filter { it != packageName }
            } catch (e: Exception) {
                emptyList()
            }
    }

    override fun onServiceDied(service: XposedService) {
        isModuleActive.value = false
        serviceFramework.value = ""
        scopedApps.value = emptyList()
    }

    private fun checkSelfActive(): Boolean = false

    @Composable
    private fun MainScreen() {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE) }
        val coroutineScope = rememberCoroutineScope()

        var mediaPath by remember { mutableStateOf(prefs.getString(Constants.KEY_MEDIA_PATH, "") ?: "") }
        var isMirrored by remember { mutableStateOf(prefs.getBoolean(Constants.KEY_IS_MIRRORED, false)) }
        var rotationAngle by remember { mutableStateOf(prefs.getInt(Constants.KEY_ROTATION_ANGLE, 0)) }
        var enableNotif by remember { mutableStateOf(prefs.getBoolean(Constants.KEY_ENABLE_CAPTURE_NOTIF, true)) }
        var isProcessing by remember { mutableStateOf(false) }

        var pipelineApp by remember { mutableStateOf(prefs.getString(Constants.KEY_LATEST_PIPELINE_APP, "") ?: "") }
        var pipelineRoute by remember { mutableStateOf(prefs.getString(Constants.KEY_LATEST_PIPELINE_ROUTE, "") ?: "") }
        var pipelineTime by remember { mutableStateOf(prefs.getLong(Constants.KEY_LATEST_PIPELINE_TIME, 0L)) }

        var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
        var scopedAppInfos by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

        val colorScheme = MaterialTheme.colorScheme

        fun refreshPipeline() {
            pipelineApp = prefs.getString(Constants.KEY_LATEST_PIPELINE_APP, "") ?: ""
            pipelineRoute = prefs.getString(Constants.KEY_LATEST_PIPELINE_ROUTE, "") ?: ""
            pipelineTime = prefs.getLong(Constants.KEY_LATEST_PIPELINE_TIME, 0L)
        }

        LaunchedEffect(mediaPath) {
            previewBitmap = loadPreviewBitmap(mediaPath)
        }

        // Fetch App Icons and Names asynchronously
        LaunchedEffect(scopedApps.value) {
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val infos =
                    scopedApps.value.map { pkg ->
                        try {
                            val info = pm.getApplicationInfo(pkg, 0)
                            val name = pm.getApplicationLabel(info).toString()
                            val iconBmp = pm.getApplicationIcon(info).toBitmap().asImageBitmap()
                            AppInfo(pkg, name, iconBmp)
                        } catch (e: Exception) {
                            AppInfo(pkg, pkg, null)
                        }
                    }
                scopedAppInfos = infos
            }
        }

        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(2000)
                refreshPipeline()
            }
        }

        val notifPermissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (!isGranted) Toast.makeText(context, "Notification permission required", Toast.LENGTH_SHORT).show()
            }

        val filePickerLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri ->
                        isProcessing = true
                        coroutineScope.launch {
                            val newPath = processMediaSelection(context, uri)
                            if (newPath != null) {
                                mediaPath = newPath
                                isMirrored = false
                                rotationAngle = 0
                                prefs
                                    .edit()
                                    .putString(Constants.KEY_MEDIA_PATH, newPath)
                                    .putBoolean(Constants.KEY_IS_MIRRORED, false)
                                    .putInt(Constants.KEY_ROTATION_ANGLE, 0)
                                    .apply()
                            }
                            isProcessing = false
                        }
                    }
                }
            }

        Scaffold(
            containerColor = colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) { paddingValues ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 24.dp)
                        .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                // Title
                Text(
                    text =
                        buildAnnotatedString {
                            withStyle(style = SpanStyle(color = colorScheme.primary)) { append("U") }
                            append("mbraCam")
                        },
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onBackground,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Module Status Card
                Card(
                    modifier = Modifier.wrapContentWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    border = BorderStroke(1.dp, if (isModuleActive.value) colorScheme.primary else colorScheme.outlineVariant),
                ) {
                    Text(
                        text = if (isModuleActive.value) "Module active ${if (serviceFramework.value.isNotBlank()) "(${serviceFramework.value})" else ""}" else "Module inactive",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        fontSize = 14.sp,
                        color = if (isModuleActive.value) colorScheme.primary else colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Scoped Apps Card
                if (scopedAppInfos.isNotEmpty()) {
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 24.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                        border = BorderStroke(1.dp, colorScheme.outlineVariant),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Active target apps",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(scopedAppInfos) { app ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.width(64.dp),
                                    ) {
                                        if (app.icon != null) {
                                            Image(
                                                bitmap = app.icon,
                                                contentDescription = app.appName,
                                                modifier =
                                                    Modifier
                                                        .size(48.dp)
                                                        .clip(RoundedCornerShape(12.dp)),
                                            )
                                        } else {
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .size(48.dp)
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .background(colorScheme.surfaceVariant),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    Icons.Default.Android,
                                                    contentDescription = null,
                                                    tint = colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = app.appName,
                                            fontSize = 11.sp,
                                            color = colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Settings Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                    border = BorderStroke(1.dp, colorScheme.outlineVariant),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Media source",
                            fontSize = 14.sp,
                            color = colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Preview Box (1:1 Ratio)
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(color = colorScheme.primary)
                            } else if (previewBitmap != null) {
                                Image(
                                    bitmap = previewBitmap!!,
                                    contentDescription = "Media Preview",
                                    contentScale = ContentScale.Crop,
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                rotationZ = rotationAngle.toFloat()
                                                scaleX = if (isMirrored) -1f else 1f
                                            },
                                )
                                IconButton(
                                    onClick = {
                                        mediaPath = ""
                                        prefs.edit().putString(Constants.KEY_MEDIA_PATH, "").apply()
                                    },
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp)
                                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                            .size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                Text("No media selected", color = colorScheme.onSurfaceVariant, fontSize = 14.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Transformation Buttons Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            FilledTonalIconButton(
                                onClick = {
                                    rotationAngle = (rotationAngle - 90 + 360) % 360
                                    prefs.edit().putInt(Constants.KEY_ROTATION_ANGLE, rotationAngle).apply()
                                },
                                colors =
                                    IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = colorScheme.surfaceVariant,
                                        contentColor = colorScheme.onSurface,
                                    ),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "Rotate Left")
                            }
                            Spacer(modifier = Modifier.width(16.dp))

                            FilledTonalIconButton(
                                onClick = {
                                    isMirrored = !isMirrored
                                    prefs.edit().putBoolean(Constants.KEY_IS_MIRRORED, isMirrored).apply()
                                },
                                colors =
                                    IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = if (isMirrored) colorScheme.primary else colorScheme.surfaceVariant,
                                        contentColor = if (isMirrored) colorScheme.onPrimary else colorScheme.onSurface,
                                    ),
                            ) {
                                Icon(Icons.Default.Flip, contentDescription = "Mirror")
                            }
                            Spacer(modifier = Modifier.width(16.dp))

                            FilledTonalIconButton(
                                onClick = {
                                    rotationAngle = (rotationAngle + 90) % 360
                                    prefs.edit().putInt(Constants.KEY_ROTATION_ANGLE, rotationAngle).apply()
                                },
                                colors =
                                    IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = colorScheme.surfaceVariant,
                                        contentColor = colorScheme.onSurface,
                                    ),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate Right")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Select Media Button
                        Button(
                            onClick = {
                                val intent =
                                    android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                        type = "*/*"
                                        putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                                    }
                                filePickerLauncher.launch(intent)
                            },
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
                            Text("Choose image or video", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Capture Alerts Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                    border = BorderStroke(1.dp, colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Capture alerts", fontSize = 16.sp, color = colorScheme.onSurface)
                            Text("Show a notification after a capture", fontSize = 12.sp, color = colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = enableNotif,
                            onCheckedChange = {
                                enableNotif = it
                                prefs.edit().putBoolean(Constants.KEY_ENABLE_CAPTURE_NOTIF, it).apply()
                                if (it && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Pipeline Monitor Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                    border = BorderStroke(1.dp, colorScheme.outlineVariant),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Pipeline monitor",
                                fontSize = 16.sp,
                                color = colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                if (pipelineApp.isNotEmpty()) {
                                    val clip = ClipData.newPlainText("Pipeline", "Target: $pipelineApp\nRoute: $pipelineRoute")
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text("Copy", fontSize = 12.sp, color = colorScheme.primary)
                            }
                            TextButton(onClick = {
                                prefs
                                    .edit()
                                    .remove(Constants.KEY_LATEST_PIPELINE_APP)
                                    .remove(Constants.KEY_LATEST_PIPELINE_ROUTE)
                                    .remove(Constants.KEY_LATEST_PIPELINE_TIME)
                                    .apply()
                                refreshPipeline()
                            }) {
                                Text("Reset", fontSize = 12.sp, color = colorScheme.primary)
                            }
                        }

                        if (pipelineApp.isEmpty()) {
                            Text(
                                text = "No hook activity yet",
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        } else {
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                Text(
                                    text = "Target: $pipelineApp",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.primary,
                                )

                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(colorScheme.surfaceVariant)
                                            .padding(12.dp),
                                ) {
                                    Text(
                                        text = pipelineRoute,
                                        fontSize = 12.sp,
                                        color = colorScheme.onSurfaceVariant,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    )
                                }

                                val formatted =
                                    if (pipelineTime > 0) {
                                        SimpleDateFormat(
                                            "HH:mm:ss",
                                            Locale.getDefault(),
                                        ).format(Date(pipelineTime))
                                    } else {
                                        ""
                                    }
                                Text(text = "Time: $formatted", fontSize = 11.sp, color = colorScheme.outlineVariant)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Bottom Note
                Text(
                    text = "Restart target apps after changing the media source.",
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 32.dp),
                )
            }
        }
    }

    private suspend fun loadPreviewBitmap(path: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (path.isEmpty() || !File(path).exists()) return@withContext null
            try {
                if (path.endsWith(".mp4", ignoreCase = true)) {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(path)
                    val bmp = retriever.getFrameAtTime(1000000)
                    retriever.release()
                    bmp?.asImageBitmap()
                } else {
                    BitmapFactory.decodeFile(path)?.asImageBitmap()
                }
            } catch (e: Exception) {
                null
            }
        }

    private suspend fun processMediaSelection(
        context: Context,
        uri: Uri,
    ): String? =
        withContext(Dispatchers.IO) {
            try {
                var mimeType = context.contentResolver.getType(uri)
                if (mimeType == null) {
                    val extStr = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                    mimeType =
                        android.webkit.MimeTypeMap
                            .getSingleton()
                            .getMimeTypeFromExtension(extStr?.lowercase(Locale.ROOT))
                            ?: "application/octet-stream"
                }

                val isImage = mimeType.startsWith("image/")
                val isVideo = mimeType.startsWith("video/")

                if (!isImage && !isVideo) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Unsupported file type", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext null
                }

                context.filesDir.listFiles { _, name -> name.startsWith("virtual.") }?.forEach { it.delete() }
                val extension =
                    android.webkit.MimeTypeMap
                        .getSingleton()
                        .getExtensionFromMimeType(mimeType) ?: "bin"

                if (isImage) {
                    val internalImage = File(context.filesDir, "virtual.$extension")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(internalImage).use { output -> input.copyTo(output) }
                    }

                    val outputVideo = File(context.filesDir, "virtual.mp4")

                    val success =
                        suspendCancellableCoroutine<Boolean> { continuation ->
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    umbra.cam.utils.MediaConverter.convertImageToMp4(
                                        context,
                                        Uri.fromFile(internalImage),
                                        outputVideo,
                                    ) { result ->
                                        if (continuation.isActive) continuation.resume(result)
                                    }
                                } catch (e: Exception) {
                                    if (continuation.isActive) continuation.resume(false)
                                }
                            }
                        }

                    internalImage.delete()

                    if (!success) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Image to Video conversion failed", Toast.LENGTH_SHORT).show()
                        }
                        return@withContext null
                    }
                    return@withContext outputVideo.absolutePath
                } else {
                    val internalFile = File(context.filesDir, "virtual.$extension")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(internalFile).use { output -> input.copyTo(output) }
                    }
                    return@withContext internalFile.absolutePath
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error processing media file", Toast.LENGTH_SHORT).show()
                }
                return@withContext null
            }
        }
}
