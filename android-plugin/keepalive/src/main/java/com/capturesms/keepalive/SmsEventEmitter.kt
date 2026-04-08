package com.capturesms.keepalive

import android.content.Context

object SmsEventEmitter {

    private const val PREFS_NAME = "capture_sms_keepalive"
    private const val KEY_INSTALL_TIMESTAMP = "install_timestamp"

    private var listener: ((Map<String, Any?>) -> Unit)? = null
    private var phoneListener: ((Map<String, Any?>) -> Unit)? = null

    var serverUrl: String = ""
    var serverToken: String = ""
    var deviceId: String = ""
    var installTimestamp: Long = 0L

    private val recentKeys = LinkedHashSet<String>()

    fun setListener(fn: (Map<String, Any?>) -> Unit) {
        listener = fn
    }

    fun removeListener() {
        listener = null
    }

    fun hasListener(): Boolean = listener != null

    fun setPhoneListener(fn: (Map<String, Any?>) -> Unit) {
        phoneListener = fn
    }

    fun removePhoneListener() {
        phoneListener = null
    }

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

    fun emit(data: Map<String, Any?>): Boolean {
        val sender = data["sender"] as? String ?: ""
        val timestamp = data["timestamp"] as? Long ?: 0L
        val key = "$sender:${timestamp / 10000}"

        synchronized(recentKeys) {
            if (recentKeys.contains(key)) return false
            recentKeys.add(key)
            if (recentKeys.size > 100) {
                recentKeys.iterator().let {
                    it.next()
                    it.remove()
                }
            }
        }

        listener?.invoke(data)
        return true
    }

    fun emitPhoneEvent(data: Map<String, Any?>) {
        phoneListener?.invoke(data)
    }
}
