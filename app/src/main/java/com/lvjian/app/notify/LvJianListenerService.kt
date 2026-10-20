// 核心服务：通知监听（数据来源）
// 核心服务：仅收集三个推荐应用和用户显式开启的应用通知；其它应用默认跳过。
// 是否撤掉/重发原通知由 MessageRepository 按「应用级接管状态 + 会话档位」决定（见 TakeoverPolicy）。
// 生命周期由系统管理：用户在系统设置授予“通知访问权”后系统自动绑定
package com.lvjian.app.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lvjian.app.LvJianApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LvJianListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 通知先排队，等待已保存的收集范围；串行入库保证回调与补收不会穿过去重检查。
    private data class QueuedNotification(val notification: StatusBarNotification, val replay: Boolean)
    private val notifications = Channel<QueuedNotification>(Channel.UNLIMITED)

    override fun onCreate() {
        super.onCreate()
        instance = this
        scope.launch {
            for (queued in notifications) {
                val sbn = queued.notification
                try {
                    val enabled = LvJianApp.instance.repo.enabledAppPackages().first()
                    if (sbn.packageName !in enabled) continue
                    val parsed = ParserRegistry.forPackage(sbn.packageName).parse(sbn)
                    if (parsed.isNotEmpty()) {
                        // 补收只写清单，不撤掉或重响已经存在的来源通知。
                        LvJianApp.instance.repo.ingestAll(parsed, if (queued.replay) null else sbn.key, sbn.notification.contentIntent)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 一条异常通知不能让后续消息全部停止收录；不记录通知正文。
                    android.util.Log.w("LvJianListener", "Could not collect notification from ${sbn.packageName}", e)
                }
            }
        }
        scope.launch {
            LvJianApp.instance.repo.enabledAppPackages().collect { replayActiveNotifications() }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _connected.value = true
        replayActiveNotifications()
    }

    private fun replayActiveNotifications() {
        if (!_connected.value) return
        try {
            activeNotifications?.forEach { enqueue(it, replay = true) }
        } catch (e: Exception) {
            android.util.Log.w("LvJianListener", "Could not replay active notifications", e)
        }
    }

    override fun onDestroy() {
        if (instance === this) {
            _connected.value = false
            instance = null
        }
        notifications.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        enqueue(sbn, replay = false)
    }

    private fun enqueue(sbn: StatusBarNotification, replay: Boolean) {
        // 硬排除自己：接管「提醒」档的重发通知来自滤见，绝不能被再收一遍（防自循环）
        if (sbn.packageName == packageName) return
        // 跳过“聚合摘要通知”：内容只有“x 条新消息”，没有逐条正文
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        notifications.trySend(QueuedNotification(sbn, replay))
    }

    /** 监听被系统断开（杀进程/权限波动）时请求重新绑定，减少断收窗口 */
    override fun onListenerDisconnected() {
        _connected.value = false
        com.lvjian.app.work.SystemHelper.rebindNotificationListener(this)
        super.onListenerDisconnected()
    }

    companion object {
        private val _connected = MutableStateFlow(false)
        val connected = _connected.asStateFlow()
        /** 当前存活实例（仓储回调取消通知用）；服务未运行时为空 */
        var instance: LvJianListenerService? = null
            private set
    }
}
