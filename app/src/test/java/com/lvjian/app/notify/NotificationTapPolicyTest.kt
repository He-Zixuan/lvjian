package com.lvjian.app.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTapPolicyTest {
    @Test
    fun onlyReusePendingIntentFromSameSourceApp() {
        assertTrue(NotificationTapPolicy.canReusePendingIntent("com.tencent.wework", "com.tencent.wework"))
        assertFalse(NotificationTapPolicy.canReusePendingIntent("com.tencent.wework", "com.tencent.mm"))
        assertFalse(NotificationTapPolicy.canReusePendingIntent("com.tencent.wework", null))
        assertFalse(NotificationTapPolicy.canReusePendingIntent("", "com.tencent.wework"))
    }
}
