// 通知重发器：「吞掉重发」的"重发"半边
// 前提：来源应用（微信等）的通知渠道已设为静默，原通知发布时无声；
// 撤掉原通知后，由滤见用自己的有声渠道补发一条，响不响、怎么响全部归滤见控制
package com.lvjian.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lvjian.app.R

object NotificationHelper {

    /** 重发通知所用渠道：高优先级（响铃+震动+横幅），用户可在系统设置里单独调 */
    private const val ALERT_CHANNEL = "lvjian_alert"

    /** 滤见当前是否能发出通知（Android 13+ 运行时许可；以下恒可） */
    fun canPostNotifications(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 重发渠道当前是否被禁用或压成无声（用户可能在系统设置里关过）。
     * 渠道不存在视为可用（首次重发时会按高优创建）。
     */
    fun alertChannelBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = nm.getNotificationChannel(ALERT_CHANNEL) ?: return false
        return ch.importance == NotificationManager.IMPORTANCE_NONE ||
            ch.importance <= NotificationManager.IMPORTANCE_LOW
    }

    /** 跳转滤见的通知渠道设置页（让用户恢复被禁的重发渠道） */
    fun openAlertChannelSettings(context: Context) {
        ensureAlertChannel(context)
        try {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, ALERT_CHANNEL)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // 个别 ROM 不支持渠道直达，退到本应用通知设置
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ================= 计划提醒（与消息接管完全分开的一条渠道） =================
    // 单独开渠道的理由：消息重发是"别人发消息了"，计划提醒是"你自己排的事要开始了"，
    // 两者该能各自静音、各自调重要性，混在一个渠道里用户就没法单独管。

    /**
     * 计划提醒分两档（响铃 / 静音），因此**必须是两条渠道**。
     *
     * ⚠️ 这不是设计啰嗦，是 Android 的硬约束：**通知渠道创建之后，重要性（响不响、震不震）
     * 就归用户管了，App 再也改不了**（只能删了重建，而重建会丢掉用户的定制）。
     * 所以"响铃 / 静音"二选一只能靠建两条渠道、发送时挑一条。
     * 预览端对应 PLAN_MODE_LABELS 的两个档位。
     */
    private const val PLAN_CHANNEL_ALERT = "lvjian_plan_alert"    // 响铃
    private const val PLAN_CHANNEL_SILENT = "lvjian_plan_silent"  // 静音（只在通知栏出现）
    /** 早期版本只用过一条 lvjian_plan；拆成两条后把旧的删掉，免得系统设置里留一条死渠道 */
    private const val PLAN_CHANNEL_LEGACY = "lvjian_plan"

    /** 按当前档位取渠道 id */
    fun planChannelId(silent: Boolean) = if (silent) PLAN_CHANNEL_SILENT else PLAN_CHANNEL_ALERT

    /** 指定档位的渠道当前是否被禁用或压成无声 */
    fun planChannelBlocked(context: Context, silent: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = nm.getNotificationChannel(planChannelId(silent)) ?: return false
        return ch.importance == NotificationManager.IMPORTANCE_NONE ||
            ch.importance <= NotificationManager.IMPORTANCE_LOW
    }

    /** 跳转对应档位的渠道设置页 */
    fun openPlanChannelSettings(context: Context, silent: Boolean) {
        ensurePlanChannels(context)
        try {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, planChannelId(silent))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 幂等创建两条渠道，并清掉旧版遗留的那条 */
    private fun ensurePlanChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(PLAN_CHANNEL_ALERT, "计划提醒（响铃）", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "计划开始前的提前提醒：在通知栏出现，并响铃或震动"
            }
        )
        // IMPORTANCE_LOW = 通知栏里出现、但不响不震（要更安静的 IMPORTANCE_MIN 会连横幅都不弹，太隐蔽）
        nm.createNotificationChannel(
            NotificationChannel(PLAN_CHANNEL_SILENT, "计划提醒（静音）", NotificationManager.IMPORTANCE_LOW).apply {
                description = "计划开始前的提前提醒：只在通知栏出现，不出声也不震动"
            }
        )
        if (nm.getNotificationChannel(PLAN_CHANNEL_LEGACY) != null) {
            nm.deleteNotificationChannel(PLAN_CHANNEL_LEGACY)
        }
    }

    private fun hm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    /**
     * 发一条计划提醒。点通知直接跳到「计划」页。
     *
     * @param occurrence 这一"次"计划的开始时刻（weekly 事件是本周/下周那一次）
     * @param silent     true=走静音渠道（只在通知栏出现）；false=走响铃渠道
     * @param leadMin    设置里的提前量；只用于兜底文案，真正显示多少分钟由 now 到 occurrence 现算
     *
     * 通知 id 用事件 id：同一条计划重复触发只会更新同一条通知，不会堆叠。
     */
    fun showPlanReminder(
        context: Context,
        eventId: Long,
        title: String,
        occurrence: java.time.LocalDateTime,
        endMin: Int,
        leadMin: Int,
        silent: Boolean
    ) {
        ensurePlanChannels(context)
        // 闹钟可能因为省电策略晚到，所以不写死"提前 N 分钟"，按当下还剩多久现算
        val mins = java.time.Duration.between(java.time.LocalDateTime.now(), occurrence).toMinutes()
        val lead = when {
            mins < 0L -> {  // 负数提前量：提醒排在计划开始过后才发，话术如实说成"已开始"
                val p = -mins
                if (p < 60L) "已开始 $p 分钟"
                else "已开始 ${p / 60} 小时${if (p % 60 == 0L) "" else " ${p % 60} 分钟"}"
            }
            mins == 0L -> "即将开始"
            mins == 1L -> "还有 1 分钟"
            mins < 60L -> "还有 $mins 分钟"
            else -> "还有 ${mins / 60} 小时 ${if (mins % 60 == 0L) "" else "${mins % 60} 分钟"}"
        }.trim()

        // 结束时刻：跨天结束（如 23:30–00:30）不常见，按同日钟点显示
        val end = hm(endMin)

        val contentIntent = PendingIntent.getActivity(
            context,
            eventId.toInt(),
            android.content.Intent(context, com.lvjian.app.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(com.lvjian.app.MainActivity.EXTRA_JUMP_TAB, "week"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, planChannelId(silent))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$lead：$title")
            .setContentText("${hm(occurrence.hour * 60 + occurrence.minute)}–$end")
            .setSubText(if (silent) "计划提醒 · 静音" else "计划提醒")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        try {
            NotificationManagerCompat.from(context).notify(eventId.toInt(), builder.build())
        } catch (e: SecurityException) {
            // Android 13+ 未授予通知权限：静默放弃（设置卡里会提示去开）
        }
    }

    /** 确保渠道存在（幂等；渠道一旦创建，重要性等属性由用户在系统设置里接管） */
    private fun ensureAlertChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, "消息提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "被监控会话的新消息提醒（原通知已被滤见接管）"
                }
            )
        }
    }

    /**
     * 重发一条消息通知。
     * @param msg 用于展示的最新一条消息（标题=发送人，正文=消息文本，副标题=群名）
     * @param contentIntent 原通知的点击跳转（微信创建），转交给我们的通知后点按行为不变
     *
     * 防刷屏策略：通知 id 按会话固定 + setOnlyAlertOnce——同会话连续来消息时
     * 只更新已有通知的内容，不重复响铃；用户滑掉后下一条才会再响。
     */
    fun repost(context: Context, msg: ParsedMessage, contentIntent: PendingIntent?) {
        ensureAlertChannel(context)
        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification) // 专用单色剪影；small icon 只取 alpha，不能拿启动图标顶替
            .setContentTitle(if (msg.isGroup) msg.sender else msg.groupName) // 群里看人，私聊看对方名
            .setContentText(msg.text)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        // 群消息补上群名作为副标题（私聊标题已是对方名，无需重复）
        if (msg.isGroup) builder.setSubText(msg.groupName)
        // 优先按通知来源包名构造显式启动入口，避免企微通知误携带微信的 contentIntent。
        val sourceIntent = context.packageManager.getLaunchIntentForPackage(msg.appPackage)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val sourcePendingIntent = contentIntent?.takeIf {
            NotificationTapPolicy.canReusePendingIntent(msg.appPackage, it.creatorPackage)
        } ?: sourceIntent?.let {
            PendingIntent.getActivity(
                context,
                (msg.appPackage + msg.groupName).hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        sourcePendingIntent?.let { builder.setContentIntent(it) }
        try {
            NotificationManagerCompat.from(context)
                .notify((msg.appPackage + msg.groupName).hashCode(), builder.build())
        } catch (e: SecurityException) {
            // Android 13+ 未授予通知权限时放弃本次重发（消息仍正常入库）
        }
    }
}
