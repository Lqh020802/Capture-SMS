package com.capturesms.keepalive

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.dcloud.feature.uniapp.annotation.UniJSMethod
import io.dcloud.feature.uniapp.bridge.UniJSCallback
import io.dcloud.feature.uniapp.common.UniModule

class KeepaliveModule : UniModule() {

    private val mainHandler = Handler(Looper.getMainLooper())

    @UniJSMethod(uiThread = false)
    fun startService(options: Map<String, Any?>?, callback: UniJSCallback?) {
        val ctx = mWXSDKInstance?.context ?: return
        options?.let {
            SmsEventEmitter.serverUrl = it["serverUrl"] as? String ?: ""
            SmsEventEmitter.serverToken = it["token"] as? String ?: ""
            SmsEventEmitter.deviceId = it["deviceId"] as? String ?: ""
            val installTimestamp = when (val value = it["installTimestamp"]) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull() ?: 0L
                else -> 0L
            }
            SmsEventEmitter.setInstallTimestamp(ctx, installTimestamp)
        }

        val intent = Intent(ctx, KeepaliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }

        callback?.invoke(mapOf("code" to 0, "msg" to "服务已启动"))
    }

    @UniJSMethod(uiThread = false)
    fun stopService(callback: UniJSCallback?) {
        val ctx = mWXSDKInstance?.context ?: return
        val intent = Intent(ctx, KeepaliveService::class.java).apply {
            action = KeepaliveService.ACTION_STOP
        }
        ctx.startService(intent)
        SmsEventEmitter.removeListener()
        SmsEventEmitter.removePhoneListener()
        callback?.invoke(mapOf("code" to 0, "msg" to "服务已停止"))
    }

    @UniJSMethod(uiThread = false)
    fun onSmsReceived(callback: UniJSCallback?) {
        if (callback == null) return

        SmsEventEmitter.setListener { data ->
            mainHandler.post {
                callback.invokeAndKeepAlive(data)
            }
        }
    }

    @UniJSMethod(uiThread = false)
    fun onPhoneEventReceived(callback: UniJSCallback?) {
        if (callback == null) return

        SmsEventEmitter.setPhoneListener { data ->
            mainHandler.post {
                callback.invokeAndKeepAlive(data)
            }
        }
    }

    @UniJSMethod(uiThread = false)
    fun isRunning(callback: UniJSCallback?) {
        val ctx = mWXSDKInstance?.context ?: return
        val running = isServiceRunning(ctx, KeepaliveService::class.java)
        callback?.invoke(mapOf("running" to running))
    }

    private fun isServiceRunning(ctx: Context, serviceClass: Class<*>): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE)
            ?.any { it.service.className == serviceClass.name } == true
    }
}
