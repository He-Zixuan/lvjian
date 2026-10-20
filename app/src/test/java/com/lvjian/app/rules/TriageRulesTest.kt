package com.lvjian.app.rules

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整理准则的护栏：这些条目是用户明确定过的，删掉就等于改回旧行为。
 */
class TriageRulesTest {

    @Test
    fun patternKeepsStudyAndDeadlineItems() {
        val p = TriageRules.PATTERN
        listOf(
            "作业", "上课地点", "调课", "考试", "测验", "截止", "@所有人", "有人@我",
            "招聘", "图片", "拿不准一律保留", "家教"
        ).forEach { assertTrue("缺少「$it」：$p", p.contains(it)) }
    }

    @Test
    fun patternMentionsDiscardSideRules() {
        val p = TriageRules.PATTERN
        listOf("验证码", "营销", "闲聊", "只 @ 了别人", "已经成交或收款").forEach {
            assertTrue("缺少「$it」：$p", p.contains(it))
        }
    }
}