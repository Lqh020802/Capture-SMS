import { uploadSms, loadConfig } from './api.js'

let isMonitoring = false

// 全局事件总线（通知页面刷新日志）
const eventBus = {
    listeners: {},
    on(event, fn)   { this.listeners[event] = fn },
    emit(event, data) { this.listeners[event] && this.listeners[event](data) }
}

export { eventBus }

// 供外部测试调用
export function simulateSms() {
    _handleSms({
        sender    : '10086',
        body      : '【模拟】验证码 888888，5分钟内有效。',
        sim_slot  : 0,
        sim_name  : 'SIM1',
        timestamp : Date.now()
    })
}

// #ifdef APP-PLUS
let keepalivePlugin = null

function getPlugin() {
    if (!keepalivePlugin) {
        try {
            keepalivePlugin = uni.requireNativePlugin('Capture-Keepalive')
        } catch (e) {
            console.error('[SMS] 原生插件加载失败，降级使用 plus.android', e)
        }
    }
    return keepalivePlugin
}
// #endif

/**
 * 启动短信监控（优先使用原生插件，降级用 plus.android）
 */
export function startSmsMonitor() {
    if (isMonitoring) return

    // #ifdef APP-PLUS
    const plugin = getPlugin()
    if (plugin) {
        _startWithPlugin(plugin)
    }
    // 无论插件是否可用，始终注册动态接收器（前台双保险）
    _startWithPlusAndroid()
    isMonitoring = true
    // #endif
}

/**
 * 停止监控
 */
export function stopSmsMonitor() {
    // #ifdef APP-PLUS
    const plugin = getPlugin()
    if (plugin) {
        plugin.stopService(() => {})
    }
    isMonitoring = false
    // #endif
}

export function getMonitorStatus() {
    return isMonitoring
}

// ─── 原生插件方式（推荐，支持后台保活） ──────────────────────────────

function _startWithPlugin(plugin) {
    const config   = loadConfig()
    const deviceId = _getDeviceId()

    plugin.startService({
        serverUrl : config.serverUrl || '',
        token     : config.token     || '',
        deviceId
    }, (res) => {
        console.log('[SMS] 保活服务启动:', res)
    })

    // 原生回调（后台/被杀时有效）
    plugin.onSmsReceived((record) => {
        _handleSms(record)
    })

    console.log('[SMS] 原生插件监控已启动')
}

// ─── plus.android 降级方式（App 被杀后失效）──────────────────────────

let _receiver = null

function _startWithPlusAndroid() {
    try {
        const IntentFilter = plus.android.importClass('android.content.IntentFilter')
        const SmsMessage   = plus.android.importClass('android.telephony.SmsMessage')

        const filter = new IntentFilter()
        filter.addAction('android.provider.Telephony.SMS_RECEIVED')
        filter.setPriority(999)

        _receiver = plus.android.implements('android.content.BroadcastReceiver', {
            onReceive(context, intent) {
                plus.android.importClass(intent)
                if (intent.getAction() !== 'android.provider.Telephony.SMS_RECEIVED') return

                try {
                    const slotIndex = intent.getIntExtra('android.telephony.extra.SLOT_INDEX', -1)
                    const subId     = intent.getIntExtra('subscription', -1)
                    const simName   = _getSimName(context, subId, slotIndex)

                    const bundle = intent.getExtras()
                    plus.android.importClass(bundle)
                    const pdus   = bundle.get('pdus')
                    const format = bundle.getString('format') || 'pdu'
                    const len    = plus.android.getAttribute(pdus, 'length')

                    let sender = '', body = ''
                    for (let i = 0; i < len; i++) {
                        const pdu    = plus.android.invoke(pdus, 'get', i)
                        const smsMsg = SmsMessage.createFromPdu(pdu, format)
                        plus.android.importClass(smsMsg)
                        sender += smsMsg.getOriginatingAddress() || ''
                        body   += smsMsg.getMessageBody()        || ''
                    }

                    _handleSms({ sender, body, sim_slot: slotIndex, sim_name: simName, timestamp: Date.now() })
                } catch (e) {
                    console.error('[SMS] 解析失败', e)
                }
            }
        })

        plus.android.runtimeMainActivity().registerReceiver(_receiver, filter)
        console.log('[SMS] plus.android 动态接收器已注册')
    } catch (e) {
        console.error('[SMS] 启动失败', e)
    }
}

// ─── 公共处理 ────────────────────────────────────────────────────────

// JS 层去重：10秒内相同 sender+body 只处理一次
const _recentKeys = new Set()
function _isDuplicate(record) {
    const key = `${record.sender}:${record.body}:${Math.floor(record.timestamp / 10000)}`
    if (_recentKeys.has(key)) return true
    _recentKeys.add(key)
    if (_recentKeys.size > 50) _recentKeys.clear()
    return false
}

function _handleSms(record) {
    if (_isDuplicate(record)) return
    eventBus.emit('sms', record)
    uploadSms(record)
}

function _getDeviceId() {
    try {
        const Settings = plus.android.importClass('android.provider.Settings')
        const activity = plus.android.runtimeMainActivity()
        const resolver = activity.getContentResolver()
        return Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID) || 'unknown'
    } catch {
        return 'unknown'
    }
}

function _getSimName(context, subId, slotIndex) {
    try {
        const manager = context.getSystemService('telephony_subscription_service')
        plus.android.importClass(manager)
        let info = null
        if (subId !== -1) {
            info = manager.getActiveSubscriptionInfo(subId)
        } else {
            const list = manager.getActiveSubscriptionInfoList()
            if (list) {
                plus.android.importClass(list)
                const size = list.size()
                for (let i = 0; i < size; i++) {
                    const item = list.get(i)
                    plus.android.importClass(item)
                    if (item.getSimSlotIndex() === slotIndex) { info = item; break }
                }
            }
        }
        if (info) { plus.android.importClass(info); return info.getDisplayName() + '' }
    } catch {}
    return slotIndex >= 0 ? `SIM${slotIndex + 1}` : 'SIM'
}
