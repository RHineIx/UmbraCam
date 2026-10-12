package umbra.cam.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import umbra.cam.R

@Composable
internal fun ScopeAppsCard(
    scopedApps: List<String>,
    appInfos: List<InstalledAppInfo>,
    isModuleActive: Boolean,
    onManageApps: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(appInfos, key = { it.packageName }, contentType = { "scoped-app" }) { app ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                            AppIcon(
                                packageName = app.packageName,
                                contentDescription = app.appName,
                                modifier = Modifier.width(48.dp).height(48.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                app.appName,
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
}
