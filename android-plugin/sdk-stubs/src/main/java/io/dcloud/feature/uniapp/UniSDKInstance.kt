package io.dcloud.feature.uniapp

import android.content.Context

/** UniApp SDK 桩类 —— 仅用于编译，运行时由真实 SDK 替换 */
open class UniSDKInstance {
    open val context: Context? = null
}
