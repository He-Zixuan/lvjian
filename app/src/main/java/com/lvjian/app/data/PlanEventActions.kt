package com.lvjian.app.data

import com.lvjian.app.util.TaskIntentParser
import com.lvjian.app.util.TaskScheduler
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 计划写入与提醒更新共用入口；排期读取持久层，不依赖某个页面是否订阅。 */
class PlanEventActions(
    private val store: Store,
    private val refreshReminders: suspend () -> Unit
) {
    interface Store {
        suspend fun all(): List<PlanEventEntity>
        suspend fun insert(event: PlanEventEntity): Long
        suspend fun update(event: PlanEventEntity)
        suspend fun delete(event: PlanEventEntity)
        suspend fun restore(event: PlanEventEntity)
    }

    data class ScheduleOutcome(val decision: TaskScheduler.Result, val saved: PlanEventEntity? = null)

    private val mutex = Mutex()

    private suspend fun <T> mutate(write: suspend () -> T): T = mutex.withLock {
        val result = write()
        refreshReminders()
        result
    }

    suspend fun add(event: PlanEventEntity): Long = mutate { store.insert(event) }
    suspend fun update(event: PlanEventEntity) = mutate { store.update(event) }
    suspend fun delete(event: PlanEventEntity) = mutate { store.delete(event) }
    suspend fun restore(event: PlanEventEntity) = mutate { store.restore(event) }
    /** 一次性读取：对话里按事项名找要删/改的计划，不能依赖 UI 是否在订阅 */
    suspend fun all(): List<PlanEventEntity> = store.all()
    suspend fun refresh() = mutex.withLock { refreshReminders() }

    /** 把读最新占用、检查冲突、写入和重排串行执行，连续排期也不会使用旧快照。 */
    suspend fun schedule(
        intent: TaskIntentParser.TaskIntent,
        date: LocalDate,
        replacing: PlanEventEntity? = null,
        repeatWeekly: Boolean = false,
        color: String = "teal"
    ): ScheduleOutcome = mutex.withLock {
        val busy = store.all().filter { event ->
            val day = LocalDate.ofEpochDay(event.epochDay)
            event.id != replacing?.id &&
                (day == date || (event.repeatWeekly && day.dayOfWeek == date.dayOfWeek) ||
                    (repeatWeekly && day.dayOfWeek == date.dayOfWeek && !day.isBefore(date)))
        }.map { TaskScheduler.Busy(it.startMin, it.endMin, it.title) }
        val decision = TaskScheduler.schedule(intent, busy)
        if (decision !is TaskScheduler.Result.Ok) return@withLock ScheduleOutcome(decision)
        val event = replacing?.copy(epochDay = date.toEpochDay(), startMin = decision.start, endMin = decision.end)
            ?: PlanEventEntity(epochDay = date.toEpochDay(), startMin = decision.start,
                endMin = decision.end, title = intent.title, repeatWeekly = repeatWeekly, color = color)
        val saved = if (replacing == null) event.copy(id = store.insert(event)) else {
            store.update(event)
            event
        }
        refreshReminders()
        ScheduleOutcome(decision, saved)
    }
}
