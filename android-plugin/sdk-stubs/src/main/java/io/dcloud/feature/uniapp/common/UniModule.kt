package io.dcloud.feature.uniapp.common

import io.dcloud.feature.uniapp.UniSDKInstance

/** UniModule 桩类 —— 仅用于编译，运行时由真实 SDK 替换 */
open class UniModule {
    @JvmField
    var mWXSDKInstance: UniSDKInstance? = null
}
