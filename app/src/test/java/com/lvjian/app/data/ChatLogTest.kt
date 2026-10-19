// 蛋黄对话按天记忆的逻辑单测：
// ① 跨天判定（isStale）—— 规则只有一条：日志的日期戳不是"今天"就该清
// ② 手写编解码的往返正确性 —— 尤其是"正文里带换行/反斜杠/分隔符"这几种会破坏行结构的情况
package com.lvjian.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ChatLogTest {

    private val today = LocalDate.of(2026, 10, 16)

    // ---------- 一、跨天判定 ----------

    @Test
    fun `no log at all counts as stale`() {
        // 从没存过（戳是 null）→ 当作过期，走空白开局
        assertTrue(ChatLog.isStale(null, today))
    }

    @Test
    fun `log from today is kept`() {
        assertFalse(ChatLog.isStale(today.toEpochDay(), today))
    }

    @Test
    fun `log from yesterday is stale`() {
        assertTrue(ChatLog.isStale(today.minusDays(1).toEpochDay(), today))
    }

    @Test
    fun `any mismatch counts as stale, including a future stamp`() {
        // 跨天就是"不等"，不做"更早才算过期"的判断 ——
        // 手机改过时间、时区变化都可能造出未来戳，那种也该清掉而不是留着
        assertTrue(ChatLog.isStale(today.plusDays(1).toEpochDay(), today))
        assertTrue(ChatLog.isStale(today.plusDays(30).toEpochDay(), today))
    }

    @Test
    fun `stale check flips exactly at midnight`() {
        // 同一天的任意时刻都算"今天"（判定粒度是天，不带时分秒）
        assertFalse(ChatLog.isStale(today.toEpochDay(), today))
        // 到了第二天就过期
        assertTrue(ChatLog.isStale(today.toEpochDay(), today.plusDays(1)))
    }

    // ---------- 二、编解码往返 ----------

    @Test
    fun `round trips plain text`() {
        val src = listOf(
            ChatLogEntry("me", "今天下午打两个小时的羽毛球"),
            ChatLogEntry("blob", "好啦，今天 15:30–17:30 帮你排上了「打羽毛球」。")
        )
        assertEquals(src, ChatLog.decode(ChatLog.encode(src)))
    }

    @Test
    fun `round trips text containing a real newline`() {
        // 蛋黄的回话是多行的（"好啦…\n想改就说「改到晚上」"），换行不能把一条拆成两条
        val src = listOf(ChatLogEntry("blob", "第一行\n第二行\n第三行"))
        val encoded = ChatLog.encode(src)
        assertEquals(1, encoded.split("\n").size)      // 编码后必须只占一行
        assertEquals(src, ChatLog.decode(encoded))
    }

    @Test
    fun `round trips text containing a literal backslash-n`() {
        // 最容易写错的一条：正文里的"字面反斜杠 + n"（4 个字符）解码后
        // 必须还是字面反斜杠 + n，不能被当成换行
        val src = listOf(ChatLogEntry("me", "路径是 C:\\new 这个"))
        val got = ChatLog.decode(ChatLog.encode(src))
        assertEquals(src, got)
        assertEquals("路径是 C:\\new 这个", got[0].text)
        assertFalse(got[0].text.contains('\n'))
    }

    @Test
    fun `round trips text containing the field separator`() {
        // 分隔符用的是正常聊天里不可能出现的控制字符；就算真出现了也不能把这条拆坏
        val src = listOf(ChatLogEntry("me", "带\u0001分隔符的正文"))
        assertEquals(src, ChatLog.decode(ChatLog.encode(src)))
    }

    @Test
    fun `round trips carriage return and tab`() {
        val src = listOf(ChatLogEntry("blob", "回车\r制表\t结尾"))
        assertEquals(src, ChatLog.decode(ChatLog.encode(src)))
    }

    @Test
    fun `round trips empty text and multiple entries`() {
        val src = listOf(
            ChatLogEntry("me", ""),
            ChatLogEntry("blob", "x"),
            ChatLogEntry("me", "y\nz")
        )
        assertEquals(src, ChatLog.decode(ChatLog.encode(src)))
    }

    @Test
    fun `empty list encodes to empty and decodes back to empty`() {
        assertEquals("", ChatLog.encode(emptyList()))
        assertEquals(emptyList<ChatLogEntry>(), ChatLog.decode(""))
        assertEquals(emptyList<ChatLogEntry>(), ChatLog.decode(null))
    }

    // ---------- 三、容量与坏数据 ----------

    @Test
    fun `only the last MAX_ENTRIES survive`() {
        val many = (1..ChatLog.MAX_ENTRIES + 50).map { ChatLogEntry("me", "第 $it 条") }
        val got = ChatLog.decode(ChatLog.encode(many))
        assertEquals(ChatLog.MAX_ENTRIES, got.size)
        // 丢的是最旧的，留下的是最近的
        assertEquals("第 51 条", got.first().text)
        assertEquals("第 ${ChatLog.MAX_ENTRIES + 50} 条", got.last().text)
    }

    @Test
    fun `a corrupt line is skipped without killing the whole log`() {
        // 手工拼一份"中间有一行没有分隔符"的坏数据
        val good1 = ChatLog.encode(listOf(ChatLogEntry("me", "好的一行")))
        val bad = "这一行没有分隔符"
        val good2 = ChatLog.encode(listOf(ChatLogEntry("blob", "也好的一行")))
        val got = ChatLog.decode("$good1\n$bad\n$good2")
        assertEquals(2, got.size)
        assertEquals("好的一行", got[0].text)
        assertEquals("也好的一行", got[1].text)
    }
}
