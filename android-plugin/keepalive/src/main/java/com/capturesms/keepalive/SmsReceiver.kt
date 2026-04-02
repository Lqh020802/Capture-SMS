package com.capturesms.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Log

/**
 * 静态短信广播接收器
 * 在 AndroidManifest 中注册，App 被杀死后系统仍可唤醒此 Receiver
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        val pdus   = intent.extras?.get("pdus") as? Array<*> ?: return
        val format = intent.extras?.getString("format") ?: "pdu"

        val slotIndex = intent.getIntExtra("android.telephony.extra.SLOT_INDEX", -1)
        val subId     = intent.getIntExtra("subscription", -1)

        var sender = ""
        val body   = StringBuilder()
        for (pdu in pdus) {
            val msg = SmsMessage.createFromPdu(pdu as ByteArray, format)
            sender  = msg.originatingAddress ?: sender
            body.append(msg.messageBody ?: "")
        }

        val simName = getSimName(context, subId, slotIndex)
        val phoneNumber = getPhoneNumber(context, subId, slotIndex)

        val timestamp = System.currentTimeMillis()
        if (!SmsEventEmitter.shouldUpload(context, timestamp)) return

        val record = mapOf(
            "sender"    to sender,
            "body"      to body.toString(),
            "sim_slot"  to slotIndex,
            "sim_name"  to simName,
            "phone_number" to phoneNumber,
            "timestamp" to timestamp
        )

        // 通知 JS 层（App 运行时）
        val emitted = SmsEventEmitter.emit(record)

        // 兜底直传：仅当未重复且 JS 层无监听（App 已被杀）时才直接上报
        if (emitted && !SmsEventEmitter.hasListener()) {
            val url   = SmsEventEmitter.serverUrl.ifEmpty { "http://192.168.30.194:8014/sms/upload" }
            val token = SmsEventEmitter.serverToken.ifEmpty { "" }
            val did   = SmsEventEmitter.deviceId
            SmsUploader.upload(url, token, did, record)
        }

        restartService(context)
    }

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


    private fun getPhoneNumber(context: Context, subId: Int, slotIndex: Int): String {
        return try {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SubscriptionManager.from(context)
            } else return ""

            val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && subId != -1) {
                normalizePhoneNumber(manager.getPhoneNumber(subId)).ifEmpty {
                    normalizePhoneNumber(manager.getActiveSubscriptionInfo(subId)?.number)
                }
            } else {
                val info = if (subId != -1) manager.getActiveSubscriptionInfo(subId)
                           else manager.activeSubscriptionInfoList
                               ?.firstOrNull { it.simSlotIndex == slotIndex }
                normalizePhoneNumber(info?.number)
            }
            if (number.isNotEmpty()) return number

            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                ?: return ""
            val telephonyForSub = if (subId != -1) telephony.createForSubscriptionId(subId) else telephony
            val operatorName = telephonyForSub.simOperatorName.orEmpty()
            val line1Number = normalizePhoneNumber(telephonyForSub.line1Number)
            Log.d("CaptureSMS", "operator=$operatorName phone=$line1Number")
            line1Number
        } catch (_: Exception) {
            ""
        }
    }

    private fun normalizePhoneNumber(phoneNumber: String?): String {
        return phoneNumber.orEmpty().removePrefix("+86").trim()
    }
}
