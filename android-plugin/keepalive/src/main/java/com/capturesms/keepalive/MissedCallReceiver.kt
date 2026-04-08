package com.capturesms.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log

class MissedCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CaptureSMS"
        private const val QUERY_DELAY_MS = 1800L

        private var lastState: String? = TelephonyManager.EXTRA_STATE_IDLE
        private var incomingNumber: String = ""
        private var ringingTimestamp: Long = 0L
        private var answeredTimestamp: Long = 0L
        private var lastMissedQueryAt: Long = 0L
        private val handler = Handler(Looper.getMainLooper())
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
                answeredTimestamp = 0L
                lastState = state
                Log.d(TAG, "incoming call ringing number=$incomingNumber")
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (lastState == TelephonyManager.EXTRA_STATE_RINGING) {
                    answeredTimestamp = System.currentTimeMillis()
                }
                lastState = state
                Log.d(TAG, "call answered number=${if (number.isNotEmpty()) number else incomingNumber}")
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val subId = extractSubscriptionId(intent)
                val slotIndex = extractSlotIndex(context, intent, subId)
                val fallbackNumber = if (number.isNotEmpty()) number else incomingNumber

                when (lastState) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        scheduleMissedCallCheck(
                            context.applicationContext,
                            subId,
                            slotIndex,
                            fallbackNumber,
                            ringingTimestamp
                        )
                    }

                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        emitCompletedCall(
                            context.applicationContext,
                            subId,
                            slotIndex,
                            fallbackNumber,
                            ringingTimestamp,
                            answeredTimestamp,
                            System.currentTimeMillis()
                        )
                    }
                }

                incomingNumber = ""
                ringingTimestamp = 0L
                answeredTimestamp = 0L
                lastState = state
            }
        }
    }

    private fun emitCompletedCall(
        context: Context,
        subId: Int,
        slotIndex: Int,
        fallbackNumber: String,
        ringAt: Long,
        answeredAt: Long,
        endAt: Long
    ) {
        val phoneNumber = PhoneUtils.getPhoneNumber(context, subId, slotIndex)
        val simName = PhoneUtils.getSimName(context, subId, slotIndex, phoneNumber)
        val record = mapOf(
            "event_type" to "call_completed",
            "sender" to if (fallbackNumber.isNotEmpty()) fallbackNumber else "未知号码",
            "body" to "通话已结束",
            "sim_slot" to slotIndex,
            "sim_name" to simName,
            "phone_number" to phoneNumber,
            "timestamp" to endAt,
            "ring_timestamp" to if (ringAt > 0L) ringAt else endAt,
            "answered_timestamp" to if (answeredAt > 0L) answeredAt else endAt,
            "end_timestamp" to endAt,
            "duration" to if (answeredAt > 0L && endAt >= answeredAt) (endAt - answeredAt) / 1000L else 0L
        )
        Log.d(TAG, "call completed: $record")
        SmsEventEmitter.emitPhoneEvent(record)
    }

    private fun scheduleMissedCallCheck(
        context: Context,
        subId: Int,
        slotIndex: Int,
        fallbackNumber: String,
        ringAt: Long
    ) {
        val startedAt = if (ringAt > 0L) ringAt else System.currentTimeMillis()
        handler.postDelayed({
            val record = queryLatestMissedCall(context, subId, slotIndex, startedAt)
                ?: buildFallbackRecord(context, subId, slotIndex, fallbackNumber, startedAt)
            if (record != null) {
                Log.d(TAG, "missed call detected: $record")
                SmsEventEmitter.emitPhoneEvent(record)
            } else {
                Log.d(TAG, "missed call check finished but no usable record found")
            }
        }, QUERY_DELAY_MS)
    }

    private fun queryLatestMissedCall(
        context: Context,
        subId: Int,
        slotIndex: Int,
        startedAt: Long
    ): Map<String, Any?>? {
        var cursor: android.database.Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION
                ),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?",
                arrayOf(
                    CallLog.Calls.MISSED_TYPE.toString(),
                    (startedAt - 30_000L).toString()
                ),
                "${CallLog.Calls.DATE} DESC"
            )

            if (cursor == null || !cursor.moveToFirst()) return null

            val date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE))
            if (date <= lastMissedQueryAt) return null
            lastMissedQueryAt = date

            val number = PhoneUtils.normalizePhoneNumber(
                cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
            )
            val duration = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION))
            val phoneNumber = PhoneUtils.getPhoneNumber(context, subId, slotIndex)
            val simName = PhoneUtils.getSimName(context, subId, slotIndex, phoneNumber)

            mapOf(
                "event_type" to "missed_call",
                "sender" to if (number.isNotEmpty()) number else "未知号码",
                "body" to "未接来电",
                "sim_slot" to slotIndex,
                "sim_name" to simName,
                "phone_number" to phoneNumber,
                "timestamp" to date,
                "duration" to duration
            )
        } catch (e: Exception) {
            Log.e(TAG, "query missed call log failed", e)
            null
        } finally {
            cursor?.close()
        }
    }

    private fun buildFallbackRecord(
        context: Context,
        subId: Int,
        slotIndex: Int,
        fallbackNumber: String,
        startedAt: Long
    ): Map<String, Any?>? {
        val phoneNumber = PhoneUtils.getPhoneNumber(context, subId, slotIndex)
        val simName = PhoneUtils.getSimName(context, subId, slotIndex, phoneNumber)
        if (fallbackNumber.isEmpty() && simName.isEmpty() && phoneNumber.isEmpty()) return null

        return mapOf(
            "event_type" to "missed_call",
            "sender" to if (fallbackNumber.isNotEmpty()) fallbackNumber else "未知号码",
            "body" to "未接来电",
            "sim_slot" to slotIndex,
            "sim_name" to simName,
            "phone_number" to phoneNumber,
            "timestamp" to startedAt,
            "duration" to 0L
        )
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
