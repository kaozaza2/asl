package dev.asl.vm

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import dev.asl.R

/** Granted only over adb (`pm grant`); see README. */
val VmPermissions = listOf("android.permission.MANAGE_VIRTUAL_MACHINE", "android.permission.USE_CUSTOM_VIRTUAL_MACHINE")

/** The notification channel of the VM's foreground-service notification. */
const val VM_NOTIFICATION_CHANNEL = "vm"

fun Context.missingVmPermissions(): List<String> =
    VmPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

/** The `adb shell pm grant` lines that grant [permissions], one per line. */
fun Context.vmGrantCommands(permissions: List<String> = VmPermissions): String =
    permissions.joinToString("\n") { "adb shell pm grant $packageName $it" }

/** Whether Android exempts ASL from Doze and app standby ("Unrestricted" battery usage). */
fun Context.ignoresBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

fun Context.notificationPermissionGranted(): Boolean =
    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Whether the VM notification can actually show: notifications are on for the app and the VM
 * channel isn't blocked. A channel that doesn't exist yet is created unblocked when the VM starts.
 */
fun Context.notificationsEnabled(): Boolean {
    val manager = getSystemService(NotificationManager::class.java)
    return manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(VM_NOTIFICATION_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
}

/** The system dialog asking to let ASL run in the background without battery limits. */
fun Context.batteryExemptionIntent(): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))

/** ASL's page in system settings, where an exemption can be revoked again. */
fun Context.appDetailsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

fun Context.notificationSettingsIntent(): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

fun Context.allFilesAccessIntent(): Intent =
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null))

/** Opens a system settings screen, telling the user instead of crashing when the device has none. */
fun Context.openSettingsScreen(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w("asl", "No activity for ${intent.action}", e)
        Toast.makeText(this, R.string.settings_screen_missing, Toast.LENGTH_LONG).show()
    }
}
