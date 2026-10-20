package com.lvjian.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SavedCountAnimationTest {
    @Test fun wholeBatchArrivingAtOnceStillShowsEverySavedResult() = runBlocking {
        val shown = mutableListOf(0)
        val pauses = mutableListOf<Long>()
        animateSavedCount(0, 40, pause = { pauses += it }) { shown += it }
        assertEquals((0..40).toList(), shown)
        assertEquals(40, pauses.size)
        assertTrue(pauses.all { it >= 32 })
    }

    @Test fun newBatchContinuesFromCurrentDisplayedValue() = runBlocking {
        val shown = mutableListOf(0)
        animateSavedCount(0, 20, pause = {}) { shown += it }
        animateSavedCount(shown.last(), 40, pause = {}) { shown += it }
        assertEquals((0..40).toList(), shown)
    }

    @Test fun partialFailureStopsAtSuccessfulCount() = runBlocking {
        val shown = mutableListOf(0)
        animateSavedCount(0, 7, pause = {}) { shown += it }
        assertEquals((0..7).toList(), shown)
        assertFalse(shown.contains(8))
    }

    @Test fun cancelledAnimationCanResumeWithoutSkippingOrRepeatingValues() = runBlocking {
        val shown = mutableListOf(0)
        try {
            animateSavedCount(0, 40, pause = { if (shown.last() == 3) throw CancellationException() }) { shown += it }
        } catch (_: CancellationException) { }
        animateSavedCount(shown.last(), 50, pause = {}) { shown += it }
        assertEquals((0..50).toList(), shown)
    }

    @Test fun removeAnimationsShowsFinalCountImmediately() = runBlocking {
        val shown = mutableListOf<Int>()
        animateSavedCount(0, 40, motionScale = 0f,
            pause = { fail("系统关闭动画时不等待") }) { shown += it }
        assertEquals(listOf(40), shown)
    }
}
