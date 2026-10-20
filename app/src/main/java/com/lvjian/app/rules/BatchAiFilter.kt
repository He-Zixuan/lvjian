package com.lvjian.app.rules

import com.lvjian.app.ai.AiClient
import com.lvjian.app.data.AiConfig
import com.lvjian.app.data.AIState
import com.lvjian.app.data.MessageEntity
import com.lvjian.app.data.RuleAction
import com.lvjian.app.data.RuleEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** 同应用、同会话、同规则集成批理解。原文由仓储保留，模型只能建议合并。 */
class BatchAiFilter(private val aiClient: AiClient, private val config: AiConfig) {
    data class BatchResult(val filtered: Int, val kept: Int, val failed: Int, val calls: Int, val merged: Int = 0)
    private data class GroupKey(val app: String, val conversation: String, val rules: List<Long>)

    suspend fun run(
        batch: List<MessageEntity>,
        enabledRules: List<RuleEntity>,
        onProgress: (Int, Int) -> Unit,
        mustKeep: (MessageEntity) -> Boolean = { false },
        contextFor: suspend (MessageEntity) -> List<MessageEntity> = { emptyList() },
        saveMerged: (suspend (MessageEntity, List<MessageEntity>, AiClient.BatchVerdict) -> Boolean)? = null,
        saveBrief: suspend (Long, AiClient.BatchVerdict) -> Unit = { _, _ -> },
        apply: suspend (Long, Boolean, String?, Int, String) -> Unit
    ): BatchResult {
        var filtered = 0
        var kept = 0
        var failed = 0
        var calls = 0
        var merged = 0
        var processed = 0
        val total = batch.size
        onProgress(0, total)
        fun progress(count: Int = 1) {
            repeat(count) { processed++; onProgress(processed, total) }
        }
        val groups = batch.sortedWith(compareBy({ it.timestamp }, { it.id })).groupBy { msg ->
            GroupKey(msg.appPackage, msg.groupName, enabledRules.filter {
                RuleEngine.aiRuleApplies(it, msg.appPackage, msg.groupName)
            }.map { it.id }.sorted())
        }
        for ((key, group) in groups) {
            val rules = enabledRules.filter { it.id in key.rules }
            for (chunk in group.chunked(CHUNK_SIZE)) {
                if (rules.isEmpty() && chunk.none(mustKeep)) {
                    for (msg in chunk) {
                        apply(msg.id, false, null, AIState.NONE, "")
                        kept++; progress()
                    }
                    continue
                }
                val numbered = chunk.mapIndexed { index, msg -> index + 1 to msg }.toMap()
                val context = contextFor(chunk.last()).filter {
                    it.appPackage == key.app && it.groupName == key.conversation &&
                        it.id !in chunk.map { m -> m.id } && !it.filtered &&
                        it.status == com.lvjian.app.data.MsgStatus.PENDING && it.mergedIntoId == 0L
                }.distinctBy { it.id }.take(CONTEXT_SIZE)
                    .sortedWith(compareBy({ it.timestamp }, { it.id }))
                val existing = context.mapIndexed { index, msg -> chunk.size + index + 1 to msg }.toMap()
                // 切块边界前后各带少量原文，只读，避免第40/41条失去上下文。
                val chunkStart = group.indexOf(chunk.first())
                val neighbors = (group.subList(maxOf(0, chunkStart - 5), chunkStart) +
                    group.subList(chunkStart + chunk.size, minOf(group.size, chunkStart + chunk.size + 5)))
                    .filter { msg -> context.none { it.id == msg.id } }
                val readOnly = neighbors.mapIndexed { index, msg -> chunk.size + existing.size + index + 1 to msg }.toMap()
                val all = numbered + existing + readOnly
                val rulesBlock = rules.joinToString("\n") {
                    "${if (it.action == RuleAction.KEEP) "保留" else "过滤"}｜「${it.name}」${it.pattern}"
                }.ifBlank { "没有过滤规则，全部保留，只做摘要与同一事项合并。" }
                val messagesBlock = buildString {
                    append("今天：${java.time.LocalDate.now()}；本批只有同一应用的同一会话。\n")
                    for ((i, msg) in all) {
                        val kind = when (i) {
                            in existing -> "【已有待处理事项】"
                            in readOnly -> "【只读上下文，不要输出结论，不可合并到此编号】"
                            else -> "【本批新消息】"
                        }
                        val keep = if (mustKeep(msg)) "【必须保留】" else ""
                        val time = Instant.ofEpochMilli(msg.timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()
                        append("[$i] 群：${msg.groupName} | $time | $kind$keep ${JSONObject.quote(msg.sender)}：")
                        append(JSONObject.quote(msg.text.takeLast(2000)))
                        if (msg.summary.isNotBlank()) append(" | 已有摘要：${JSONObject.quote(msg.summary)}")
                        append('\n')
                    }
                }
                val verdicts = try {
                    callWithRetry(rulesBlock, messagesBlock) { calls++ }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += chunk.size
                    continue
                }.mapValues { (i, v) ->
                    if (all[i]?.let(mustKeep) == true) v.copy(filter = false, ruleName = null) else v
                }
                // 只接受闭合、无环且目标也确实被判保留的合并关系。
                fun rootOf(index: Int): Int? {
                    val visited = mutableSetOf<Int>()
                    var current = index
                    while (visited.add(current)) {
                        if (current !in all || current in readOnly) return null
                        val v = verdicts[current] ?: return null
                        if (v.filter) return null
                        if (v.mergeInto == 0) return current
                        current = v.mergeInto
                    }
                    return null
                }
                val roots = numbered.keys.associateWith { i -> rootOf(i) }
                val saved = mutableSetOf<Int>()
                for ((i, msg) in numbered) {
                    if (i in saved) continue
                    val v = verdicts[i]
                    if (v == null) { failed++; onProgress(processed, total); continue }
                    if (v.filter) {
                        apply(msg.id, true, v.ruleName, AIState.DONE, "")
                        filtered++; progress(); continue
                    }
                    val rootIndex = roots[i]
                    val members = numbered.keys.filter { roots[it] == rootIndex && rootIndex != null }
                    val root = all[rootIndex]
                    val rootVerdict = verdicts[rootIndex]
                    val sources = members.filter { it != rootIndex }.map { numbered.getValue(it) }
                    if (root != null && rootVerdict != null && sources.isNotEmpty() &&
                        rootVerdict.summary.isNotBlank() && saveMerged != null) {
                        if (saveMerged(root, sources, rootVerdict)) {
                            saved += members
                            if (rootIndex in numbered) kept++
                            merged += sources.size
                            progress(members.size)
                        } else {
                            saved += members
                            failed += members.size
                        }
                    } else {
                        // 非法合并、缺少代表结论或无保存能力时，独立保留，绝不隐藏原文。
                        apply(msg.id, false, null, AIState.DONE, v.summary)
                        saveBrief(msg.id, v)
                        kept++; progress()
                        saved += i
                    }
                }
            }
        }
        return BatchResult(filtered, kept, failed, calls, merged)
    }

    private suspend fun callWithRetry(rules: String, messages: String, onCall: () -> Unit): Map<Int, AiClient.BatchVerdict> {
        return try {
            onCall()
            aiClient.judgeBatch(config, rules, messages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            delay(3000)
            onCall()
            aiClient.judgeBatch(config, rules, messages)
        }
    }
    companion object {
        const val CHUNK_SIZE = 40
        const val CONTEXT_SIZE = 30
    }
}
