package com.capturesms.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import kotlinx.coroutines.*
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 静态短信广播接收器
 * 在 AndroidManifest 中注册，App 被杀死后系统仍可唤醒此 Receiver
 * 收到短信后：
 *   1. 通过 SmsEventEmitter 通知 JS 层（App 运行时）
 *   2. 直接 HTTP 上报服务器（App 被杀时）
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        val pdus   = intent.extras?.get("pdus") as? Array<*> ?: return
        val format = intent.extras?.getString("format") ?: "pdu"

        val slotIndex = intent.getIntExtra("android.telephony.extra.SLOT_INDEX", -1)
        val subId     = intent.getIntExtra("subscription", -1)

        // 解析短信
        var sender = ""
        val body   = StringBuilder()
        for (pdu in pdus) {
            val msg = SmsMessage.createFromPdu(pdu as ByteArray, format)
            sender  = msg.originatingAddress ?: sender
            body.append(msg.messageBody ?: "")
        }

        val simName = getSimName(context, subId, slotIndex)

        val record = mapOf(
            "sender"    to sender,
            "body"      to body.toString(),
            "sim_slot"  to slotIndex,
            "sim_name"  to simName,
            "timestamp" to System.currentTimeMillis()
        )

        // 1. 通知 JS 层（如果 App 在前台/后台运行）
        SmsEventEmitter.emit(record)

        // 2. 兜底直传：仅当 JS 层无监听（App 已被杀）时才直接上报，避免重复
        if (!SmsEventEmitter.hasListener()) {
            val url   = SmsEventEmitter.serverUrl.ifEmpty { "http://192.168.30.194:8014/sms/upload" }
            val token = SmsEventEmitter.serverToken.ifEmpty { "" }
            val did   = SmsEventEmitter.deviceId
            uploadDirect(url, token, did, record)
        }

        // 3. 确保保活服务继续运行
        restartService(context)
    }

    /**
     * 直接 HTTP 上报（不依赖 UniApp 网络层）
     */
    private fun uploadDirect(
        url: String, token: String, deviceId: String,
        record: Map<String, Any?>
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = buildJsonString(deviceId, record)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod     = "POST"
                    connectTimeout    = 10_000
                    readTimeout       = 10_000
                    doOutput          = true
                    setRequestProperty("Content-Type", "application/json")
                    if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(json) }
                conn.responseCode // 触发请求
                conn.disconnect()
            } catch (e: Exception) {
                // 网络失败：由 App 前台的重传机制处理
            }
        }
    }

    private fun buildJsonString(deviceId: String, record: Map<String, Any?>): String {
        return """{"device_id":"$deviceId","sender":"${record["sender"]}","body":"${
            (record["body"] as? String)?.replace("\"", "\\\"")
        }","sim_slot":${record["sim_slot"]},"sim_name":"${record["sim_name"]}","timestamp":${record["timestamp"]}}"""
    }

    /**
     * 确保保活服务在运行（App 被唤醒后重启服务）
     */
    private fun restartService(context: Context) {
        try {
            val intent = Intent(context, KeepaliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {}
    }

    private fun getSimName(context: Context, subId: Int, slotIndex: Int): String {
        return try {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SubscriptionManager.from(context)
            } else return "SIM${slotIndex + 1}"

            val info = if (subId != -1) manager.getActiveSubscriptionInfo(subId)
                       else manager.activeSubscriptionInfoList
                           ?.firstOrNull { it.simSlotIndex == slotIndex }

            info?.displayName?.toString() ?: "SIM${slotIndex + 1}"
        } catch (_: Exception) {
            "SIM${slotIndex + 1}"
        }
    }
}
