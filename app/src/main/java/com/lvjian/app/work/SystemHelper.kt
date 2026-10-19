// 系统能力助手：权限状态查询与系统设置跳转
package com.lvjian.app.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object SystemHelper {

    /** 通知访问权是否已授予本应用 */
    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * 监听服务此刻有没有绑上。权限开着但系统没绑定（省电、被杀、重装后）时，新通知不会进来。
     */
    fun isNotificationListenerBound(context: Context): Boolean {
        val cn = android.content.ComponentName(context, com.lvjian.app.notify.LvJianListenerService::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        if (!flat.contains(cn.flattenToString())) return false
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val method = manager.javaClass.getMethod("isNotificationListenerAccessGranted", android.content.ComponentName::class.java)
            method.invoke(manager, cn) as? Boolean ?: com.lvjian.app.notify.LvJianListenerService.instance != null
        } catch (e: Exception) {
            com.lvjian.app.notify.LvJianListenerService.instance != null
        }
    }

    fun rebindNotificationListener(context: Context) {
        if (!hasNotificationAccess(context)) return
        val cn = android.content.ComponentName(context, com.lvjian.app.notify.LvJianListenerService::class.java)
        try {
            android.service.notification.NotificationListenerService.requestRebind(cn)
        } catch (e: Exception) {
            // 系统拒绝时下次打开应用再试。权限还在，不需要用户重开开关。
        }
    }
    fun openNotificationAccessSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 跳转微信的通知设置页：在这里把微信通知渠道设为“静默”（伪免打扰的关键配置） */
    fun openWeChatNotificationSettings(context: Context) =
        openAppNotificationSettings(context, "com.tencent.mm")

    /**
     * 跳转任意应用的通知设置页（接管向导用：把来源应用的通知渠道设为“静默”）。
     * 系统不支持精确直达时退化为该应用的详情页，由用户在页内找「通知」入口。
     */
    fun openAppNotificationSettings(context: Context, pkg: String) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 本应用是否已在电池优化白名单中 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 弹窗请求把本应用加入电池优化白名单（失败则打开白名单列表页手动加） */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 各家清理后台后都会杀掉监听。按品牌打开最接近的自启动页，打不开就进应用详情。 */
    fun openVendorAutoStart(context: Context) {
        val brand = Build.BRAND.orEmpty().lowercase()
        val candidates = when {
            brand.contains("huawei") || brand.contains("honor") -> listOf(
                "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager/com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            )
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> listOf(
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> listOf(
                "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oplus.safecenter/com.oplus.safecenter.startupapp.view.StartupAppListActivity",
                "com.oppo.safe/.permission.startup.StartupAppListActivity"
            )
            brand.contains("vivo") || brand.contains("iqoo") -> listOf(
                "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            brand.contains("samsung") -> listOf(
                "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity"
            )
            else -> emptyList()
        }
        for (flat in candidates) {
            try {
                context.startActivity(
                    Intent().setComponent(android.content.ComponentName.unflattenFromString(flat))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (e: Exception) {
                // 这个系统版本没有这一页，试下一个。
            }
        }
        openAppDetailSettings(context)
    }

    /** 给用户看的清理后台说明。锁定没有系统接口，只能写步骤。 */
    fun keepAliveHint(): String {
        val brand = Build.BRAND.orEmpty().lowercase()
        val vendor = when {
            brand.contains("huawei") || brand.contains("honor") ->
                "华为、荣耀：应用启动管理里改成手动管理，允许自启动、关联启动和后台活动。再关闭锁屏清理。"
            brand.contains("xiaomi") || brand.contains("redmi") ->
                "小米：授权管理里打开自启动。省电策略选无限制。"
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
                "OPPO、realme、一加：应用信息里打开自启动，耗电保护选允许后台运行。"
            brand.contains("vivo") || brand.contains("iqoo") ->
                "vivo：i 管家里打开自启动，并加入后台高耗电。"
            brand.contains("samsung") ->
                "三星：电池里把滤见设为不受限制。再在设备保养里把滤见移出休眠应用。"
            else ->
                "在系统设置里打开滤见的自启动，并把电池策略设为不限制。"
        }
        return vendor + " 各家都一样：最近任务里下拉滤见的卡片，锁住它。一键清理不会清掉锁定的应用。单独把它划掉仍会停收，再打开一次就恢复。"
    }
    fun openAppDetailSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---- 计划提醒相关 ----

    /**
     * 是否已获准使用精确闹钟（Android 12+ 是特殊权限）。
     * 12 以下恒为 true；没授权时计划提醒退化为不精确闹钟（可能晚几分钟），不是不能用。
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(android.app.AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    /** 跳转「闹钟和提醒」授权页（部分 ROM 没有这一页，退化为本应用详情页） */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            openAppDetailSettings(context)
        }
    }
}
