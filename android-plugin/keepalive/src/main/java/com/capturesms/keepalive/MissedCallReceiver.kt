package com.capturesms.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class MissedCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CaptureSMS"

        private var lastState: String? = TelephonyManager.EXTRA_STATE_IDLE
        private var incomingNumber: String = ""
        private var ringingTimestamp: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = PhoneUtils.normalizePhoneNumber(
            intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        )

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                incomingNumber = number
                ringingTimestamp = System.currentTimeMillis()
                lastState = state
                Log.d(TAG, "incoming call ringing number=$incomingNumber")
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                lastState = state
                Log.d(TAG, "call answered number=${if (number.isNotEmpty()) number else incomingNumber}")
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val lastKnownNumber = if (number.isNotEmpty()) number else incomingNumber
                if (lastState == TelephonyManager.EXTRA_STATE_RINGING && lastKnownNumber.isNotEmpty()) {
                    val subId = extractSubscriptionId(intent)
                    val slotIndex = extractSlotIndex(context, intent, subId)
                    val phoneNumber = PhoneUtils.getPhoneNumber(context, subId, slotIndex)
                    val simName = PhoneUtils.getSimName(context, subId, slotIndex, phoneNumber)
                    val record = mapOf(
                        "event_type" to "missed_call",
                        "sender" to lastKnownNumber,
                        "body" to "未接来电",
                        "sim_slot" to slotIndex,
                        "sim_name" to simName,
                        "phone_number" to phoneNumber,
                        "timestamp" to if (ringingTimestamp > 0L) ringingTimestamp else System.currentTimeMillis()
                    )

                    Log.d(TAG, "missed call detected: $record")
                    SmsEventEmitter.emitPhoneEvent(record)
                }

                incomingNumber = ""
                ringingTimestamp = 0L
                lastState = state
            }
        }
    }

    private fun extractSubscriptionId(intent: Intent): Int {
        val keys = listOf(
            "subscription",
            "subscription_id",
            "android.telephony.extra.SUBSCRIPTION_INDEX"
        )
        keys.forEach { key ->
            val value = intent.getIntExtra(key, -1)
            if (value != -1) return value
        }
        return -1
    }

    private fun extractSlotIndex(context: Context, intent: Intent, subId: Int): Int {
        val keys = listOf(
            "slot",
            "slot_id",
            "simSlot",
            "android.telephony.extra.SLOT_INDEX"
        )
        keys.forEach { key ->
            val value = intent.getIntExtra(key, -1)
            if (value != -1) return value
        }
        return PhoneUtils.getSlotIndex(context, subId)
    }
}
