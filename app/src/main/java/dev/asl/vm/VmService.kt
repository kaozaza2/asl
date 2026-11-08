package dev.asl.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.asl.core.Asl
import dev.asl.core.VmState
import dev.asl.core.VmStatus
import dev.asl.ui.TabRegistry
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while the VM runs; stops itself once it is down. With
 * `keepAwake` set it also holds a partial wake lock while the VM is running, so the guest keeps
 * running with the screen off. Because the running foreground service keeps this process alive,
 * task removal from recents does not end anything: the VM and the [TabRegistry] tab sessions keep
 * running until they exit or are closed, and reopening the app reattaches to them.
 */
class VmService : LifecycleService() {
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "asl:vm").apply { setReferenceCounted(false) }
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Linux VM", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification(Asl.vm.status.value, 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        lifecycleScope.launch {
            combine(Asl.vm.status, Asl.vm.forwardedPorts, TabRegistry.runningSessions) { status, ports, sessions ->
                Triple(status, ports.count { it.active >= 0 }, sessions)
            }.collect { (status, ports, sessions) ->
                if (status.state == VmState.Stopped || status.state == VmState.Error) stopSelf()
                else notifications.notify(NOTIFICATION_ID, notification(status, ports, sessions))
            }
        }
        lifecycleScope.launch {
            // Held only while the guest is up, so a VM stuck starting or stopping can't keep the CPU awake.
            combine(Asl.settings.settings.map { it.asl.keepAwake }, Asl.vm.status.map { it.state == VmState.Running }) { keep, running -> keep && running }
                .distinctUntilChanged()
                .collect { hold -> if (hold) wakeLock.acquire() else if (wakeLock.isHeld) wakeLock.release() }
        }
    }

    override fun onDestroy() {
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_SHUTDOWN) lifecycleScope.launch { Asl.vm.shutdown() }
        return START_NOT_STICKY
    }

    private fun notification(status: VmStatus, ports: Int, sessions: Int): Notification {
        val shutdown = PendingIntent.getService(
            this, 0, Intent(this, VmService::class.java).setAction(ACTION_SHUTDOWN), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(dev.asl.R.drawable.ic_stat_asl)
            .setContentTitle("Linux VM: ${status.state.name.lowercase()}")
            .setContentText(
                status.message ?: if (sessions > 0) {
                    "$sessions running session${if (sessions == 1) "" else "s"}"
                } else {
                    "$ports forwarded port${if (ports == 1) "" else "s"}"
                },
            )
            .setOngoing(true)
            .setContentIntent(
                packageManager.getLaunchIntentForPackage(packageName)
                    ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) },
            )
            .addAction(Notification.Action.Builder(null, "Shut down", shutdown).build())
            .build()
    }

    private companion object {
        const val CHANNEL = VM_NOTIFICATION_CHANNEL
        const val NOTIFICATION_ID = 1
        const val ACTION_SHUTDOWN = "dev.asl.action.SHUTDOWN_VM"
    }
}
