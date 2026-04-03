package com.capturesms.keepalive

import android.app.*
import android.content.Intent
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.util.Log
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat

/**
 * 前台保活服务
 * - 显示持久通知，防止系统回收进程
 * - START_STICKY：被杀后系统自动重启
 * - ContentObserver：监听短信数据库，兜底捕获被广播拦截的验证码短信
 */
class KeepaliveService : Service() {

    companion object {
        const val CHANNEL_ID      = "capture_sms_keepalive"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP     = "com.capturesms.keepalive.STOP"
        private const val PHONE_REPORT_URL = "http://192.168.30.194:8014/sms/phone"
        private const val PHONE_REPORT_INTERVAL_MS = 10_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val phoneReporter = object : Runnable {
        override fun run() {
            reportSimNames()
            mainHandler.postDelayed(this, PHONE_REPORT_INTERVAL_MS)
        }
    }

    private var smsObserver: ContentObserver? = null
    @Volatile private var lastSmsId: Long = -1L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerSmsObserver()
        startPhoneReporter()
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

    override fun onDestroy() {
        mainHandler.removeCallbacks(phoneReporter)
        smsObserver?.let { contentResolver.unregisterContentObserver(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
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

    // ── ContentObserver 兜底：捕获被系统短信 App 拦截的验证码短信 ──

    private fun registerSmsObserver() {
        smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                readLatestSms()
            }
        }
        contentResolver.registerContentObserver(
            Uri.parse("content://sms/inbox"),
            true,
            smsObserver!!
        )
        // 记录当前最新短信 ID，避免重复上报历史记录
        lastSmsId = queryLatestSmsId()
    }

    private fun queryLatestSmsId(): Long {
        return try {
            contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                null, null,
                "_id DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            } ?: -1L
        } catch (e: Exception) { -1L }
    }

    private fun readLatestSms() {
        try {
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date", "subscription_id"),
                "_id > ?",
                arrayOf(lastSmsId.toString()),
                "_id DESC"
            ) ?: return

            cursor.use {
                while (it.moveToNext()) {
                    val id     = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val sender = it.getString(it.getColumnIndexOrThrow("address")) ?: ""
                    val body   = it.getString(it.getColumnIndexOrThrow("body"))   ?: ""
                    val date   = it.getLong(it.getColumnIndexOrThrow("date"))
                    val subId  = it.getInt(it.getColumnIndexOrThrow("subscription_id"))

                    if (id > lastSmsId) lastSmsId = id
                    if (!SmsEventEmitter.shouldUpload(this@KeepaliveService, date)) continue

                    val simSlot = getSlotBySubId(subId)
                    val phoneNumber = getPhoneNumberBySubId(subId)
                    val simName = getSimNameBySubId(subId, simSlot, phoneNumber)

                    val record = mapOf(
                        "sender"    to sender,
                        "body"      to body,
                        "sim_slot"  to simSlot,
                        "sim_name"  to simName,
                        "phone_number" to phoneNumber,
                        "timestamp" to date
                    )

                    // 去重：emit 返回 false 表示已由广播处理过，跳过
                    val emitted = SmsEventEmitter.emit(record)
                    if (emitted && !SmsEventEmitter.hasListener()) {
                        val url   = SmsEventEmitter.serverUrl.ifEmpty { "http://192.168.30.194:8014/sms/upload" }
                        val token = SmsEventEmitter.serverToken.ifEmpty { "" }
                        val did   = SmsEventEmitter.deviceId
                        SmsUploader.upload(url, token, did, record)
                    }
                }
            }
        } catch (e: Exception) { /* 权限不足时静默失败 */ }
    }

    private fun startPhoneReporter() {
        mainHandler.removeCallbacks(phoneReporter)
        mainHandler.post(phoneReporter)
    }

    private fun reportSimNames() {
        val simNames = getActiveSimNames()
        if (simNames.isEmpty()) return

        simNames.forEach { simName ->
            SmsUploader.uploadPhoneStatus(PHONE_REPORT_URL, SmsEventEmitter.serverToken, simName)
            Log.d("CaptureSMS", "report sim_name=$simName")
        }
    }

    private fun getActiveSimNames(): List<String> {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return emptyList()
            val list = SubscriptionManager.from(this).activeSubscriptionInfoList ?: return emptyList()
            list.mapNotNull { it?.displayName?.toString()?.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getSlotBySubId(subId: Int): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                android.telephony.SubscriptionManager.from(this)
                    .getActiveSubscriptionInfo(subId)?.simSlotIndex ?: 0
            } else 0
        } catch (e: Exception) { 0 }
    }

    private fun getSimNameBySubId(subId: Int, slotIndex: Int, phoneNumber: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val displayName = android.telephony.SubscriptionManager.from(this)
                    .getActiveSubscriptionInfo(subId)?.displayName?.toString()?.trim().orEmpty()
                if (displayName.isNotEmpty()) displayName else fallbackSimName(slotIndex, phoneNumber)
            } else fallbackSimName(slotIndex, phoneNumber)
        } catch (e: Exception) { fallbackSimName(slotIndex, phoneNumber) }
    }

    private fun fallbackSimName(slotIndex: Int, phoneNumber: String): String {
        val normalizedPhone = normalizePhoneNumber(phoneNumber)
        if (normalizedPhone.isNotEmpty()) return "Phone $normalizedPhone"
        return ""
    }


    private fun getPhoneNumberBySubId(subId: Int): String {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return ""

            val manager = android.telephony.SubscriptionManager.from(this)
            val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && subId != -1) {
                normalizePhoneNumber(manager.getPhoneNumber(subId)).ifEmpty {
                    normalizePhoneNumber(manager.getActiveSubscriptionInfo(subId)?.number)
                }
            } else {
                normalizePhoneNumber(manager.getActiveSubscriptionInfo(subId)?.number)
            }
            if (number.isNotEmpty()) return number

            val telephony = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                ?: return ""
            val telephonyForSub = if (subId != -1) telephony.createForSubscriptionId(subId) else telephony
            val operatorName = telephonyForSub.simOperatorName.orEmpty()
            val line1Number = normalizePhoneNumber(telephonyForSub.line1Number)
            Log.d("CaptureSMS", "operator=$operatorName phone=$line1Number")
            line1Number
        } catch (e: Exception) {
            ""
        }
    }

    private fun normalizePhoneNumber(phoneNumber: String?): String {
        return phoneNumber.orEmpty().removePrefix("+86").trim()
    }

    // ── 通知 ──

    private fun buildNotification(): Notification {
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
            .setOngoing(true)
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
