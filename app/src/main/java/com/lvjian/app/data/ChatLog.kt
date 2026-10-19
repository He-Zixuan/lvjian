// 蛋黄对话的「按天记忆」：把对话日志存下来（跨进程重启还在），但过了自然日零点就整份丢掉。
//
// 为什么用 DataStore 存一个字符串、而不是新建数据库表：
//   一天的量很小（发给模型的历史本来就只取最近 20 轮），而新建表要动 DB 版本 + 写迁移，
//   为一个"每天都会清空"的临时日志付这个代价不划算。
//
// 序列化为什么手写而不用 org.json：Android 的 org.json 在 JVM 单测里是 stub（调用会抛
// "not mocked"），手写一份纯 Kotlin 的转义/解转义就能连编解码一起测，不怕聊天里带换行。
package com.lvjian.app.data

import java.time.LocalDate

/** 日志里的一条：who ∈ "me" / "blob" */
data class ChatLogEntry(val who: String, val text: String)

object ChatLog {

    /** 一天内最多存这么多条。防止刷屏式聊天把存储写爆（模型侧另有一道 takeLast(20) 的限制） */
    const val MAX_ENTRIES = 200

    /** 字段分隔符：用一个正常聊天里不可能出现的控制字符，省掉一层转义 */
    private const val SEP = '\u0001'

    /**
     * 这份日志是不是已经过期（该清空了）。
     * 判定就是"日志的日期戳是不是今天" —— 自然日零点换天，跟跨时区/夏令时都无关（都是本地日期）。
     *
     * @param logEpochDay 日志存的日期（没存过传 null）
     */
    fun isStale(logEpochDay: Long?, today: LocalDate): Boolean =
        logEpochDay == null || logEpochDay != today.toEpochDay()

    // ---- 序列化：每条一行，"who<SEP>转义后的正文" ----

    /** 只转义会破坏行结构的三个字符。反斜杠必须先转，否则会把后面转出来的反斜杠再转一遍 */
    private fun esc(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace(SEP.toString(), "\\s")

    /** 单趟从左到右解转义：遇到 `\` 就看下一个字符，避免把 "\\n"（字面反斜杠+n）错解成换行 */
    private fun unesc(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { out.append('\\'); i += 2 }
                    'n' -> { out.append('\n'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    's' -> { out.append(SEP); i += 2 }
                    else -> { out.append(c); i++ }   // 不认识的转义原样保留，别吞字符
                }
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    /** 编成一行一条的文本（只保留最后 MAX_ENTRIES 条） */
    fun encode(entries: List<ChatLogEntry>): String =
        entries.takeLast(MAX_ENTRIES).joinToString("\n") { esc(it.who) + SEP + esc(it.text) }

    /** 解回列表；空串/坏行直接跳过，不让一条脏数据把整份日志废掉 */
    fun decode(raw: String?): List<ChatLogEntry> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val i = line.indexOf(SEP)
            if (i < 0) return@mapNotNull null
            ChatLogEntry(unesc(line.substring(0, i)), unesc(line.substring(i + 1)))
        }
    }
}
