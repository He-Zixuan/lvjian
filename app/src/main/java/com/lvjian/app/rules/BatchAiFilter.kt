// 批量 AI 判定引擎（「一键整理」核心）：
//   全部未整理 → 按「适用规则集签名」分组（与实时模式语义等价）→ 40 条/块
//   → 一次调用判一组的全部规则 → 宽松解析 → 逐条落库
// 兜底原则：宁多勿丢——整块失败自动重试一次，仍失败则该块保持 PENDING_BATCH 等下次
package com.lvjian.app.rules

import com.lvjian.app.ai.AiClient
import com.lvjian.app.data.AiConfig
import com.lvjian.app.data.AIState
import com.lvjian.app.data.MessageEntity
import com.lvjian.app.data.RuleEntity
import kotlinx.coroutines.delay

class BatchAiFilter(
    private val aiClient: AiClient,
    private val config: AiConfig
) {
    /** 一次点击的整理结果汇总（UI toast 用） */
    data class BatchResult(
        val filtered: Int,  // 被判过滤的条数
        val kept: Int,      // 被判保留的条数
        val failed: Int,    // 失败的条数（留在未整理）
        val calls: Int      // 实际 API 调用次数
    )

    /**
     * 执行批量整理。
     * @param batch 全部未整理条目（按时间升序）
     * @param enabledRules 全部启用的规则（内部再按每条消息的适用范围筛选）
     * @param onProgress 进度回调（已完成条数, 总条数）
     * @param apply 落库回调：(消息id, 是否过滤, 命中规则名, AI状态)
     */
    suspend fun run(
        batch: List<MessageEntity>,
        enabledRules: List<RuleEntity>,
        onProgress: (Int, Int) -> Unit,
        apply: suspend (msgId: Long, filtered: Boolean, ruleName: String?, aiState: Int) -> Unit
    ): BatchResult {
        var filtered = 0
        var kept = 0
        var failed = 0
        var calls = 0
        val total = batch.size

        // 1) 按适用规则集签名分组：规则有作用范围，不同群适用的规则集可能不同。
        //    分组后每组一次调用只带该组规则，语义与实时逐条判定完全等价。
        val groups = LinkedHashMap<String, Pair<List<RuleEntity>, MutableList<MessageEntity>>>()
        for (msg in batch) {
            val applicable = enabledRules.filter {
                RuleEngine.aiRuleApplies(it, msg.appPackage, msg.groupName)
            }
            if (applicable.isEmpty()) {
                // 规则中途被删/停用：这条不再需要 AI，直接转为普通待处理
                apply(msg.id, false, null, AIState.NONE)
                kept++
                onProgress(filtered + kept + failed, total)
                continue
            }
            val signature = applicable.sortedBy { it.id }.joinToString(",") { it.id.toString() }
            val pair = groups.getOrPut(signature) { applicable to mutableListOf() }
            pair.second.add(msg)
        }

        // 2) 每组按 40 条/块 切块（控制单次请求体与输出长度）
        var done = filtered + kept + failed
        for ((_, pair) in groups) {
            val (groupRules, msgs) = pair
            for (chunk in msgs.chunked(CHUNK_SIZE)) {
                // 编号从 1 开始（提示词与模型输出对齐用）
                val numbered = chunk.mapIndexed { idx, m -> (idx + 1) to m }
                val verdicts = try {
                    calls++
                    callWithRetry(groupRules, numbered)
                } catch (e: Exception) {
                    // 整块失败（重试一次后仍败）：保持未整理，不丢
                    failed += chunk.size
                    done += chunk.size
                    onProgress(done, total)
                    continue
                }
                // 3) 逐条落库；响应里缺失的编号算失败（留在未整理等下次）
                for ((idx, msg) in numbered) {
                    val v = verdicts[idx]
                    if (v == null) {
                        failed++
                    } else if (v.first) {
                        apply(msg.id, true, v.second, AIState.DONE)
                        filtered++
                    } else {
                        apply(msg.id, false, null, AIState.DONE)
                        kept++
                    }
                }
                done += chunk.size
                onProgress(done, total)
            }
        }
        return BatchResult(filtered, kept, failed, calls)
    }

    /** 单次调用 + 失败自动重试一次（3 秒退避） */
    private suspend fun callWithRetry(
        rules: List<RuleEntity>,
        numbered: List<Pair<Int, MessageEntity>>
    ): Map<Int, Pair<Boolean, String?>> {
        val rulesBlock = rules.mapIndexed { i, r -> "${i + 1}.「${r.name}」${r.pattern}" }
            .joinToString("\n")
        val messagesBlock = numbered.joinToString("\n") { (idx, m) ->
            // 正文压成单行，防止消息内换行打乱编号格式
            val oneLine = m.text.replace('\n', ' ').take(300)
            "[$idx] 群：${m.groupName} | ${m.sender}：$oneLine"
        }
        return try {
            aiClient.judgeBatch(config, rulesBlock, messagesBlock)
        } catch (e: Exception) {
            delay(3000)
            aiClient.judgeBatch(config, rulesBlock, messagesBlock)
        }
    }

    companion object {
        const val CHUNK_SIZE = 40  // 每块消息数（单次请求的体量控制，不是总量上限）
    }
}
