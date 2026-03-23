package com.capturesms.keepalive

/**
 * 单例事件总线：连接静态 BroadcastReceiver 和 UniApp JS 层
 * 因为静态 Receiver 无法持有 Module 引用，通过此单例中转
 */
object SmsEventEmitter {

    private var listener: ((Map<String, Any?>) -> Unit)? = null

    var serverUrl: String = ""
    var serverToken: String = ""
    var deviceId: String = ""

    // 去重缓存：10秒内相同 sender+timestamp 只处理一次
    private val recentKeys = LinkedHashSet<String>()

    fun setListener(fn: (Map<String, Any?>) -> Unit) {
        listener = fn
    }

    fun removeListener() {
        listener = null
    }

    fun hasListener(): Boolean = listener != null

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
