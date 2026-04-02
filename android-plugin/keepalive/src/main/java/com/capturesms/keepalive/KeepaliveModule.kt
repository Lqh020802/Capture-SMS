package com.capturesms.keepalive

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.dcloud.feature.uniapp.annotation.UniJSMethod
import io.dcloud.feature.uniapp.bridge.UniJSCallback
import io.dcloud.feature.uniapp.common.UniModule

/**
 * UniApp 原生模块
 * JS 调用方式：
 *   const keepalive = uni.requireNativePlugin('Capture-Keepalive')
 *   keepalive.startService({ serverUrl, token, deviceId }, callback)
 *   keepalive.stopService()
 *   keepalive.onSmsReceived(callback)
 */
class KeepaliveModule : UniModule() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 启动前台保活服务
     * @param options { serverUrl, token, deviceId }
     */
    @UniJSMethod(uiThread = false)
    fun startService(options: Map<String, Any?>?, callback: UniJSCallback?) {
        val ctx = mWXSDKInstance?.context ?: return
        options?.let {
            SmsEventEmitter.serverUrl  = it["serverUrl"]  as? String ?: ""
            SmsEventEmitter.serverToken = it["token"]     as? String ?: ""
            SmsEventEmitter.deviceId   = it["deviceId"]  as? String ?: ""
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

    /**
     * 停止保活服务
     */
    @UniJSMethod(uiThread = false)
    fun stopService(callback: UniJSCallback?) {
        val ctx = mWXSDKInstance?.context ?: return
        val intent = Intent(ctx, KeepaliveService::class.java).apply {
            action = KeepaliveService.ACTION_STOP
        }
        ctx.startService(intent)
        SmsEventEmitter.removeListener()
        callback?.invoke(mapOf("code" to 0, "msg" to "服务已停止"))
    }

    /**
     * 注册短信接收回调
     * 每次收到短信都会触发此回调
     */
    @UniJSMethod(uiThread = false)
    fun onSmsReceived(callback: UniJSCallback?) {
        if (callback == null) return

        SmsEventEmitter.setListener { data ->
            // 切换到主线程回调 JS
            mainHandler.post {
                // keepCallback = true 表示可多次回调
                callback.invokeAndKeepAlive(data)
            }
        }
    }

    /**
     * 获取服务运行状态
     */
    @UniJSMethod(uiThread = false)
    fun isRunning(callback: UniJSCallback?) {
        val ctx = mWXSDKInstance?.context ?: return
        val running = isServiceRunning(ctx, KeepaliveService::class.java)
        callback?.invoke(mapOf("running" to running))
    }

    /**
     * 检查服务是否在运行
     */
    private fun isServiceRunning(ctx: Context, serviceClass: Class<*>): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE)
            ?.any { it.service.className == serviceClass.name } == true
    }
}
