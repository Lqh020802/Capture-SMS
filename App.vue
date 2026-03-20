<script>
    import { startSmsMonitor, stopSmsMonitor } from '@/utils/sms-monitor.js'

    export default {
        onLaunch() {
            // #ifdef APP-PLUS
            this._requestPermissions()
            // #endif
        },

        onShow() {},
        onHide() {},

        methods: {
            _requestPermissions() {
                const permissions = [
                    'android.permission.RECEIVE_SMS',
                    'android.permission.READ_SMS',
                    'android.permission.READ_PHONE_STATE'
                ]

                // Android 6+ 动态申请权限
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
                                    // 跳转到应用设置页
                                    const Intent = plus.android.importClass('android.content.Intent')
                                    const Settings = plus.android.importClass('android.provider.Settings')
                                    const Uri = plus.android.importClass('android.net.Uri')
                                    const intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    const uri = Uri.fromParts('package', plus.runtime.appid, null)
                                    intent.setData(uri)
                                    plus.android.runtimeMainActivity().startActivity(intent)
                                }
                            })
                        } else {
                            // 权限OK，启动监控
                            startSmsMonitor()
                        }
                    },
                    (error) => {
                        console.error('权限申请失败', error)
                    }
                )
            }
        }
    }
</script>
