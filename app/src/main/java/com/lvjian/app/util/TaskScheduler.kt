// 排期决策（纯函数，不碰数据库）—— 与网页预览 web-preview/preview-v7.js 的 scheduleTask 口径一致。
//
// 排期策略（2026-10-16 用户拍板，不要擅自改成"自动兜底"）：
//   · 排不进 → **只报告，不硬塞**（返回 NoSlot，由调用方汇报，不写库）
//   · 指定时间撞车 → **不覆盖，只汇报**（返回 Conflict，不写库）
// 单位统一为"当天分钟数"：0:00 = 0，14:30 = 870。
package com.lvjian.app.util

object TaskScheduler {

    /** 某天已占用的一段时间 */
    data class Busy(val start: Int, val end: Int, val title: String)

    sealed interface Result {
        /** 可排：给出最终区间 */
        data class Ok(val start: Int, val end: Int) : Result
        /** 与已有安排重叠（不写库，交给调用方汇报） */
        data class Conflict(val conflict: Busy, val wantStart: Int, val wantEnd: Int) : Result
        /** 该时段内没有连续足够长的空档（不写库，交给调用方汇报） */
        data class NoSlot(val from: Int, val to: Int, val dur: Int, val free: List<Pair<Int, Int>>) : Result
        /** 超出可排范围（08:00–22:00） */
        object OutOfRange : Result
    }

    /** 指定区间是否与已有安排重叠；重叠则返回那一条（用来汇报"跟谁撞了"） */
    fun conflictAt(busy: List<Busy>, start: Int, end: Int): Busy? =
        busy.firstOrNull { start < it.end && it.start < end }

    /** 某时段内的空闲区间列表 —— 只用于汇报"哪里还有空"，不落库 */
    fun freeSpans(busy: List<Busy>, from: Int, to: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var cursor = from
        for (b in busy.filter { it.end > from && it.start < to }.sortedBy { it.start }) {
            if (b.start > cursor) out += cursor to minOf(b.start, to)
            cursor = maxOf(cursor, b.end)
        }
        if (cursor < to) out += cursor to to
        return out
    }

    /** 在 [from, to) 里找最早的、长度 ≥ dur 的连续空档；找不到返回 null */
    fun findFreeSlot(busy: List<Busy>, from: Int, to: Int, dur: Int): Pair<Int, Int>? {
        var cursor = from
        for (b in busy.filter { it.end > from && it.start < to }.sortedBy { it.start }) {
            if (b.start - cursor >= dur) return cursor to (cursor + dur)
            cursor = maxOf(cursor, b.end)
        }
        if (to - cursor >= dur) return cursor to (cursor + dur)
        return null
    }

    /**
     * 主决策。优先级：明确区间 → 单个时刻 → 模糊时段里找空档。
     * @param busyOfDay 目标那天已占用的时间段（调用方负责把"每周重复"的事件按星期几也算进来）
     */
    fun schedule(intent: TaskIntentParser.TaskIntent, busyOfDay: List<Busy>): Result {
        val spanStart = TaskIntentParser.DAY_SPAN_START
        val spanEnd = TaskIntentParser.DAY_SPAN_END

        val range = intent.range
        if (range != null) {
            // 说了"几点到几点" → 按说的来；撞车不覆盖
            if (range.first < spanStart || range.second > spanEnd) return Result.OutOfRange
            val hit = conflictAt(busyOfDay, range.first, range.second)
            if (hit != null) return Result.Conflict(hit, range.first, range.second)
            return Result.Ok(range.first, range.second)
        }

        val point = intent.point
        if (point != null) {
            // 只说了"几点开始" → 从那一点起排 durationMin 分钟（默认 1 小时）
            val wantEnd = point + intent.durationMin
            if (point < spanStart || wantEnd > spanEnd) return Result.OutOfRange
            val hit = conflictAt(busyOfDay, point, wantEnd)
            if (hit != null) return Result.Conflict(hit, point, wantEnd)
            return Result.Ok(point, wantEnd)
        }

        // 只给了时段/时长 → 在该时段内找最早的够长空档
        val from = intent.period?.start ?: spanStart
        val to = intent.period?.end ?: spanEnd
        val slot = findFreeSlot(busyOfDay, from, to, intent.durationMin)
            ?: return Result.NoSlot(from, to, intent.durationMin, freeSpans(busyOfDay, from, to))
        return Result.Ok(slot.first, slot.second)
    }
}
