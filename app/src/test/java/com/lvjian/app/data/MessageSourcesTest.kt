package com.lvjian.app.data

import org.junit.Assert.*
import org.junit.Test

class MessageSourcesTest {
    private val root = MessageEntity(1, "com.tencent.mm", "年级群", true, "老师", "问卷通知", 1000)
    private val source = root.copy(id = 2, text = "改为明天截止", timestamp = 2000, aiState = AIState.PENDING_BATCH)

    @Test fun displayPreservesEveryOriginalWithoutMutatingStoredText() {
        val shown = MessageSources.forDisplay(root.copy(summary = "问卷明天截止"), listOf(source))
        assertTrue(shown.text.contains("问卷通知"))
        assertTrue(shown.text.contains("改为明天截止"))
        assertEquals("问卷明天截止", shown.summary)
        assertEquals("问卷通知", root.text)
        assertEquals("改为明天截止", source.text)
        assertEquals(1L, shown.id)
    }

    @Test fun changedDeletedFilteredOrCrossConversationRowsCannotBeConsumed() {
        assertTrue(MessageSources.canMerge(root, listOf(source)))
        assertFalse(MessageSources.canMerge(null, listOf(source)))
        assertFalse(MessageSources.canMerge(root.copy(status = MsgStatus.DONE), listOf(source)))
        assertFalse(MessageSources.canMerge(root, listOf(null)))
        assertFalse(MessageSources.canMerge(root, listOf(source.copy(status = MsgStatus.IGNORED))))
        assertFalse(MessageSources.canMerge(root, listOf(source.copy(filtered = true))))
        assertFalse(MessageSources.canMerge(root, listOf(source.copy(groupName = "另一个群"))))
        assertFalse(MessageSources.canMerge(root, listOf(source.copy(appPackage = "com.tencent.mobileqq"))))
        assertFalse(MessageSources.canMerge(root, listOf(source.copy(mergedIntoId = 3))))
    }
}
