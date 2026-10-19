// 保活前台服务：挂一条最低重要度通知，降低被系统后台回收的概率（小米/华为/OPPO/vivo 等国产 ROM 尤其需要）
// 注意：真正的数据采集靠通知监听服务（系统绑定），本服务只是“压舱石”
package com.lvjian.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lvjian.app.MainActivity
import com.lvjian.app.R

class KeeperService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        SystemHelper.rebindNotificationListener(this)
        return START_STICKY // 被杀后尽量拉起
    }

    /** 构造保活通知：IMPORTANCE_MIN，不响不震不横幅，折叠在通知栏底部 */
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.keeper_channel_name), NotificationManager.IMPORTANCE_MIN)
            )
        }
        // 点按通知打开主界面
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.keeper_notification_title))
            .setContentText(getString(R.string.keeper_notification_text))
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "keeper"
        private const val NOTIF_ID = 1

        /** 启动保活；Android 13+ 若无通知权限则跳过（不崩） */
        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
            val intent = Intent(context, KeeperService::class.java)
            // Android 12+ 禁止后台启动前台服务：本方法也会在后台路径被调到
            // （Application.onCreate 被通知监听绑定拉起时、开机广播），
            // 系统拒绝时抛 ForegroundServiceStartNotAllowedException/IllegalStateException，
            // 必须兜住——保活失败只是弱一点，崩掉就是事故
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // 静默放弃本次保活；用户下次打开应用时会再次尝试
            }
        }
    }
}
