package com.capturesms.keepalive

import kotlinx.coroutines.*
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 直接 HTTP 上报工具（不依赖 UniApp 网络层，便于 App 被杀时使用）
 */
object SmsUploader {

    fun upload(url: String, token: String, deviceId: String, record: Map<String, Any?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = buildJson(deviceId, record)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(json) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    fun uploadPhoneStatus(url: String, token: String, simName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = buildPhoneJson(simName)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(json) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun buildJson(deviceId: String, record: Map<String, Any?>): String {
        val body = (record["body"] as? String)?.replace("\"", "\\\"") ?: ""
        val phoneNumber = (record["phone_number"] as? String)?.replace("\"", "\\\"") ?: ""
        return """{"device_id":"$deviceId","sender":"${record["sender"]}","body":"$body","sim_slot":${record["sim_slot"]},"sim_name":"${record["sim_name"]}","phone_number":"$phoneNumber","timestamp":${record["timestamp"]}}"""
    }

    private fun buildPhoneJson(simName: String): String {
        val value = simName.replace("\"", "\\\"")
        return """{"phone":"$value"}"""
    }
}
