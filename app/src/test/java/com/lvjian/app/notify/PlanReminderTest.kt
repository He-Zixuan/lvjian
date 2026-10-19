// 计划提醒的纯逻辑单测：算"下一次开始时刻"和"该响的时刻"。
// 这两件事看着简单，但 weekly 的"今天还没到点 / 今天已过点"、以及"提醒时刻已经过去"两处分支最容易写反。
// 全部用手工构造的 now 做注入，不依赖真实时间。
package com.lvjian.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PlanReminderTest {

    /** 2026-10-15 是星期四，跟其它测试用同一个基准日 */
    private val today = LocalDate.of(2026, 10, 15)

    private fun at(hour: Int, min: Int = 0) = today.atTime(hour, min)

    // ---------- 一、nextStart ----------

    @Test
    fun `one-off event in the future returns its own start`() {
        val tomorrow9 = today.plusDays(1)
        val got = PlanReminder.nextStart(tomorrow9.toEpochDay(), 9 * 60, repeatWeekly = false, now = at(8))
        assertEquals(tomorrow9.atTime(9, 0), got)
    }

    @Test
    fun `one-off event already past returns null`() {
        val yesterday = today.minusDays(1)
        assertNull(PlanReminder.nextStart(yesterday.toEpochDay(), 9 * 60, repeatWeekly = false, now = at(8)))
    }

    @Test
    fun `one-off event later today is still in the future`() {
        val got = PlanReminder.nextStart(today.toEpochDay(), 20 * 60, repeatWeekly = false, now = at(8))
        assertEquals(today.atTime(20, 0), got)
    }

    @Test
    fun `weekly event whose slot has not started yet lands on today`() {
        // 锚点就是星期四(=今天)，15:30 还没到 → 今天 15:30
        val got = PlanReminder.nextStart(today.toEpochDay(), 15 * 60 + 30, repeatWeekly = true, now = at(8))
        assertEquals(today.atTime(15, 30), got)
    }

    @Test
    fun `weekly event whose slot already passed lands on next week`() {
        // 同一条，但现在已经 20:00 → 到今天已过点，应该落到下周四
        val got = PlanReminder.nextStart(today.toEpochDay(), 15 * 60 + 30, repeatWeekly = true, now = at(20))
        assertEquals(today.plusWeeks(1).atTime(15, 30), got)
    }

    @Test
    fun `weekly event anchored on another weekday lands on the coming one`() {
        // 锚点 = 后天（星期六），本周还没到 → 就是后天
        val sat = today.plusDays(2)
        assertEquals(java.time.DayOfWeek.SATURDAY, sat.dayOfWeek)
        val got = PlanReminder.nextStart(sat.toEpochDay(), 10 * 60, repeatWeekly = true, now = at(8))
        assertEquals(sat.atTime(10, 0), got)
    }

    // ---------- 二、triggerAt ----------

    @Test
    fun `trigger is start minus lead`() {
        val tomorrow9 = today.plusDays(1)
        val got = PlanReminder.triggerAt(tomorrow9.toEpochDay(), 9 * 60, repeatWeekly = false, leadMin = 10, now = at(8))
        assertEquals(tomorrow9.atTime(8, 50), got)
    }

    @Test
    fun `trigger already in the past is dropped instead of fired late`() {
        // 明天 09:00 的计划，提前 12 小时 → 该在今天 21:00 响，但现在已经 22:00
        // 不能补发：否则每次开 App 都会把错过的一堆一次性炸出来
        val tomorrow9 = today.plusDays(1)
        val got = PlanReminder.triggerAt(tomorrow9.toEpochDay(), 9 * 60, repeatWeekly = false, leadMin = 720, now = at(22))
        assertNull(got)
    }

    @Test
    fun `missed weekly trigger advances to next week without firing late`() {
        // 每周四 15:30、提前 60 分钟 → 提醒本该在 14:30 响。
        // 现在 15:00：**事件还没开始**，但提醒的时点已经过去了。
        // 这里的取舍是"不补发"：已经错过的提醒就不再补一个闹钟
        // （补发的话每次开 App 都会把错过的一批一次性炸出来）。
        // 不补今天这一次，但不能把每周提醒的后续也一起丢掉。
        assertEquals(today.plusWeeks(1).atTime(14, 30),
            PlanReminder.triggerAt(today.toEpochDay(), 15 * 60 + 30, repeatWeekly = true, leadMin = 60, now = at(15)))
    }

    @Test
    fun `weekly reminder rearms at the exact delivery time`() {
        assertEquals(today.plusWeeks(1).atTime(14, 30),
            PlanReminder.triggerAt(today.toEpochDay(), 15 * 60 + 30, true, 60, at(14, 30)))
    }

    @Test
    fun `weekly reminder rearms one second after delivery`() {
        assertEquals(today.plusWeeks(1).atTime(14, 30),
            PlanReminder.triggerAt(today.toEpochDay(), 15 * 60 + 30, true, 60, at(14, 30).plusSeconds(1)))
    }

    @Test
    fun `weekly trigger for next week is still scheduled normally`() {
        // 紧接着上一条：如果现在是本周四 20:00（今天这一次已经过去了），
        // nextStart 会落到下周四 15:30 → 提醒排在下周四 14:30，照常有效
        val got = PlanReminder.triggerAt(today.toEpochDay(), 15 * 60 + 30, repeatWeekly = true, leadMin = 60, now = at(20))
        assertEquals(today.plusWeeks(1).atTime(14, 30), got)
    }

    // ---------- 三、提前量的边界 ----------

    @Test
    fun `late weekly broadcast keeps the scheduled occurrence`() {
        val original = today.atTime(15, 30)
        assertEquals(original, PlanReminder.deliveryOccurrence(today.toEpochDay(), 930, true, original))
    }

    @Test
    fun `old alarm for a changed start time is ignored`() {
        assertNull(PlanReminder.deliveryOccurrence(today.toEpochDay(), 960, true, today.atTime(15, 30)))
    }

    @Test
    fun `old one-off alarm for a changed date is ignored`() {
        assertNull(PlanReminder.deliveryOccurrence(today.plusDays(1).toEpochDay(), 930, false, today.atTime(15, 30)))
    }

    @Test
    fun `legacy alarm without occurrence is not guessed`() {
        assertNull(PlanReminder.deliveryOccurrence(today.toEpochDay(), 930, true, null))
    }

    @Test
    fun `lead outside minus999 to 999 is rejected or clamped`() {
        // v0.6.0：区间从 1–720 放宽到 -999~999，负数 = 计划开始过后再提醒
        assertEquals(-999, PlanReminder.LEAD_MIN)
        assertEquals(999, PlanReminder.LEAD_MAX)
        assertEquals(10, PlanReminder.LEAD_DEFAULT)

        assertEquals(-999, PlanReminder.normalizeLead(-5000))
        assertEquals(999, PlanReminder.normalizeLead(9999))
        assertEquals(30, PlanReminder.normalizeLead(30))
        assertEquals(-30, PlanReminder.normalizeLead(-30))
        assertEquals(true, PlanReminder.isValidLead(-999))
        assertEquals(true, PlanReminder.isValidLead(999))
        assertEquals(false, PlanReminder.isValidLead(-1000))
        assertEquals(false, PlanReminder.isValidLead(1000))
    }

    // ---------- 四、负数提前量（计划开始过后再提醒） ----------

    @Test
    fun `negative lead fires after the event starts`() {
        // 明天 09:00 的计划，lead=-30 → 明天 09:30 才响
        val tomorrow9 = today.plusDays(1)
        val got = PlanReminder.triggerAt(tomorrow9.toEpochDay(), 9 * 60, repeatWeekly = false, leadMin = -30, now = at(8))
        assertEquals(tomorrow9.atTime(9, 30), got)
    }

    @Test
    fun `negative lead still fires for an event that just started`() {
        // 今天 20:00 开始、lead=-10，现在 20:05 → 提醒时刻 20:10 还在未来，不能丢
        val got = PlanReminder.triggerAt(today.toEpochDay(), 20 * 60, repeatWeekly = false, leadMin = -10, now = at(20, 5))
        assertEquals(today.atTime(20, 10), got)
    }

    @Test
    fun `negative lead weekly keeps this week when post-start trigger is still ahead`() {
        // 每周四 15:30、lead=-30，现在周四 15:45 → 今天 16:00 的提醒还在未来，排今天而不是下周
        val got = PlanReminder.triggerAt(today.toEpochDay(), 15 * 60 + 30, repeatWeekly = true, leadMin = -30, now = at(15, 45))
        assertEquals(today.atTime(16, 0), got)
    }

    @Test
    fun `negative lead whose moment already passed is dropped`() {
        // 昨天 09:00 开始、lead=-10 → 提醒时刻昨天 09:10，早已过去 → 不补发
        val yesterday = today.minusDays(1)
        assertNull(PlanReminder.triggerAt(yesterday.toEpochDay(), 9 * 60, repeatWeekly = false, leadMin = -10, now = at(8)))
    }
}
