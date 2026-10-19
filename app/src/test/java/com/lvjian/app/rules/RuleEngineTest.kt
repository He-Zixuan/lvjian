package com.lvjian.app.rules

import com.lvjian.app.data.RuleAction
import com.lvjian.app.data.RuleEntity
import com.lvjian.app.data.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    private fun rule(
        name: String,
        type: Int = RuleType.KEYWORD,
        pattern: String = name,
        action: Int = RuleAction.FILTER
    ) = RuleEntity(name = name, type = type, pattern = pattern, action = action)

    @Test
    fun keywordFilterHitsBody() {
        val v = RuleEngine.matchLocal(listOf(rule("家教")), "pkg", "群", "甲", "有家教广告")
        assertEquals("家教", v?.rule?.name)
        assertFalse(v!!.keep)
    }

    @Test
    fun keepWinsOverLaterFilter() {
        val rules = listOf(
            rule("家教", action = RuleAction.FILTER),
            rule("作业", action = RuleAction.KEEP)
        )
        val v = RuleEngine.matchLocal(rules, "pkg", "群", "甲", "家教作业截止")
        assertEquals("作业", v?.rule?.name)
        assertTrue(v!!.keep)
    }

    @Test
    fun keepCheckedBeforeFilterEvenIfFilterIsListedFirst() {
        val rules = listOf(
            rule("刷屏", action = RuleAction.FILTER),
            rule("通知", action = RuleAction.KEEP)
        )
        val v = RuleEngine.matchLocal(rules, "pkg", "群", "甲", "通知不要刷屏")
        assertTrue(v!!.keep)
        assertEquals("通知", v.rule.name)
    }

    @Test
    fun personKeepMatchesSenderOnly() {
        val rules = listOf(rule("班主任", RuleType.PERSON, "王老师", RuleAction.KEEP))
        val hit = RuleEngine.matchLocal(rules, "pkg", "群", "王老师", "随便聊聊")
        assertTrue(hit!!.keep)
        assertNull(RuleEngine.matchLocal(rules, "pkg", "群", "别人", "王老师"))
    }

    @Test
    fun oldFilterDefaultStaysFilter() {
        val legacy = RuleEntity(name = "家教", type = RuleType.KEYWORD, pattern = "家教")
        assertEquals(RuleAction.FILTER, legacy.action)
    }
}
