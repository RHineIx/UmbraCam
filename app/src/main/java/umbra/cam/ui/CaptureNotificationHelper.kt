package umbra.cam.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import umbra.cam.R
import umbra.cam.data.Constants.CAPTURE_NOTIFICATION_CHANNEL_ID
import umbra.cam.data.Constants.CAPTURE_NOTIFICATION_ID

object CaptureNotificationHelper {
    fun showCaptureNotification(
        context: Context,
        packageName: String,
        route: String,
        width: Int,
        height: Int,
        thumbnailBytes: ByteArray?,
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CAPTURE_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.capture_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.capture_notification_description)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 100, 150)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val appName = runCatching {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName.substringAfterLast('.'))

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val thumbnailBitmap = thumbnailBytes?.takeIf { it.isNotEmpty() }?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
        val resolutionText = if (width > 0 && height > 0) {
            context.getString(R.string.notification_resolution, width, height)
        } else {
            context.getString(R.string.notification_resolution_auto)
        }
        val summary = context.getString(R.string.notification_summary, appName, packageName, route, resolutionText)

        val builder = NotificationCompat.Builder(context, CAPTURE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_title, appName))
            .setContentText(context.getString(R.string.notification_pipeline, route))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)

        if (thumbnailBitmap != null) builder.setLargeIcon(thumbnailBitmap)
        notificationManager.notify(CAPTURE_NOTIFICATION_ID, builder.build())
    }
}
