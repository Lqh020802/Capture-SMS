package com.capturesms.keepalive

import android.content.Context

/**
 * 单例事件总线：连接静态 BroadcastReceiver 和 UniApp JS 层
 * 因为静态 Receiver 无法持有 Module 引用，通过此单例中转
 */
object SmsEventEmitter {

    private const val PREFS_NAME = "capture_sms_keepalive"
    private const val KEY_INSTALL_TIMESTAMP = "install_timestamp"

    private var listener: ((Map<String, Any?>) -> Unit)? = null

    var serverUrl: String = ""
    var serverToken: String = ""
    var deviceId: String = ""
    var installTimestamp: Long = 0L

    // 去重缓存：10秒内相同 sender+timestamp 只处理一次
    private val recentKeys = LinkedHashSet<String>()

    fun setListener(fn: (Map<String, Any?>) -> Unit) {
        listener = fn
    }

    fun removeListener() {
        listener = null
    }

    fun hasListener(): Boolean = listener != null

    fun setInstallTimestamp(context: Context, timestamp: Long) {
        if (timestamp <= 0L) return
        installTimestamp = timestamp
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_INSTALL_TIMESTAMP, timestamp)
            .apply()
    }

    fun getInstallTimestamp(context: Context): Long {
        if (installTimestamp > 0L) return installTimestamp
        installTimestamp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_INSTALL_TIMESTAMP, 0L)
        return installTimestamp
    }

    fun shouldUpload(context: Context, timestamp: Long): Boolean {
        val baseline = getInstallTimestamp(context)
        return baseline <= 0L || timestamp <= 0L || timestamp >= baseline
    }

    /**
     * 触发 JS 事件，内置去重（防止广播和 ContentObserver 双路重复）
     */
    fun emit(data: Map<String, Any?>): Boolean {
        val sender    = data["sender"] as? String ?: ""
        val timestamp = data["timestamp"] as? Long ?: 0L
        val key       = "$sender:${timestamp / 10000}"  // 10 秒级指纹

        synchronized(recentKeys) {
            if (recentKeys.contains(key)) return false  // 重复，跳过
            recentKeys.add(key)
            if (recentKeys.size > 100) recentKeys.iterator().let { it.next(); it.remove() }
        }

        listener?.invoke(data)
        return true
    }
}
