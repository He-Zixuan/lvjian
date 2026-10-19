package com.lvjian.app.util

import com.lvjian.app.data.PlanEventEntity
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PlanDeleteMatcherTest {
    private val today = LocalDate.of(2026, 10, 19)   // 周一

    private fun event(
        id: Long,
        title: String,
        day: LocalDate = today.plusDays(1),
        start: Int = 900,
        end: Int = 960,
        weekly: Boolean = false
    ) = PlanEventEntity(
        id = id, epochDay = day.toEpochDay(), startMin = start, endMin = end,
        title = title, repeatWeekly = weekly
    )

    @Test
    fun `local delete finds plan by title even without prior pointer`() {
        val events = listOf(event(1, "高数"), event(2, "英语"))
        val req = PlanDeleteMatcher.parseLocal("删掉高数", today)!!
        assertEquals("高数", req.title)
        assertEquals(listOf(1L), PlanDeleteMatcher.match(events, req, today).map { it.id })
    }

    @Test
    fun `title match tolerates abbreviations`() {
        val events = listOf(event(1, "高等数学"), event(2, "大学英语"))
        val req = PlanDeleteMatcher.parseLocal("把高数删掉", today)!!
        assertEquals(listOf(1L), PlanDeleteMatcher.match(events, req, today).map { it.id })
    }

    @Test
    fun `date word narrows target to that day`() {
        val wednesday = LocalDate.of(2026, 10, 21)
        val friday = LocalDate.of(2026, 10, 23)
        val events = listOf(event(1, "高数", wednesday), event(2, "高数", friday))
        val req = PlanDeleteMatcher.parseLocal("删掉周三的高数", today)!!
        assertEquals(wednesday.toString(), req.date)
        assertEquals(listOf(1L), PlanDeleteMatcher.match(events, req, today).map { it.id })
    }

    @Test
    fun `bare delete with generic object falls back to last written plan`() {
        val events = listOf(event(1, "高数"), event(2, "英语"))
        val fallback = events[0]
        val req = PlanDeleteMatcher.parseLocal("删掉刚才写的计划", today)!!
        assertEquals("", req.title)
        assertEquals(listOf(1L), PlanDeleteMatcher.match(events, req, today, fallback).map { it.id })
    }

    @Test
    fun `unqualified delete removes newest plan`() {
        val events = listOf(event(4, "高数"), event(9, "英语"))
        val req = PlanDeleteMatcher.parseLocal("不要了", today)!!
        assertEquals(listOf(9L), PlanDeleteMatcher.match(events, req, today).map { it.id })
    }

    @Test
    fun `explicit all only when plan words present`() {
        val events = listOf(event(1, "高数"), event(2, "英语"))
        val all = PlanDeleteMatcher.parseLocal("把所有计划都删掉", today)!!
        assertTrue(all.all)
        assertEquals(setOf(1L, 2L), PlanDeleteMatcher.match(events, all, today).map { it.id }.toSet())

        // 「取消所有应用」是设置指令，不能当成清空计划，也不能去匹配计划名
        assertNull(PlanDeleteMatcher.parseLocal("取消所有应用", today))
    }

    @Test
    fun `ai request with id deletes exactly that event`() {
        val events = listOf(event(1, "高数"), event(2, "英语"))
        val req = PlanDeleteMatcher.Request(id = 2, title = "随便写的", date = "2030-01-01")
        assertEquals(listOf(2L), PlanDeleteMatcher.match(events, req, today).map { it.id })
    }

    @Test
    fun `unknown ai id deletes nothing instead of falling back`() {
        val events = listOf(event(1, "高数"), event(2, "英语"))
        val req = PlanDeleteMatcher.Request(id = 999)
        assertTrue(PlanDeleteMatcher.match(events, req, today, events[0]).isEmpty())
    }

    @Test
    fun `ai request without id uses title and date`() {
        val events = listOf(event(1, "高数", LocalDate.of(2026, 10, 21)), event(2, "高数", LocalDate.of(2026, 10, 23)))
        val req = PlanDeleteMatcher.Request(title = "高数", date = "2026-10-23")
        assertEquals(listOf(2L), PlanDeleteMatcher.match(events, req, today).map { it.id })
    }

    @Test
    fun `weekly plan counts as matching its weekday`() {
        val weekly = event(1, "高数", today.plusDays(7), weekly = true)
        val req = PlanDeleteMatcher.parseLocal("删掉今天的高数", today)!!
        assertEquals(listOf(1L), PlanDeleteMatcher.match(listOf(weekly), req, today).map { it.id })
    }

    @Test
    fun `past plans still deletable when date is explicit`() {
        val yesterday = today.minusDays(1)
        val events = listOf(event(1, "高数", yesterday), event(2, "英语"))
        val req = PlanDeleteMatcher.parseLocal("删掉昨天的高数", today)!!
        assertEquals(listOf(1L), PlanDeleteMatcher.match(events, req, today).map { it.id })
    }

    @Test
    fun `delete for unknown title deletes nothing`() {
        val events = listOf(event(1, "高数"))
        val req = PlanDeleteMatcher.parseLocal("删掉游泳课", today)!!
        assertTrue(PlanDeleteMatcher.match(events, req, today).isEmpty())
    }

    @Test
    fun `edit target extracts title before the verb`() {
        val req = PlanDeleteMatcher.parseEditTarget("把高数改到明天下午", today)!!
        assertEquals("高数", req.title)
        assertEquals(today.plusDays(1).toString(), req.date)
    }

    @Test
    fun `non delete sentence is ignored`() {
        assertNull(PlanDeleteMatcher.parseLocal("今天好累", today))
        assertNull(PlanDeleteMatcher.parseLocal("明天下午开会", today))
    }
}