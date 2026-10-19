// 通知处置策略（纯函数，无 Android 依赖，JVM 单测直接覆盖）
// 任务书 05 第 5.2 节行为矩阵的实现口径：
//   收集关闭           → 不入库、不动原通知（监听层已按排除集合拦住，这里是双保险）
//   接管非 ACTIVE      → 只收集整理，不撤不重发
//   接管 ACTIVE + 提醒档 → 撤原通知 + 有声重发（前提：滤见发通知许可与渠道可用，
//                          否则宁可不撤——不能出现"原通知没了、提醒也发不出"）
//   接管 ACTIVE + 静默档 → 原通知不动（无声依赖用户在向导里把来源渠道设为静默）
//   接管 ACTIVE + 隐藏档 → 撤原通知，不重发
package com.lvjian.app.notify

import com.lvjian.app.data.GroupMode
import com.lvjian.app.data.TakeoverState

/** 对一条来源通知的处置动作 */
enum class NotificationAction {
    /** 不动原通知（只收集） */
    KEEP,
    /** 撤掉原通知，不重发（隐藏档） */
    CANCEL,
    /** 撤掉原通知并用滤见渠道有声重发（提醒档） */
    CANCEL_AND_REPOST
}

/** 策略判定的输入（全部与具体消息内容无关） */
data class PolicyInput(
    /** 该应用是否仍在收集（false=用户显式排除） */
    val collecting: Boolean,
    /** 应用级接管状态，见 TakeoverState */
    val takeover: Int,
    /** 会话档位，见 GroupMode */
    val groupMode: Int,
    /** 滤见是否持有发通知许可（Android 13+ POST_NOTIFICATIONS；13 以下恒为 true） */
    val canPostNotifications: Boolean,
    /** 滤见的重发渠道是否被用户/系统禁用或压成无声 */
    val alertChannelBlocked: Boolean
) {
    /** 当前是否具备有声重发能力 */
    val canRepost: Boolean get() = canPostNotifications && !alertChannelBlocked
}

object TakeoverPolicy {

    /**
     * 判定对来源通知的动作。
     * 提醒档但当前无法重发时返回 KEEP（收集照常），由调用方把接管降级为 SUSPENDED。
     */
    fun decide(input: PolicyInput): NotificationAction {
        if (!input.collecting) return NotificationAction.KEEP
        if (input.takeover != TakeoverState.ACTIVE) return NotificationAction.KEEP
        return when (input.groupMode) {
            GroupMode.HIDE -> NotificationAction.CANCEL
            GroupMode.QUIET -> NotificationAction.KEEP
            else -> // REMIND：发不出提醒就绝不撤原通知
                if (input.canRepost) NotificationAction.CANCEL_AND_REPOST else NotificationAction.KEEP
        }
    }

    /**
     * 接管为 ACTIVE 且存在「提醒」档会话、但重发能力失效时，应降级为 SUSPENDED。
     * （隐藏/静默档不受影响，无需降级。）
     */
    fun shouldSuspend(takeover: Int, groupModes: Collection<Int>, canRepost: Boolean): Boolean =
        takeover == TakeoverState.ACTIVE &&
            groupModes.any { it == GroupMode.REMIND } &&
            !canRepost

    /** 向导「完成并开启接管」的通过条件：来源渠道已手动确认 + 滤见可发通知 */
    fun canEnableTakeover(channelConfirmed: Boolean, canPostNotifications: Boolean, alertChannelBlocked: Boolean): Boolean =
        channelConfirmed && canPostNotifications && !alertChannelBlocked
}
