package com.capturesms.keepalive

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

object PhoneUtils {

    fun getSlotIndex(context: Context, subId: Int): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && subId != -1) {
                SubscriptionManager.from(context).getActiveSubscriptionInfo(subId)?.simSlotIndex ?: -1
            } else {
                -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    fun getSimName(context: Context, subId: Int, slotIndex: Int, phoneNumber: String): String {
        return try {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SubscriptionManager.from(context)
            } else return fallbackSimName(phoneNumber)

            val info = if (subId != -1) {
                manager.getActiveSubscriptionInfo(subId)
            } else {
                manager.activeSubscriptionInfoList?.firstOrNull { it.simSlotIndex == slotIndex }
            }

            val displayName = info?.displayName?.toString()?.trim().orEmpty()
            if (displayName.isNotEmpty()) displayName else fallbackSimName(phoneNumber)
        } catch (_: Exception) {
            fallbackSimName(phoneNumber)
        }
    }

    fun getPhoneNumber(context: Context, subId: Int, slotIndex: Int): String {
        return try {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SubscriptionManager.from(context)
            } else return ""

            val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && subId != -1) {
                normalizePhoneNumber(manager.getPhoneNumber(subId)).ifEmpty {
                    normalizePhoneNumber(manager.getActiveSubscriptionInfo(subId)?.number)
                }
            } else {
                val info = if (subId != -1) {
                    manager.getActiveSubscriptionInfo(subId)
                } else {
                    manager.activeSubscriptionInfoList?.firstOrNull { it.simSlotIndex == slotIndex }
                }
                normalizePhoneNumber(info?.number)
            }
            if (number.isNotEmpty()) return number

            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return ""
            val telephonyForSub = if (subId != -1) telephony.createForSubscriptionId(subId) else telephony
            normalizePhoneNumber(telephonyForSub.line1Number)
        } catch (_: Exception) {
            ""
        }
    }

    fun normalizePhoneNumber(phoneNumber: String?): String {
        return phoneNumber.orEmpty()
            .removePrefix("+86")
            .replace(Regex("\\D"), "")
            .trim()
    }

    private fun fallbackSimName(phoneNumber: String): String {
        val normalizedPhone = normalizePhoneNumber(phoneNumber)
        return if (normalizedPhone.isNotEmpty()) normalizedPhone else ""
    }
}
