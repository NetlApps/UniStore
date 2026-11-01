package ru.updatehub.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ru.updatehub.app.MainActivity
import ru.updatehub.app.R
import ru.updatehub.app.data.AppUpdateResult

object Notifications {
    private const val CHANNEL_ID = "updatehub_updates"
    private const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Обновления приложений",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Уведомления о новых версиях отслеживаемых приложений"
            enableLights(true)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun showUpdates(
        context: Context,
        updates: List<AppUpdateResult>,
        labels: Map<String, String>
    ) {
        if (updates.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val names = updates.take(6).map { labels[it.packageName] ?: it.packageName }
        val summary = names.joinToString(", ")
        val title = when {
            updates.size == 1 -> "Доступно обновление"
            else -> "Доступно обновлений: ${updates.size}"
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setColor(0xFF5B5BD6.toInt())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Fallback for the system installer's "confirm installation" screen when
     * Android does not allow a background receiver to start that activity
     * directly. The notification opens the same confirmation Intent.
     */
    fun showInstallConfirmation(
        context: Context,
        confirmation: Intent,
        label: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context,
            INSTALL_NOTIFICATION_ID,
            confirmation,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Подтвердите обновление")
            .setContentText("Нажмите, чтобы установить $label")
            .setColor(0xFF3D7BFF.toInt())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(INSTALL_NOTIFICATION_ID, notification)
        }
    }

    private const val INSTALL_NOTIFICATION_ID = 1002
}
