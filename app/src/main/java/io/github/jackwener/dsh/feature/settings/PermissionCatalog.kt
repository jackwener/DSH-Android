package io.github.jackwener.dsh.feature.settings

import androidx.annotation.StringRes

import io.github.jackwener.dsh.core.capability.tools.hasAllFilesAccess
import io.github.jackwener.dsh.core.trigger.canExact
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import io.github.jackwener.dsh.core.designsystem.findActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.service.DshAccessibilityService
import io.github.jackwener.dsh.service.DshNotificationListener

/** One grantable thing: how to check it, how to ask for it, and which capabilities need it. */
data class PermissionItem(
    val id: String,
    @StringRes val titleRes: Int, @StringRes val whyRes: Int,
    val groups: Set<ToolGroup>,
    /** Runtime permissions requested in-app; null when granted through a Settings screen. */
    val runtime: List<String>? = null,
    val restrictedSetting: Boolean = false,
    val check: (Context) -> Boolean,
    val intent: (Context) -> Intent? = { null },
)

object PermissionCatalog {
    private fun pkgUri(c: Context) = Uri.parse("package:${c.packageName}")
    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun appDetails(c: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(c))

    /**
     * After a denied request: true when none of [perms] can be asked again ("don't ask again", or
     * the second denial on Android 11+), so only the app's settings page can grant them.
     */
    fun permanentlyDenied(c: Context, perms: Collection<String>): Boolean {
        val act = c.findActivity() ?: return false
        return perms.isNotEmpty() && perms.none { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(act, it) } &&
            perms.none { androidx.core.content.ContextCompat.checkSelfPermission(c, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    val all: List<PermissionItem> = listOf(
        PermissionItem("notifications", R.string.perm_notifications, R.string.perm_notifications_why,
            ToolGroup.entries.toSet(), runtime = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else null,
            check = { Build.VERSION.SDK_INT < 33 || granted(it, Manifest.permission.POST_NOTIFICATIONS) }),
        PermissionItem("battery", R.string.perm_battery, R.string.perm_battery_why,
            ToolGroup.entries.toSet(),
            check = { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
            intent = { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(it)) }),
        PermissionItem("files", R.string.perm_files, R.string.perm_files_why,
            setOf(ToolGroup.Files),
            // Android 10 has no "All files access"; legacy storage + WRITE_EXTERNAL_STORAGE covers it there.
            runtime = if (Build.VERSION.SDK_INT < 30) listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE) else null,
            check = { hasAllFilesAccess(it) },
            intent = { if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri(it)) else null }),
        PermissionItem("accessibility", R.string.perm_accessibility, R.string.perm_accessibility_why,
            setOf(ToolGroup.Screen, ToolGroup.Apps), restrictedSetting = true,
            check = { DshAccessibilityService.instance != null || a11yEnabled(it) },
            intent = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }),
        PermissionItem("notif_access", R.string.perm_notif_access, R.string.perm_notif_access_why,
            setOf(ToolGroup.Notifications, ToolGroup.Media, ToolGroup.Rules), restrictedSetting = true,
            check = { DshNotificationListener.instance != null || listenerEnabled(it) },
            intent = { c -> if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(c, DshNotificationListener::class.java).flattenToString())
                else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }),
        PermissionItem("exact_alarm", R.string.perm_exact_alarm, R.string.perm_exact_alarm_why,
            setOf(ToolGroup.Rules),
            check = { it.getSystemService(AlarmManager::class.java).canExact() },
            intent = { if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(it)) else null }),
        PermissionItem("dnd", R.string.perm_dnd, R.string.perm_dnd_why,
            setOf(ToolGroup.System),
            check = { it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
            intent = { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }),
        PermissionItem("write_settings", R.string.perm_write_settings, R.string.perm_write_settings_why,
            setOf(ToolGroup.System),
            check = { Settings.System.canWrite(it) },
            intent = { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(it)) }),
        PermissionItem("location", R.string.perm_location, R.string.perm_location_why,
            setOf(ToolGroup.Rules), runtime = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            check = { granted(it, Manifest.permission.ACCESS_FINE_LOCATION) }),
        PermissionItem("bg_location", R.string.perm_bg_location, R.string.perm_bg_location_why,
            setOf(ToolGroup.Rules),
            check = { granted(it, Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            intent = { appDetails(it) }),
        PermissionItem("bluetooth", R.string.perm_bluetooth, R.string.perm_bluetooth_why,
            setOf(ToolGroup.Rules), runtime = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else null,
            check = { Build.VERSION.SDK_INT < 31 || granted(it, Manifest.permission.BLUETOOTH_CONNECT) }),
        PermissionItem("comms", R.string.perm_comms, R.string.perm_comms_why,
            setOf(ToolGroup.Comms), runtime = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            restrictedSetting = true,
            check = { granted(it, Manifest.permission.READ_CONTACTS) && granted(it, Manifest.permission.READ_SMS) }),
        PermissionItem("overlay", R.string.perm_overlay, R.string.perm_overlay_why,
            emptySet(),
            check = { Settings.canDrawOverlays(it) },
            intent = { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(it)) }),
    )

    fun forGroups(groups: Set<ToolGroup>) = all.filter { it.groups.any(groups::contains) }

    fun byId(id: String) = all.firstOrNull { it.id == id }

    private fun a11yEnabled(c: Context): Boolean {
        val flat = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.contains(ComponentName(c, DshAccessibilityService::class.java).flattenToString())
    }

    private fun listenerEnabled(c: Context): Boolean {
        val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ComponentName(c, DshNotificationListener::class.java).flattenToString())
    }
}
