// 规则引擎：本地规则（关键词/按人）同步判定；AI 规则交给 AiClient 异步判定
// 作用范围改为多选集合（ScopeKeys），对齐设计稿的「全部群 / X 的全部群 / X·某群」
// 每条规则有动作：过滤或保留。保留优先于过滤——同一条消息先被保留规则命中，就不再过滤。
package com.lvjian.app.rules

import com.lvjian.app.data.RuleAction
import com.lvjian.app.data.RuleEntity
import com.lvjian.app.data.RuleType
import com.lvjian.app.data.ScopeKeys

object RuleEngine {

    /** 一条消息在本地规则上的结论。未命中时整个结果为 null。 */
    data class LocalVerdict(val rule: RuleEntity, val keep: Boolean)

    /**
     * 本地规则匹配。
     * 保留规则整表先看；命中则返回保留，不再看过滤规则。
     * 没有保留命中时，再返回第一条命中的过滤规则。
     * @return 未命中返回 null
     */
    fun matchLocal(
        rules: List<RuleEntity>,
        appPackage: String,
        groupName: String,
        sender: String,
        text: String
    ): LocalVerdict? {
        val applicable = rules.filter { ScopeKeys.covers(it.scopeKeys, appPackage, groupName) }
        val keepHit = applicable.firstOrNull { it.action == RuleAction.KEEP && hits(it, sender, text) }
        if (keepHit != null) return LocalVerdict(keepHit, keep = true)
        val filterHit = applicable.firstOrNull { it.action != RuleAction.KEEP && hits(it, sender, text) }
        return filterHit?.let { LocalVerdict(it, keep = false) }
    }

    /** 关键词看正文包含，按人看发送者显示名完全相等。AI 规则不在本地判。 */
    private fun hits(rule: RuleEntity, sender: String, text: String): Boolean = when (rule.type) {
        RuleType.KEYWORD -> rule.pattern.isNotBlank() &&
            text.contains(rule.pattern.trim(), ignoreCase = true)
        RuleType.PERSON -> rule.pattern.isNotBlank() &&
            sender.trim() == rule.pattern.trim()
        else -> false
    }

    /** 该 AI 规则是否作用于这条消息（异步判定前过滤一遍） */
    fun aiRuleApplies(rule: RuleEntity, appPackage: String, groupName: String): Boolean =
        rule.type == RuleType.AI_PROMPT && ScopeKeys.covers(rule.scopeKeys, appPackage, groupName)

    /**
     * 组装发给 AI 的判定提示词。
     * 过滤规则：命中 = 应过滤。保留规则：命中 = 应留下，调用方按 action 反解模型的 filter 字段。
     */
    fun buildPrompt(rule: RuleEntity, groupName: String, sender: String, text: String): String =
        buildString {
            val verb = if (rule.action == RuleAction.KEEP) "保留规则" else "过滤规则"
            append(verb).append("「").append(rule.name).append("」：").append(rule.pattern).append('\n')
            append("群名：").append(groupName).append('\n')
            append("发送人：").append(sender).append('\n')
            append("消息：").append(text)
        }
}
