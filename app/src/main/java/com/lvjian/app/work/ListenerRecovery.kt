package com.lvjian.app.work

/** 先请求系统重绑；权限仍在但未连接时，再刷新自身组件。 */
class ListenerRecovery(
    private val hasAccess: () -> Boolean,
    private val isConnected: () -> Boolean,
    private val requestRebind: () -> Unit,
    private val refreshComponent: () -> Unit,
    private val awaitBinding: suspend () -> Unit
) {
    suspend fun recover() {
        if (!hasAccess() || isConnected()) return
        requestRebind()
        awaitBinding()
        if (!hasAccess() || isConnected()) return
        refreshComponent()
        requestRebind()
    }
}
