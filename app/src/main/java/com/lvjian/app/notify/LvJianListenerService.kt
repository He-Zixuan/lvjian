// 核心服务：通知监听（数据来源）
// 核心服务：仅收集三个推荐应用和用户显式开启的应用通知；其它应用默认跳过。
// 是否撤掉/重发原通知由 MessageRepository 按「应用级接管状态 + 会话档位」决定（见 TakeoverPolicy）。
// 生命周期由系统管理：用户在系统设置授予“通知访问权”后系统自动绑定
package com.lvjian.app.notify

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lvjian.app.LvJianApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LvJianListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 收集范围缓存：只接收白名单里的包（推荐应用 + 用户显式开启）。 */
    @Volatile
    private var enabledPackages: Set<String> = emptySet()

    /**
     * 排除配置是否已加载完成。
     * 未加载前一律不处理：既不收集（避免短暂绕过已保存的排除），更不会执行任何接管动作。
     */
    @Volatile
    private var configLoaded: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 订阅启用范围：用户开启/关闭应用后自动刷新，无需重启服务
        scope.launch {
            LvJianApp.instance.repo.enabledAppPackages().collect {
                enabledPackages = it
                configLoaded = true
            }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 硬排除自己：接管「提醒」档的重发通知来自滤见，绝不能被再收一遍（防自循环）
        if (sbn.packageName == packageName) return
        // 配置未加载完成前不处理（保守：不绕过排除、不执行接管）
        if (!configLoaded) return
        // 只收集默认推荐应用及用户明确开启的应用。
        if (sbn.packageName !in enabledPackages) return

        // 跳过“聚合摘要通知”：内容只有“x 条新消息”，没有逐条正文
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // 经注册表取该应用的解析器（全部是通用解析器，后续可按包名定制）
        val parsed = ParserRegistry.forPackage(sbn.packageName).parse(sbn)
        if (parsed.isEmpty()) return

        // 整条通知交给仓储：通知处置（撤掉/有声重发，仅当该应用接管已启用）一次完成，逐条消息去重入库
        // contentIntent 转交给重发通知，点按行为与来源应用原通知一致
        scope.launch {
            LvJianApp.instance.repo.ingestAll(parsed, sbn.key, sbn.notification.contentIntent)
        }
    }

    /** 监听被系统断开（杀进程/权限波动）时请求重新绑定，减少断收窗口 */
    override fun onListenerDisconnected() {
        requestRebind(ComponentName(this, LvJianListenerService::class.java))
    }

    companion object {
        /** 当前存活实例（仓储回调取消通知用）；服务未运行时为空 */
        var instance: LvJianListenerService? = null
            private set
    }
}
