package com.capturesms.keepalive

/**
 * 单例事件总线：连接静态 BroadcastReceiver 和 UniApp JS 层
 * 因为静态 Receiver 无法持有 Module 引用，通过此单例中转
 */
object SmsEventEmitter {

    // JS 层注册的回调（由 KeepaliveModule 设置）
    private var listener: ((Map<String, Any?>) -> Unit)? = null

    // 服务器配置（由 JS 层通过 Module 写入）
    var serverUrl: String = ""
    var serverToken: String = ""
    var deviceId: String = ""

    fun setListener(fn: (Map<String, Any?>) -> Unit) {
        listener = fn
    }

    fun removeListener() {
        listener = null
    }

    fun hasListener(): Boolean = listener != null

    /**
     * 触发 JS 事件（在主线程调用）
     */
    fun emit(data: Map<String, Any?>) {
        listener?.invoke(data)
    }
}
