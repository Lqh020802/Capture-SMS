package io.dcloud.feature.uniapp.bridge

/** UniJSCallback 桩接口 —— 仅用于编译，运行时由真实 SDK 替换 */
interface UniJSCallback {
    fun invoke(data: Any?)
    fun invokeAndKeepAlive(data: Any?)
}
