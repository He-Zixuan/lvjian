// TakeoverPolicy 单测：覆盖任务书 05 第 5.2 节通知处置矩阵的每一行
// 纯 JVM 测试（无 Android 依赖），`gradlew testDebugUnitTest` 直接跑
package com.lvjian.app.notify

import com.lvjian.app.data.GroupMode
import com.lvjian.app.data.TakeoverState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoverPolicyTest {

    /** 构造一个「收集中 + 接管已启用 + 能发通知」的基准输入，按需覆盖字段 */
    private fun base(
        collecting: Boolean = true,
        takeover: Int = TakeoverState.ACTIVE,
        groupMode: Int = GroupMode.REMIND,
        canPost: Boolean = true,
        channelBlocked: Boolean = false
    ) = PolicyInput(collecting, takeover, groupMode, canPost, channelBlocked)

    // ---- 矩阵行 1：收集关闭 + 接管关闭 → 不动原通知 ----
    @Test
    fun `not collecting means never touch notification`() {
        for (mode in listOf(GroupMode.REMIND, GroupMode.QUIET, GroupMode.HIDE)) {
            assertEquals(NotificationAction.KEEP, TakeoverPolicy.decide(base(collecting = false, groupMode = mode)))
        }
    }

    // ---- 矩阵行 2：收集开启 + 接管关闭（默认只收集）→ 三档都不动原通知 ----
    @Test
    fun `collect only mode never cancels or reposts`() {
        for (mode in listOf(GroupMode.REMIND, GroupMode.QUIET, GroupMode.HIDE)) {
            assertEquals(NotificationAction.KEEP, TakeoverPolicy.decide(base(takeover = TakeoverState.OFF, groupMode = mode)))
        }
    }

    // ---- 矩阵行 3：配置中/已暂停 → 不撤不重发 ----
    @Test
    fun `configuring or suspended takeover never touches notification`() {
        for (state in listOf(TakeoverState.CONFIGURING, TakeoverState.SUSPENDED)) {
            for (mode in listOf(GroupMode.REMIND, GroupMode.QUIET, GroupMode.HIDE)) {
                assertEquals(NotificationAction.KEEP, TakeoverPolicy.decide(base(takeover = state, groupMode = mode)))
            }
        }
    }

    // ---- 矩阵行 4：接管启用 + 提醒档 → 撤 + 有声重发 ----
    @Test
    fun `active takeover with remind mode cancels and reposts`() {
        assertEquals(
            NotificationAction.CANCEL_AND_REPOST,
            TakeoverPolicy.decide(base(groupMode = GroupMode.REMIND))
        )
    }

    // ---- 矩阵行 5：接管启用 + 静默档 → 原通知保留 ----
    @Test
    fun `active takeover with quiet mode keeps notification`() {
        assertEquals(NotificationAction.KEEP, TakeoverPolicy.decide(base(groupMode = GroupMode.QUIET)))
    }

    // ---- 矩阵行 6：接管启用 + 隐藏档 → 撤但不重发 ----
    @Test
    fun `active takeover with hide mode cancels without repost`() {
        assertEquals(NotificationAction.CANCEL, TakeoverPolicy.decide(base(groupMode = GroupMode.HIDE)))
    }

    // ---- 验收 7：发通知许可被关 / 渠道被禁时，提醒档不允许「先撤再发现发不出」 ----
    @Test
    fun `remind without post permission keeps original notification`() {
        assertEquals(
            NotificationAction.KEEP,
            TakeoverPolicy.decide(base(groupMode = GroupMode.REMIND, canPost = false))
        )
        assertEquals(
            NotificationAction.KEEP,
            TakeoverPolicy.decide(base(groupMode = GroupMode.REMIND, channelBlocked = true))
        )
    }

    @Test
    fun `hide mode does not depend on post permission`() {
        // 隐藏档只需监听权，撤掉原通知不依赖发通知许可
        assertEquals(
            NotificationAction.CANCEL,
            TakeoverPolicy.decide(base(groupMode = GroupMode.HIDE, canPost = false))
        )
    }

    // ---- 降级判定：接管启用 + 含提醒档会话 + 重发能力失效 → 应暂停 ----
    @Test
    fun `should suspend only when active takeover has remind conversations and cannot repost`() {
        assertTrue(TakeoverPolicy.shouldSuspend(TakeoverState.ACTIVE, listOf(GroupMode.REMIND), canRepost = false))
        assertFalse(TakeoverPolicy.shouldSuspend(TakeoverState.ACTIVE, listOf(GroupMode.REMIND), canRepost = true))
        assertFalse(TakeoverPolicy.shouldSuspend(TakeoverState.ACTIVE, listOf(GroupMode.QUIET, GroupMode.HIDE), canRepost = false))
        assertFalse(TakeoverPolicy.shouldSuspend(TakeoverState.OFF, listOf(GroupMode.REMIND), canRepost = false))
    }

    // ---- 向导完成条件：渠道确认 + 能发通知 + 渠道未被禁，缺一不可 ----
    @Test
    fun `can enable takeover requires channel confirmation and repost capability`() {
        assertTrue(TakeoverPolicy.canEnableTakeover(channelConfirmed = true, canPostNotifications = true, alertChannelBlocked = false))
        assertFalse(TakeoverPolicy.canEnableTakeover(channelConfirmed = false, canPostNotifications = true, alertChannelBlocked = false))
        assertFalse(TakeoverPolicy.canEnableTakeover(channelConfirmed = true, canPostNotifications = false, alertChannelBlocked = false))
        assertFalse(TakeoverPolicy.canEnableTakeover(channelConfirmed = true, canPostNotifications = true, alertChannelBlocked = true))
    }
}
