package umbra.cam.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import umbra.cam.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun PipelineMonitorCard(
    app: String,
    route: String,
    timestamp: Long,
    onCopy: () -> Unit,
    onReset: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.label_pipeline_monitor),
                    fontSize = 16.sp,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCopy, enabled = app.isNotEmpty()) {
                    Text(stringResource(R.string.action_copy), fontSize = 12.sp, color = colorScheme.primary)
                }
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.action_reset), fontSize = 12.sp, color = colorScheme.primary)
                }
            }
            if (app.isEmpty()) {
                Text(
                    stringResource(R.string.label_no_pipeline_history),
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                Column(Modifier.padding(top = 12.dp)) {
                    Text(
                        stringResource(R.string.label_last_active_app, app),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary,
                    )
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            .clip(RoundedCornerShape(8.dp)).background(colorScheme.surfaceVariant).padding(12.dp),
                    ) {
                        Text(
                            route,
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    val formattedTime = if (timestamp > 0L) {
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
                    } else {
                        ""
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
