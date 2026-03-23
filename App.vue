<script>
import { startSmsMonitor } from '@/utils/sms-monitor.js'

export default {
    onLaunch() {
        // #ifdef APP-PLUS
        this._requestPermissions()
        // #endif
    },

    onShow() { },
    onHide() { },

    methods: {
        _requestPermissions() {
            const permissions = [
                'android.permission.RECEIVE_SMS',
                'android.permission.READ_SMS',
                'android.permission.READ_PHONE_STATE'
            ]
            plus.android.requestPermissions(
                permissions,
                (result) => {
                    const denied = result.deniedAlways.concat(result.deniedPresent)
                    if (denied.length > 0) {
                        uni.showModal({
                            title: '权限不足',
                            content: '需要短信和电话权限才能正常工作，请在设置中手动开启',
                            showCancel: false,
                            confirmText: '去设置',
                            success: () => {
                                const Intent   = plus.android.importClass('android.content.Intent')
                                const Settings = plus.android.importClass('android.provider.Settings')
                                const Uri      = plus.android.importClass('android.net.Uri')
                                const intent   = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.setData(Uri.fromParts('package', plus.runtime.appid, null))
                                plus.android.runtimeMainActivity().startActivity(intent)
                            }
                        })
                    } else {
                        startSmsMonitor()
                        this._checkXiaomiTip()
                    }
                },
                (error) => { console.error('权限申请失败', error) }
            )
        },

        _checkXiaomiTip() {
            try {
                const Build = plus.android.importClass('android.os.Build')
                const manufacturer = (Build.MANUFACTURER + '').toLowerCase()
                if (!manufacturer.includes('xiaomi')) return
                if (uni.getStorageSync('miui_tip_shown')) return
                uni.setStorageSync('miui_tip_shown', '1')
                uni.showModal({
                    title: '小米设备额外设置',
                    content: '请前往：设置 → 应用管理 → 本应用 → 权限\n\n开启「短信」和「通知类短信」权限，否则验证码可能无法捕获。',
                    confirmText: '去开启',
                    cancelText: '稍后',
                    success: (res) => {
                        if (!res.confirm) return
                        const Intent   = plus.android.importClass('android.content.Intent')
                        const Settings = plus.android.importClass('android.provider.Settings')
                        const Uri      = plus.android.importClass('android.net.Uri')
                        const intent   = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.setData(Uri.fromParts('package', plus.runtime.appid, null))
                        plus.android.runtimeMainActivity().startActivity(intent)
                    }
                })
            } catch (e) {}
        }
    }
}
</script>
<style>
/* 适配 iOS 底部安全区背景色 */
</style>
