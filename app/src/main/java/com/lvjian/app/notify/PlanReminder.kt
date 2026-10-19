// 计划提醒：全局设置「每项计划开始前 N 分钟发一条通知」。
// 这里只放两件事：① 纯函数（算下一occurrence / 算触发时刻，可上 JVM 单测）
//                 ② 闹钟排程（AlarmManager）+ 收到闹钟后的接收器
// 通知的样子在 NotificationHelper（独立渠道 lvjian_plan）。
package com.lvjian.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lvjian.app.LvJianApp
import com.lvjian.app.data.PlanEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

object PlanReminder {

    /** 提前量的合法区间与默认值。**与预览端 state.planReminderOn / planLead 的默认值保持一致**（默认开 + 10 分钟）。
     *  v0.6.0：区间从 1–720 放宽到 **-999~999**；负数 = 计划开始过后再提醒（预览 v7.3 用户拍板）。 */
    const val LEAD_MIN = -999
    const val LEAD_MAX = 999
    const val LEAD_DEFAULT = 10

    fun isValidLead(min: Int) = min in LEAD_MIN..LEAD_MAX
    fun normalizeLead(min: Int) = min.coerceIn(LEAD_MIN, LEAD_MAX)

    /** 事件开始时刻（不带日期） */
    private fun timeOf(startMin: Int): LocalTime = LocalTime.of(startMin / 60, startMin % 60)

    /**
     * 这条计划**下一次**的开始时刻；不会再有下一次则返回 null。
     *
     * @param epochDay 事件日期。weekly 时它只当"星期几"的锚点用（见 PlanEventEntity 的注释）
     * @param now      传入当前时间而不是内部取，方便单测注入
     */
    fun nextStart(epochDay: Long, startMin: Int, repeatWeekly: Boolean, now: LocalDateTime): LocalDateTime? {
        val time = timeOf(startMin)
        if (!repeatWeekly) {
            val at = LocalDate.ofEpochDay(epochDay).atTime(time)
            return if (at.isAfter(now)) at else null      // 已经过去的一次性事件不再提醒
        }
        val anchor = LocalDate.ofEpochDay(epochDay).dayOfWeek
        val today = now.toLocalDate()
        val date = if (today.dayOfWeek == anchor && today.atTime(time).isAfter(now)) {
            today                                          // 就是今天、且还没到点
        } else {
            today.with(TemporalAdjusters.next(anchor))     // next() 是"严格之后"，今天已过点会正确落到下周
        }
        return date.atTime(time)
    }

    /**
     * 提醒该响的时刻 = 开始时刻 − 提前量（**提前量为负时 = 开始过后 |lead| 分钟**）。
     * 已经过了不补发；一次性返回 null，每周重复则排下一周，不能把后续提醒一起丢掉。
     *
     * v0.6.0：改为「候选发生时刻」模型 —— 负数提前量下"刚开始没多久"的事件，提醒时刻还在未来，
     * 不能因为开始时刻已过就直接丢弃（旧的 nextStart−lead 模型会把它漏掉）。
     * weekly 给本周那次 + 下周那次两个候选，一次性只给自己。
     */
    fun triggerAt(
        epochDay: Long, startMin: Int, repeatWeekly: Boolean, leadMin: Int, now: LocalDateTime
    ): LocalDateTime? {
        val time = timeOf(startMin)
        val candidates: List<LocalDateTime> = if (repeatWeekly) {
            val anchor = LocalDate.ofEpochDay(epochDay).dayOfWeek
            val prev = now.toLocalDate().with(TemporalAdjusters.previousOrSame(anchor))
            listOf(prev, prev.plusWeeks(1)).map { it.atTime(time) }
        } else {
            listOf(LocalDate.ofEpochDay(epochDay).atTime(time))
        }
        return candidates.firstOrNull { occ -> occ.minusMinutes(leadMin.toLong()).isAfter(now) }
            ?.minusMinutes(leadMin.toLong())
    }

    /** 使用排程时记录的这一次开始时刻；迟到的广播也不能误变成下周的通知。 */
    fun deliveryOccurrence(epochDay: Long, startMin: Int, repeatWeekly: Boolean,
        scheduled: LocalDateTime?): LocalDateTime? {
        if (scheduled == null || scheduled.toLocalTime() != timeOf(startMin)) return null
        val date = LocalDate.ofEpochDay(epochDay)
        return scheduled.takeIf {
            if (repeatWeekly) it.dayOfWeek == date.dayOfWeek else it.toLocalDate() == date
        }
    }
}

/**
 * 闹钟排程。策略：**全量重排**（先撤掉所有已排的，再按当前设置重排），
 * 这样删计划 / 改时间 / 改设置都不需要各自写一套增量逻辑。
 *
 * requestCode 用事件 id，所以每条计划对应一个独立的 PendingIntent。
 * 被删掉的事件不会出现在列表里，靠 Prefs 里记的"上次排了哪些 id"来回收它的闹钟。
 */
object PlanReminderScheduler {

    private const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_OCCURRENCE = "occurrence_millis"

    /** 精确闹钟是否可用（Android 12+ 需要用户在系统里授权；没授权就退化成不精确闹钟） */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    private fun pendingIntent(context: Context, eventId: Long, occurrenceMillis: Long? = null): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            eventId.toInt(),
            Intent(context, PlanReminderReceiver::class.java).putExtra(EXTRA_EVENT_ID, eventId).apply {
                occurrenceMillis?.let { putExtra(EXTRA_OCCURRENCE, it) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun cancel(context: Context, eventId: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(context, eventId))
    }

    /**
     * 按当前设置把所有计划的提醒闹钟重排一遍。
     *
     * @param previousIds 上一次排过的 id（用来回收已被删除事件残留的闹钟）
     * @return 这次真正排上的 id 集合，调用方需要写回 Prefs
     */
    fun reschedule(
        context: Context,
        events: List<PlanEventEntity>,
        enabled: Boolean,
        leadMin: Int,
        previousIds: Set<Long>,
        now: LocalDateTime = LocalDateTime.now()
    ): Set<Long> {
        // 1) 先全部撤掉：包括这次不在列表里的（已删除 / 已改设置）
        (previousIds + events.map { it.id }).forEach { cancel(context, it) }
        if (!enabled) return emptySet()

        val am = context.getSystemService(AlarmManager::class.java) ?: return emptySet()
        val exact = canScheduleExact(context)
        val lead = PlanReminder.normalizeLead(leadMin)
        val scheduled = mutableSetOf<Long>()

        events.forEach { ev ->
            val at = PlanReminder.triggerAt(ev.epochDay, ev.startMin, ev.repeatWeekly, lead, now) ?: return@forEach
            val millis = at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val occurrenceMillis = at.plusMinutes(lead.toLong())
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pi = pendingIntent(context, ev.id, occurrenceMillis)
            try {
                if (exact) {
                    // 用 setExactAndAllowWhileIdle 而不是 setAlarmClock：后者会在状态栏挂一个闹钟图标，
                    // "提前 10 分钟提示一下"没必要占那个位置
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                }
                scheduled += ev.id
            } catch (e: SecurityException) {
                // 授权在运行中被撤掉的极窄窗口：退化成不精确闹钟，别让整批排程挂掉
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                scheduled += ev.id
            }
        }
        return scheduled
    }
}

/**
 * 闹钟到点。
 * 只做两件事：① 查这条计划是否还在、是否还该提醒 → 发通知；② 重排一次（weekly 事件需要排下一周）。
 * 全部计划都删光了也不会崩 —— 查不到就什么都不做。
 */
class PlanReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("event_id", -1L)
        if (id < 0) return
        val pending = goAsync()          // onReceive 不能阻塞主线程，用 goAsync 争取一点时间做 IO
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = LvJianApp.instance
                val enabled = app.prefs.currentPlanReminderEnabled()
                val lead = app.prefs.currentPlanReminderLeadMin()
                val silent = app.prefs.currentPlanReminderSilent()
                val ev = app.db.planEventDao().byId(id)

                // 事件可能已被删除；开关也可能在这期间被关掉 —— 两种情况都不发
                if (ev != null && enabled) {
                    val savedMillis = intent.getLongExtra(PlanReminderScheduler.EXTRA_OCCURRENCE, -1L)
                    val scheduled = savedMillis.takeIf { it > 0 }?.let {
                        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), java.time.ZoneId.systemDefault())
                    }
                    // 旧版没有 occurrence 的闹钟只重排，不猜测日期而发错提醒。
                    val next = PlanReminder.deliveryOccurrence(ev.epochDay, ev.startMin, ev.repeatWeekly, scheduled)
                    if (next != null) {
                        NotificationHelper.showPlanReminder(
                            context = context,
                            eventId = ev.id,
                            title = ev.title,
                            occurrence = next,
                            endMin = ev.endMin,
                            leadMin = lead,
                            silent = silent
                        )
                    }
                }

                // 重排：weekly 事件要把下一周那一次补上；顺带也把已删除的残留闹钟回收干净
                app.repo.reschedulePlanReminders()
            } catch (_: Throwable) {
                // 闹钟路径不该因为任何异常把进程搞崩
            } finally {
                pending.finish()
            }
        }
    }
}
