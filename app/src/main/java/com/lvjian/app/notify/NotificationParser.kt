// 通知解析器：把系统通知还原成结构化消息
// 优先走 MessagingStyle（含发送人/时间/会话标题）；失败退回标题+正文兜底
// 该路径对微信/QQ/企业微信基本通用，通用兜底覆盖其它应用
package com.lvjian.app.notify

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/** 一条解析出的消息 */
data class ParsedMessage(
    val appPackage: String,  // 来源应用包名（多应用改造后随消息走全链路）
    val groupName: String,   // 群名；私聊为联系人名
    val isGroup: Boolean,
    val sender: String,
    val text: String,
    val timestamp: Long
)

/**
 * 通知解析策略接口：为后续“每应用定制解析器”留扩展点。
 * 某个应用的通知结构特殊时，实现本接口并注册进 ParserRegistry 即可。
 */
interface NotificationMessageParser {
    /**
     * 解析一条通知，可能包含多条消息（MessagingStyle 会累积）。
     * @return 消息列表；无法提取内容时返回空列表（调用方忽略）
     */
    fun parse(sbn: StatusBarNotification): List<ParsedMessage>
}

/**
 * 解析器注册表：包名 → 定制解析器。
 * 本轮只实现一个通用解析器（NotificationParser）并作为所有包名的缺省；
 * 后续如 QQ/企微需要专门解析，在这里 ParserRegistry.register(包名, 专用解析器) 即可。
 */
object ParserRegistry {
    private val parsers = mutableMapOf<String, NotificationMessageParser>()

    /** 注册某个应用的专用解析器 */
    fun register(packageName: String, parser: NotificationMessageParser) {
        parsers[packageName] = parser
    }

    /** 取该应用的解析器；没有专用解析器时用通用解析器兜底 */
    fun forPackage(packageName: String): NotificationMessageParser =
        parsers[packageName] ?: NotificationParser
}

/** 通用通知解析器（默认注册到所有包名） */
object NotificationParser : NotificationMessageParser {

    /** 微信“关闭通知详情”时的占位文案，没有收集价值，直接跳过 */
    private val CONTENTLESS_MARKERS = listOf("你收到一条消息", "你收到了一条消息")

    override fun parse(sbn: StatusBarNotification): List<ParsedMessage> {
        val notification = sbn.notification
        val extras = notification.extras

        // 1) MessagingStyle 优先：能拿到逐条的发送人/正文/时间
        // 注意：framework 的 Notification.MessagingStyle 没有暴露静态提取方法，
        // 用 AndroidX 的 NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (style != null) {
            val conversationTitle = style.conversationTitle?.toString()
            // 会话标题 = 群名；私聊没有会话标题，用通知标题（对方名字）
            val fallbackTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val group = conversationTitle ?: fallbackTitle
            val isGroup = conversationTitle != null

            val out = mutableListOf<ParsedMessage>()
            for (m in style.messages) {
                val text = m.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                if (CONTENTLESS_MARKERS.any { text.contains(it) }) continue
                out.add(
                    ParsedMessage(
                        appPackage = sbn.packageName,
                        groupName = group.ifBlank { "未知会话" },
                        isGroup = isGroup,
                        // sender 已废弃，优先取 Person 名；都为空时给占位
                        sender = (m.person?.name ?: m.sender)?.toString()?.trim().orEmpty().ifBlank { "未知成员" },
                        text = text,
                        timestamp = if (m.timestamp > 0) m.timestamp else sbn.postTime
                    )
                )
            }
            if (out.isNotEmpty()) return out
        }

        // 2) 兜底：标题 + 正文。邮件常常只有标题，正文与标题是同一句。
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val text = (bigText ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            ?.trim().orEmpty()

        if (text.isEmpty()) return emptyList()
        if (CONTENTLESS_MARKERS.any { text.contains(it) }) return emptyList()
        if (title.isEmpty() && text.isEmpty()) return emptyList()
        // 邮件通知经常只有标题，正文就是同一句。发件人不要再填成标题，否则展开会看到三遍。
        val sender = sub.ifBlank { "" }

        return listOf(
            ParsedMessage(
                appPackage = sbn.packageName,
                groupName = title.ifBlank { "通知" },
                isGroup = false,
                sender = sender.ifBlank { "通知" },
                text = text,
                timestamp = sbn.postTime
            )
        )
    }
}
