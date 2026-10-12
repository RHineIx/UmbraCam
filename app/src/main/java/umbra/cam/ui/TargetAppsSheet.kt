package umbra.cam.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import umbra.cam.R

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
) {
    val colorScheme = MaterialTheme.colorScheme
    val filteredApps = remember(apps, query, showSystemApps) {
        filterInstalledApps(apps, query, showSystemApps)
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
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
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
            if (filteredApps.isEmpty()) {
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
                    items(filteredApps, key = { it.packageName }) { app ->
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
                                if (app.icon != null) {
                                    Image(
                                        bitmap = app.icon,
                                        contentDescription = app.appName,
                                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)),
                                    )
                                } else {
                                    Icon(Icons.Default.Android, contentDescription = null)
                                }
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
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 24.dp).height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(stringResource(R.string.action_apply_scope, selectedPackages.size))
            }
        }
    }
}
