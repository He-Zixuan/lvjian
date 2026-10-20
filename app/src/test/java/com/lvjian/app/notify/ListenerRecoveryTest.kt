package com.lvjian.app.notify

import com.lvjian.app.work.ListenerRecovery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenerRecoveryTest {
    @Test fun grantedButDisconnectedRefreshesAfterRebindFails() = runBlocking {
        val calls = mutableListOf<String>()
        ListenerRecovery({ true }, { false }, { calls += "rebind" },
            { calls += "refresh" }, { calls += "wait" }).recover()
        assertEquals(listOf("rebind", "wait", "refresh", "rebind"), calls)
    }

    @Test fun successfulRebindDoesNotRestartLiveService() = runBlocking {
        var connected = false
        val calls = mutableListOf<String>()
        ListenerRecovery({ true }, { connected }, { calls += "rebind" },
            { calls += "refresh" }, { connected = true }).recover()
        assertEquals(listOf("rebind"), calls)
    }

    @Test fun revokedPermissionDuringWaitDoesNotRefresh() = runBlocking {
        var access = true
        val calls = mutableListOf<String>()
        ListenerRecovery({ access }, { false }, { calls += "rebind" },
            { calls += "refresh" }, { access = false }).recover()
        assertEquals(listOf("rebind"), calls)
    }

    @Test fun noAccessOrAlreadyConnectedDoesNothing() = runBlocking {
        for ((access, connected) in listOf(false to false, true to true)) {
            val calls = mutableListOf<String>()
            ListenerRecovery({ access }, { connected }, { calls += "rebind" },
                { calls += "refresh" }, { calls += "wait" }).recover()
            assertEquals(emptyList<String>(), calls)
        }
    }
}
