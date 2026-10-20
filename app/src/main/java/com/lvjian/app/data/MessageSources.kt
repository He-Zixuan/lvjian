package com.lvjian.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 数据库始终保存各条原文；仅展示/提供上下文时拼接，通知重放去重仍能查到原文。 */
object MessageSources {
    fun forDisplay(root: MessageEntity, sources: List<MessageEntity>): MessageEntity {
        if (sources.isEmpty()) return root
        val rows = (listOf(root) + sources).sortedWith(compareBy({ it.timestamp }, { it.id }))
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        return root.copy(text = rows.joinToString("\n\n") {
            val time = Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).format(formatter)
            "${it.sender} · $time\n${it.text}"
        })
    }

    /** 在同一个数据库事务里重新核对，避免请求期间用户划去或删除后又被合并。 */
    fun canMerge(root: MessageEntity?, sources: List<MessageEntity?>): Boolean {
        if (root == null || root.filtered || root.status != MsgStatus.PENDING || root.mergedIntoId != 0L) return false
        if (sources.isEmpty() || sources.any { it == null }) return false
        return sources.filterNotNull().all {
            it.id != root.id && it.appPackage == root.appPackage && it.groupName == root.groupName &&
                !it.filtered && it.status == MsgStatus.PENDING && it.mergedIntoId == 0L &&
                it.aiState == AIState.PENDING_BATCH
        }
    }
}
