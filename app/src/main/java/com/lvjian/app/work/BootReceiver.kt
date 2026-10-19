// 开机广播接收器：手机重启后恢复保活服务（监听服务由系统自行绑定，无需处理）
// 同时把「计划提醒」的闹钟重排一遍 —— 重启会清掉系统里所有 AlarmManager 闹钟
package com.lvjian.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lvjian.app.LvJianApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        KeeperService.start(context)
        SystemHelper.rebindNotificationListener(context)

        // 重启后系统里的闹钟全没了，必须重排，否则计划提醒会静默失效
        val pending = goAsync()          // onReceive 不能阻塞主线程，goAsync 争取一点时间做 IO
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = LvJianApp.instance
                app.repo.reschedulePlanReminders()
            } catch (_: Throwable) {
                // 开机路径不该因为任何异常把接收器抛崩
            } finally {
                pending.finish()
            }
        }
    }
}
