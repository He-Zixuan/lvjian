package com.lvjian.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCollectionPolicyTest {
    @Test
    fun recommendedAppsAreTheOnlyDefaultEnabledApps() {
        KnownApps.PRESET.forEach { (packageName, _) ->
            assertTrue("$packageName is recommended", KnownApps.collectingByDefault(packageName))
        }
        assertFalse(KnownApps.collectingByDefault("com.example.unlisted"))
    }

    @Test
    fun enablingAllOpensEveryInstalledAppAndPreservesTakeover() {
        val existing = MonitoredAppEntity(
            packageName = "com.example.app",
            appName = "Example",
            enabled = false,
            takeover = TakeoverState.ACTIVE,
            takeoverChannelOk = true
        )
        val rows = AppCollectionPolicy.setInstalledApps(
            listOf("com.example.app" to "Example", "com.example.new" to "New"),
            listOf(existing),
            enabled = true
        )
        assertTrue(rows.all { it.enabled })
        assertEquals(TakeoverState.ACTIVE, rows.first().takeover)
        assertTrue(rows.first().takeoverChannelOk)
    }

    @Test
    fun disablingAllClosesCollectionAndTakeoverButPreservesRows() {
        val existing = MonitoredAppEntity(
            packageName = "com.example.app",
            appName = "Example",
            enabled = true,
            takeover = TakeoverState.ACTIVE,
            takeoverChannelOk = true
        )
        val rows = AppCollectionPolicy.setInstalledApps(
            listOf("com.example.app" to "Example", "com.example.new" to "New"),
            listOf(existing),
            enabled = false
        )
        assertEquals(2, rows.size)
        assertFalse(rows[0].enabled)
        assertEquals(TakeoverState.OFF, rows[0].takeover)
        assertFalse(rows[0].takeoverChannelOk)
        assertFalse(rows[1].enabled)
        assertTrue(existing.enabled)
        assertEquals(TakeoverState.ACTIVE, existing.takeover)
    }
}
