package com.simplexray.an.service.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.simplexray.an.R
import com.simplexray.an.activity.MainActivity
import com.simplexray.an.feature.dashboard.model.TrafficState
import com.simplexray.an.feature.subscriptions.data.subscriptionBytes as formatTrafficBytes
import com.simplexray.an.service.TProxyService

internal class ServiceNotifications(private val service: Service) {
    private var builder: NotificationCompat.Builder? = null
    private var lastContent: Pair<String, String>? = null
    private var serverName: String? = null
    private var subscriptionDomain: String? = null

    @Synchronized
    @Suppress("SameParameterValue")
    fun show(channelName: String) {
        val intent = Intent(service, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(
                service,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val disconnectIntent =
            PendingIntent.getService(
                service,
                1,
                Intent(service, TProxyService::class.java)
                    .setAction(TProxyService.ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(service, channelName)
                .setContentTitle(serverName ?: service.getString(R.string.app_name))
                .setContentText("Подключение…")
                .setSubText(null)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.cancel, "Отключиться", disconnectIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        builder = notification
        lastContent = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(1, notification.build())
        } else {
            service.startForeground(
                1,
                notification.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }
    }

    @Synchronized
    fun setServerName(name: String, domain: String?) {
        serverName = name
        subscriptionDomain = domain
        builder?.setContentTitle(name)
        lastContent = null
    }

    fun canUpdate(): Boolean = NotificationManagerCompat.from(service).areNotificationsEnabled()

    fun updateTraffic(traffic: TrafficState?, rates: TrafficState) {
        if (traffic == null) {
            update(null, "Статистика временно недоступна")
            return
        }
        update(
            "↓ ${formatTrafficBytes(rates.downlink)}/с  ↑ ${formatTrafficBytes(rates.uplink)}/с",
            "Получено ${formatTrafficBytes(traffic.downlink)} · Отправлено ${formatTrafficBytes(traffic.uplink)}",
        )
    }

    fun showStatisticsDisabled() {
        update(null, "Подключено · Статистика выключена")
    }

    @Synchronized
    private fun update(speed: String?, text: String) {
        val notification = builder ?: return
        val title = serverName ?: service.getString(R.string.app_name)
        val lines = listOfNotNull(subscriptionDomain, speed, text)
        val expanded = lines.joinToString("\n")
        val content = title to expanded
        if (!canUpdate() || lastContent == content) return
        notification
            .setContentTitle(title)
            .setContentText(lines.joinToString(" · "))
            .setSubText(null)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(expanded))
        try {
            service
                .getSystemService(NotificationManager::class.java)
                .notify(1, notification.build())
            lastContent = content
        } catch (_: SecurityException) {}
    }

    @Suppress("SameParameterValue")
    fun createChannel(channelName: String) {
        val notificationManager =
            service.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        val name: CharSequence = service.getString(R.string.app_name)
        val channel = NotificationChannel(channelName, name, NotificationManager.IMPORTANCE_DEFAULT)
        notificationManager.createNotificationChannel(channel)
    }
}
