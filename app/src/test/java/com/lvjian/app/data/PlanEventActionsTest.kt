package com.lvjian.app.data

import com.lvjian.app.notify.PlanReminder
import com.lvjian.app.util.TaskIntentParser
import com.lvjian.app.util.TaskScheduler
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlanEventActionsTest {
    private val date = LocalDate.of(2026, 10, 16)

    private class Store(initial: List<PlanEventEntity> = emptyList()) : PlanEventActions.Store {
        val events = initial.toMutableList()
        private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1
        override suspend fun all(): List<PlanEventEntity> { yield(); return events.toList() }
        override suspend fun insert(event: PlanEventEntity): Long {
            yield()
            val id = nextId++
            events += event.copy(id = id)
            return id
        }
        override suspend fun update(event: PlanEventEntity) {
            events[events.indexOfFirst { it.id == event.id }] = event
        }
        override suspend fun delete(event: PlanEventEntity) { events.removeAll { it.id == event.id } }
        override suspend fun restore(event: PlanEventEntity) { events += event }
    }

    private fun event(id: Long = 1, start: Int = 900, end: Int = 960, weekly: Boolean = false) =
        PlanEventEntity(id = id, epochDay = date.toEpochDay(), startMin = start,
            endMin = end, title = "已有安排", repeatWeekly = weekly)

    private fun intent(start: Int = 900, end: Int = 960) = TaskIntentParser.TaskIntent(
        title = "对话安排", dayOfWeek = date.dayOfWeek.value - 1, weekOffset = 0,
        durationMin = end - start, durationExplicit = true,
        range = start to end, point = null, period = null)

    @Test
    fun `cold start reads existing persisted plans without a UI subscriber`() = runBlocking {
        val store = Store(listOf(event()))
        var refreshes = 0
        val actions = PlanEventActions(store) { refreshes++ }
        val result = actions.schedule(intent(), date)
        assertTrue(result.decision is TaskScheduler.Result.Conflict)
        assertNull(result.saved)
        assertEquals(listOf(event()), store.events)
        assertEquals(0, refreshes)
    }

    @Test
    fun `consecutive chat requests see the plan just inserted`() = runBlocking {
        val store = Store()
        var refreshes = 0
        val actions = PlanEventActions(store) { refreshes++ }
        assertNotNull(actions.schedule(intent(), date).saved)
        assertTrue(actions.schedule(intent(), date).decision is TaskScheduler.Result.Conflict)
        assertEquals(1, store.events.size)
        assertEquals(1, refreshes)
    }

    @Test
    fun `concurrent requests cannot both claim the same free slot`() = runBlocking {
        val store = Store()
        val actions = PlanEventActions(store) {}
        val first = async { actions.schedule(intent(), date) }
        val second = async { actions.schedule(intent(), date) }
        val outcomes = listOf(first.await(), second.await())
        assertEquals(1, outcomes.count { it.saved != null })
        assertEquals(1, outcomes.count { it.decision is TaskScheduler.Result.Conflict })
        assertEquals(1, store.events.size)
    }

    @Test
    fun `add edit delete and undo each refresh reminders from committed plans`() = runBlocking {
        val store = Store()
        val alarmSnapshots = mutableListOf<Map<Long, java.time.LocalDateTime?>>()
        val now = date.minusDays(1).atStartOfDay()
        val actions = PlanEventActions(store) {
            alarmSnapshots += store.all().associate { e ->
                e.id to PlanReminder.triggerAt(e.epochDay, e.startMin, e.repeatWeekly, 10, now)
            }
        }
        val id = actions.add(event(id = 0))
        val changed = event(id = id, start = 1020, end = 1080)
        actions.update(changed)
        actions.delete(changed)
        actions.restore(changed)
        assertEquals(listOf(
            mapOf(id to date.atTime(14, 50)),
            mapOf(id to date.atTime(16, 50)),
            emptyMap(),
            mapOf(id to date.atTime(16, 50))
        ), alarmSnapshots)
    }

    @Test
    fun `chat rescheduling updates alarms and excludes its own old slot`() = runBlocking {
        val original = event()
        val store = Store(listOf(original))
        val snapshots = mutableListOf<List<PlanEventEntity>>()
        val actions = PlanEventActions(store) { snapshots += store.all() }
        val result = actions.schedule(intent(930, 990), date, replacing = original)
        assertTrue(result.decision is TaskScheduler.Result.Ok)
        assertEquals(original.id, result.saved?.id)
        assertEquals(930, store.events.single().startMin)
        assertEquals(listOf(store.events.toList()), snapshots)
    }

    @Test
    fun `rejected edit leaves both plan and reminder state unchanged`() = runBlocking {
        val original = event()
        val blocker = event(id = 2, start = 1020, end = 1080)
        val store = Store(listOf(original, blocker))
        var refreshes = 0
        val actions = PlanEventActions(store) { refreshes++ }
        val result = actions.schedule(intent(1020, 1080), date, replacing = original)
        assertTrue(result.decision is TaskScheduler.Result.Conflict)
        assertEquals(listOf(original, blocker), store.events)
        assertEquals(0, refreshes)
    }

    @Test
    fun `weekly plans from an older date still block the same weekday`() = runBlocking {
        val old = event(weekly = true).copy(epochDay = date.minusWeeks(1).toEpochDay())
        val store = Store(listOf(old))
        val actions = PlanEventActions(store) {}
        assertTrue(actions.schedule(intent(), date).decision is TaskScheduler.Result.Conflict)
    }

    @Test
    fun `weekly import refreshes reminders and checks future one-off conflicts`() = runBlocking {
        val blocker = event().copy(epochDay = date.plusWeeks(1).toEpochDay())
        val store = Store(listOf(blocker))
        var refreshes = 0
        val actions = PlanEventActions(store) { refreshes++ }
        assertNull(actions.schedule(intent(), date, repeatWeekly = true).saved)
        val free = actions.schedule(intent(1080, 1140), date, repeatWeekly = true, color = "blue")
        assertEquals(true, free.saved?.repeatWeekly)
        assertEquals("blue", free.saved?.color)
        assertEquals(1, refreshes)
    }
}
