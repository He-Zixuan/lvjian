package com.lvjian.app.ui

import kotlin.math.abs

/** 只追到已落库的数量；每个整数各停留一拍，合并回调也不跳号。 */
internal suspend fun animateSavedCount(
    from: Int,
    saved: Int,
    motionScale: Float = 1f,
    pause: suspend (Long) -> Unit,
    show: (Int) -> Unit
) {
    if (from == saved) return
    if (motionScale <= 0f) {
        show(saved)
        return
    }
    val step = if (saved > from) 1 else -1
    val interval = ((900L / abs(saved.toLong() - from)).coerceIn(32L, 80L) * motionScale)
        .toLong().coerceAtLeast(1L)
    var current = from
    while (current != saved) {
        pause(interval)
        current += step
        show(current)
    }
}
