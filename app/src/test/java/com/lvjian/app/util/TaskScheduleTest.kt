// 「一句话 → 日程」链路单测：意图解析 + 空档排期 + 课程表句式
// 纯 JVM 测试（无 Android 依赖），只测 TaskIntentParser / TaskScheduler / ScheduleParser 三个纯对象。
// 口径基准：today = 2026-10-15（周四），与网页预览 TODAY_IDX=3（周四）对齐，方便两边对照。
package com.lvjian.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskScheduleTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 15)   // 周四
    private val todayDow = 3                                     // 0=周一 … 3=周四

    /** 建"某天已占用"的列表 */
    private fun busy(vararg triples: Triple<Int, Int, String>): List<TaskScheduler.Busy> =
        triples.map { TaskScheduler.Busy(it.first, it.second, it.third) }

    // ---------- 一、意图解析 ----------

    @Test
    fun `parses duration and period and title`() {
        // 直接 !! —— 解析不出来就该让测试失败，NPE 也能定位到行
        val i = TaskIntentParser.parse("今天下午打两个小时羽毛球", today)!!
        assertEquals("打羽毛球", i.title)
        assertEquals(120, i.durationMin)
        assertTrue(i.durationExplicit)
        assertEquals("下午", i.period?.label)
        assertEquals(todayDow, i.dayOfWeek)
        assertEquals(0, i.weekOffset)
        assertNull(i.range)
        assertNull(i.point)
    }

    @Test
    fun `title strips leading prefix words`() {
        // 这两个曾经都是坏的：
        // ① extractTitle 把句子包成 " " + text + " "，而前缀词表用的是 ^ 锚点，
        //    被开头那个空格顶掉 → 「帮我/我要/记一下」这类前缀永远删不掉，被塞进标题。
        // ② 时长剥离缺"裸半"规则（parseDuration 补过，extractTitle 漏了）
        assertEquals("背单词", TaskIntentParser.extractTitle("我要在今天下午排一个小时背单词"))
        assertEquals("面试", TaskIntentParser.extractTitle("记一下明天上午十点面试"))
        assertEquals("打球", TaskIntentParser.extractTitle("今天下午打半小时球"))
        // 常规路径不能被这次修改碰坏
        assertEquals("打羽毛球", TaskIntentParser.extractTitle("今天下午打两个小时的羽毛球"))
        assertEquals("打羽毛球", TaskIntentParser.extractTitle("今天下午打1.5小时羽毛球"))
    }

    @Test
    fun `leading 在 before a time word is stripped but a real 在家 is kept`() {
        // 「在 + 时间词」只摘紧跟时间表达的那个「在」（"我要在今天下午…"）；
        // 不做句首无差别删除，否则会把「在家写作业」毁成「家写作业」
        assertEquals("在家写作业", TaskIntentParser.extractTitle("今天下午在家写作业两个小时"))
    }

    @Test
    fun `no duration and no clock means not a scheduling task`() {
        // 「去参加志愿活动」没说排多久、也没说几点 → 交给聊天，不要硬排
        assertNull(TaskIntentParser.parse("我今天下午要去参加志愿活动", today))
    }

    @Test
    fun `parses explicit clock range`() {
        val i = TaskIntentParser.parse("今天 14:30 到 15:00 开会", today)!!
        assertEquals(870 to 900, i.range)
        assertEquals(30, i.durationMin)   // 区间长度即时长
        assertEquals("开会", i.title)
    }

    @Test
    fun `parses single clock point with default one hour`() {
        val i = TaskIntentParser.parse("今天下午四点开会", today)!!
        assertEquals(16 * 60, i.point ?: -1)
        assertEquals(60, i.durationMin)
        assertFalse(i.durationExplicit)   // 用于汇报"按 1 小时排的"
        assertEquals("开会", i.title)
    }

    @Test
    fun `parses chinese clock point`() {
        val i = TaskIntentParser.parse("明天上午九点半 体检", today)!!
        assertEquals("体检", i.title)
        assertEquals(todayDow + 1, i.dayOfWeek)   // 周五
        assertEquals(9 * 60 + 30, i.point ?: -1)
    }

    @Test
    fun `weekday plus clock range yields to course parser`() {
        // 「周X + 起止时刻」是课程表句式 → 本层让路（返回 null），由 ScheduleParser 排成每周重复
        assertNull(TaskIntentParser.parse("周二下午2点到4点 大学物理", today))
    }

    @Test
    fun `durations in several shapes`() {
        // 用 "?: -1" 把 Int? 收窄成 Int，避免 assertEquals 的重载歧义
        assertEquals(30, TaskIntentParser.parseDuration("半小时") ?: -1)
        assertEquals(90, TaskIntentParser.parseDuration("一个半小时") ?: -1)
        assertEquals(150, TaskIntentParser.parseDuration("两个半钟头") ?: -1)
        assertEquals(120, TaskIntentParser.parseDuration("俩小时") ?: -1)
        assertEquals(90, TaskIntentParser.parseDuration("90分钟") ?: -1)
        assertEquals(80, TaskIntentParser.parseDuration("1小时20分钟") ?: -1)   // 不能被小数规则截成 60
        assertEquals(90, TaskIntentParser.parseDuration("1.5小时") ?: -1)      // 纯小数小时
        assertNull(TaskIntentParser.parseDuration("三点十分"))   // 这是时刻不是时长
    }

    // ---------- 二、排期决策 ----------

    @Test
    fun `picks earliest slot that fits in the period`() {
        // 周四下午已有 14:00–15:30（C++ 课）；要 2 小时 → 应落到 15:30–17:30
        val i = TaskIntentParser.parse("今天下午打两个小时羽毛球", today)!!
        val res = TaskScheduler.schedule(i, busy(Triple(840, 930, "C++ · 编程练习")))
        assertTrue(res is TaskScheduler.Result.Ok)
        val ok = res as TaskScheduler.Result.Ok
        assertEquals(930, ok.start)
        assertEquals(1050, ok.end)
    }

    @Test
    fun `reports no slot instead of squeezing in`() {
        // 下午只剩 13:00–14:00 和 15:30–18:00 两段，凑不出连续 3 小时 → 不得硬塞
        val i = TaskIntentParser.parse("今天下午打三个小时羽毛球", today)!!
        val res = TaskScheduler.schedule(i, busy(Triple(840, 930, "C++ · 编程练习")))
        assertTrue(res is TaskScheduler.Result.NoSlot)
        val no = res as TaskScheduler.Result.NoSlot
        assertEquals(780, no.from)
        assertEquals(1080, no.to)
        assertEquals(180, no.dur)
        assertTrue(no.free.isNotEmpty())   // 汇报里要能说出"那段时间还有哪些空"
    }

    @Test
    fun `reports conflict instead of overwriting`() {
        val i = TaskIntentParser.parse("今天 14:30 到 15:00 开会", today)!!
        val res = TaskScheduler.schedule(i, busy(Triple(840, 930, "C++ · 编程练习")))
        assertTrue(res is TaskScheduler.Result.Conflict)
        assertEquals("C++ · 编程练习", (res as TaskScheduler.Result.Conflict).conflict.title)
    }

    @Test
    fun `out of range is rejected`() {
        val i = TaskIntentParser.parse("今天 06:00 到 07:00 跑步", today)!!
        assertTrue(TaskScheduler.schedule(i, emptyList()) is TaskScheduler.Result.OutOfRange)
    }

    @Test
    fun `touching but not overlapping is allowed`() {
        // 15:30 开始的那块与 14:00–15:30 只是"相接"，不算重叠
        val i = TaskIntentParser.parse("今天 15:30 到 16:30 开会", today)!!
        assertTrue(TaskScheduler.schedule(i, busy(Triple(840, 930, "课"))) is TaskScheduler.Result.Ok)
    }

    // ---------- 三、课程表句式（每周重复） ----------

    @Test
    fun `course block with chinese clock is parsed`() {
        // 旧版正则只认 "2:00-4:00"，不认 "2点到4点"，导致空态文案里举的例子根本解析不出
        val blocks = ScheduleParser.parse("周二下午2点到4点 大学物理")
        assertEquals(1, blocks.size)
        assertEquals(1, blocks[0].dayOfWeek)      // 周二
        assertEquals(14 * 60, blocks[0].startMin)
        assertEquals(16 * 60, blocks[0].endMin)   // 结束时刻继承"下午" → 16:00，不是 04:00
        assertEquals("大学物理", blocks[0].title)
    }

    @Test
    fun `course block with colon clock still works`() {
        val blocks = ScheduleParser.parse("周一三五 10:00-11:30 高等数学")
        assertEquals(3, blocks.size)
        assertEquals(listOf(0, 2, 4), blocks.map { it.dayOfWeek })
        assertTrue(blocks.all { it.startMin == 600 && it.endMin == 690 && it.title == "高等数学" })
    }
}
