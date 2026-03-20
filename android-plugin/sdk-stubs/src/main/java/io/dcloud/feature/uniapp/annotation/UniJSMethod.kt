package io.dcloud.feature.uniapp.annotation

/** UniJSMethod 桩注解 —— 仅用于编译，运行时由真实 SDK 替换 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class UniJSMethod(val uiThread: Boolean = true)
