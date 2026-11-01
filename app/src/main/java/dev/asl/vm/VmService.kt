package dev.asl.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.asl.core.Asl
import dev.asl.core.VmState
import dev.asl.core.VmStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps the process in the foreground while the VM runs; stops itself once it is down. */
class VmService : LifecycleService() {
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Linux VM", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification(Asl.vm.status.value, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        lifecycleScope.launch {
            combine(Asl.vm.status, Asl.vm.forwardedPorts) { status, ports -> status to ports.count { it.active >= 0 } }
                .collect { (status, ports) ->
                    if (status.state == VmState.Stopped || status.state == VmState.Error) stopSelf()
                    else notifications.notify(NOTIFICATION_ID, notification(status, ports))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_SHUTDOWN) lifecycleScope.launch { Asl.vm.shutdown() }
        return START_NOT_STICKY
    }

    private fun notification(status: VmStatus, ports: Int): Notification {
        val shutdown = PendingIntent.getService(
            this, 0, Intent(this, VmService::class.java).setAction(ACTION_SHUTDOWN), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(dev.asl.R.drawable.ic_stat_asl)
            .setContentTitle("Linux VM: ${status.state.name.lowercase()}")
            .setContentText(status.message ?: "$ports forwarded port${if (ports == 1) "" else "s"}")
            .setOngoing(true)
            .setContentIntent(
                packageManager.getLaunchIntentForPackage(packageName)
                    ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) },
            )
            .addAction(Notification.Action.Builder(null, "Shut down", shutdown).build())
            .build()
    }

    private companion object {
        const val CHANNEL = "vm"
        const val NOTIFICATION_ID = 1
        const val ACTION_SHUTDOWN = "dev.asl.action.SHUTDOWN_VM"
    }
}
