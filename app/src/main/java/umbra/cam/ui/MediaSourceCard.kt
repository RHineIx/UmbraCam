package umbra.cam.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import umbra.cam.R

@Composable
internal fun MediaSourceCard(
    previewBitmap: ImageBitmap?,
    isProcessing: Boolean,
    isMirrored: Boolean,
    rotationAngle: Int,
    onClear: () -> Unit,
    onRotateLeft: () -> Unit,
    onToggleMirror: () -> Unit,
    onRotateRight: () -> Unit,
    onChooseMedia: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.label_media_preview), fontSize = 14.sp, color = colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .background(colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isProcessing -> CircularProgressIndicator(color = colorScheme.primary)
                    previewBitmap != null -> {
                        Image(
                            bitmap = previewBitmap,
                            contentDescription = stringResource(R.string.desc_media_preview),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                rotationZ = rotationAngle.toFloat()
                                scaleX = if (isMirrored) -1f else 1f
                            },
                        )
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape).size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_clear),
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    else -> Text(
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
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = colorScheme.surfaceVariant,
                        contentColor = colorScheme.onSurface,
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = stringResource(R.string.desc_rotate_left))
                }
                Spacer(Modifier.size(16.dp))
                FilledTonalIconButton(
                    onClick = onToggleMirror,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isMirrored) colorScheme.primary else colorScheme.surfaceVariant,
                        contentColor = if (isMirrored) colorScheme.onPrimary else colorScheme.onSurface,
                    ),
                ) {
                    Icon(Icons.Default.Flip, contentDescription = stringResource(R.string.desc_mirror))
                }
                Spacer(Modifier.size(16.dp))
                FilledTonalIconButton(
                    onClick = onRotateRight,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
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
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary,
                    contentColor = colorScheme.onPrimary,
                ),
            ) {
                Text(stringResource(R.string.btn_select_video), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
