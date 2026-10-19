package com.lvjian.app.data

import org.junit.Assert.*
import org.junit.Test

class ChatDraftTest {
    @Test fun selectionStaysInDraftUntilExplicitSend() {
        val draft = ChatDraft(text = "请比较这些图片").addImages(listOf("a", "b", "a"))
        assertEquals(listOf("a", "b"), draft.images)
        assertEquals("请比较这些图片", draft.text)
        assertTrue(draft.canSend(false))
        assertFalse(draft.canSend(true))
        assertEquals(listOf("b"), draft.removeImage("a").images)
        assertFalse(ChatDraft().canSend(false))
        assertTrue(ChatDraft().addImages(listOf("a")).canSend(false))
    }
}
