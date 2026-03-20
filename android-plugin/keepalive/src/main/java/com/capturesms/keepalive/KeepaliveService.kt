package com.capturesms.keepalive

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台保活服务
 * - 显示持久通知，防止系统回收进程
 * - START_STICKY：被杀后系统自动重启
 */
class KeepaliveService : Service() {

    companion object {
        const val CHANNEL_ID   = "capture_sms_keepalive"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP  = "com.capturesms.keepalive.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App 从任务栏划掉后，重新启动服务
        val restart = Intent(applicationContext, KeepaliveService::class.java)
        val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getService(
                applicationContext, 1, restart,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                applicationContext, 1, restart,
                PendingIntent.FLAG_ONE_SHOT
            )
        }
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pending)
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        // 点击通知打开 App
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT

        val contentPending = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("短信监控运行中")
            .setContentText("正在监听双卡短信，点击查看详情")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(contentPending)
            .setOngoing(true)          // 不可滑动删除
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "短信监控服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持短信监控在后台运行"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
