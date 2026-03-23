import { uploadSms, loadConfig } from './api.js'

let isMonitoring = false

// 全局事件总线
const eventBus = {
    listeners: {},
    on(event, fn)    { this.listeners[event] = fn },
    emit(event, data){ this.listeners[event] && this.listeners[event](data) }
}
export { eventBus }

// 模拟短信（测试用）
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
        try { keepalivePlugin = uni.requireNativePlugin('Capture-Keepalive') } catch (e) {}
    }
    return keepalivePlugin
}
// #endif

export function startSmsMonitor() {
    if (isMonitoring) return
    // #ifdef APP-PLUS
    const plugin = getPlugin()
    if (plugin) _startWithPlugin(plugin)   // 后台保活
    _startPolling()                         // 主力：轮询数据库（不依赖广播）
    _startWithPlusAndroid()                 // 辅助：广播接收器
    isMonitoring = true
    // #endif
}

export function stopSmsMonitor() {
    // #ifdef APP-PLUS
    const plugin = getPlugin()
    if (plugin) plugin.stopService(() => {})
    _stopPolling()
    isMonitoring = false
    // #endif
}

export function getMonitorStatus() { return isMonitoring }

// ─── 1. 原生插件（后台保活）────────────────────────────────────────────

function _startWithPlugin(plugin) {
    const config = loadConfig()
    plugin.startService({
        serverUrl : config.serverUrl || '',
        token     : config.token     || '',
        deviceId  : _getDeviceId()
    }, () => {})
    plugin.onSmsReceived((record) => { _handleSms(record) })
    console.log('[SMS] 原生插件已启动')
}

// ─── 2. 轮询数据库（主力，不受广播限制）──────────────────────────────

let _pollTimer  = null
let _lastSmsId  = -1

function _startPolling() {
    _lastSmsId = _queryMaxSmsId()
    console.log('[SMS] 开始轮询，初始ID:', _lastSmsId)
    _pollTimer = setInterval(_pollNewSms, 2000)
}

function _stopPolling() {
    if (_pollTimer) { clearInterval(_pollTimer); _pollTimer = null }
}

function _queryMaxSmsId() {
    try {
        const Uri      = plus.android.importClass('android.net.Uri')
        const resolver = plus.android.runtimeMainActivity().getContentResolver()
        plus.android.importClass(resolver)
        const cursor = resolver.query(Uri.parse('content://sms'), null, null, null, '_id DESC')
        if (!cursor) return 0
        plus.android.importClass(cursor)
        const id = cursor.moveToFirst() ? cursor.getLong(cursor.getColumnIndex('_id')) : 0
        cursor.close()
        return id
    } catch (e) { console.error('[SMS] 查询初始ID失败', e) }
    return 0
}

function _pollNewSms() {
    try {
        const Uri      = plus.android.importClass('android.net.Uri')
        const resolver = plus.android.runtimeMainActivity().getContentResolver()
        plus.android.importClass(resolver)
        // 不传 projection，避免 JS 数组转 Java String[] 失败
        const cursor = resolver.query(
            Uri.parse('content://sms'),
            null,
            '_id > ' + _lastSmsId,
            null,
            '_id ASC'
        )
        if (!cursor) return
        plus.android.importClass(cursor)

        while (cursor.moveToNext()) {
            const id   = cursor.getLong(cursor.getColumnIndex('_id'))
            const type = cursor.getInt(cursor.getColumnIndex('type'))
            if (id > _lastSmsId) _lastSmsId = id
            if (type !== 1) continue  // type=1 收件箱

            const sender = cursor.getString(cursor.getColumnIndex('address')) || ''
            const body   = cursor.getString(cursor.getColumnIndex('body'))   || ''
            const date   = cursor.getLong(cursor.getColumnIndex('date'))
            const subId  = cursor.getInt(cursor.getColumnIndex('subscription_id'))
            const slot   = _getSimSlotFromSubId(subId)
            const name   = _getSimNameFromSubId(subId, slot)

            console.log('[SMS] 轮询发现新短信 from:', sender)
            _handleSms({ sender, body, sim_slot: slot, sim_name: name, timestamp: date })
        }
        cursor.close()
    } catch (e) { console.error('[SMS] 轮询失败', e) }
}

// ─── 3. 广播接收器（辅助）────────────────────────────────────────────

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
                    const bundle    = intent.getExtras()
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
                    console.log('[SMS] 广播收到短信 from:', sender)
                    _handleSms({ sender, body, sim_slot: slotIndex, sim_name: simName, timestamp: Date.now() })
                } catch (e) { console.error('[SMS] 广播解析失败', e) }
            }
        })
        plus.android.runtimeMainActivity().registerReceiver(_receiver, filter)
        console.log('[SMS] 广播接收器已注册')
    } catch (e) { console.error('[SMS] 广播注册失败', e) }
}

// ─── 公共处理 ─────────────────────────────────────────────────────────

const _recentKeys = new Set()
function _isDuplicate(record) {
    const key = `${record.sender}:${String(record.body).slice(0,20)}:${Math.floor((record.timestamp||0) / 10000)}`
    if (_recentKeys.has(key)) return true
    _recentKeys.add(key)
    if (_recentKeys.size > 100) _recentKeys.clear()
    return false
}

function _handleSms(record) {
    if (_isDuplicate(record)) return
    eventBus.emit('sms', record)
    uploadSms(record)
}

// ─── 工具方法 ─────────────────────────────────────────────────────────

function _getDeviceId() {
    try {
        const Settings = plus.android.importClass('android.provider.Settings')
        const activity = plus.android.runtimeMainActivity()
        return Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID) || 'unknown'
    } catch { return 'unknown' }
}

function _getSimSlotFromSubId(subId) {
    try {
        const SubscriptionManager = plus.android.importClass('android.telephony.SubscriptionManager')
        const manager = SubscriptionManager.from(plus.android.runtimeMainActivity())
        plus.android.importClass(manager)
        const info = manager.getActiveSubscriptionInfo(subId)
        if (info) { plus.android.importClass(info); return info.getSimSlotIndex() }
    } catch {}
    return 0
}

function _getSimNameFromSubId(subId, slotIndex) {
    try {
        const SubscriptionManager = plus.android.importClass('android.telephony.SubscriptionManager')
        const manager = SubscriptionManager.from(plus.android.runtimeMainActivity())
        plus.android.importClass(manager)
        const info = manager.getActiveSubscriptionInfo(subId)
        if (info) { plus.android.importClass(info); return info.getDisplayName() + '' }
    } catch {}
    return `SIM${slotIndex + 1}`
}

function _getSimName(context, subId, slotIndex) {
    try {
        const manager = context.getSystemService('telephony_subscription_service')
        plus.android.importClass(manager)
        const info = subId !== -1
            ? manager.getActiveSubscriptionInfo(subId)
            : (manager.getActiveSubscriptionInfoList()?.toArray() || []).find(i => {
                plus.android.importClass(i); return i.getSimSlotIndex() === slotIndex
              })
        if (info) { plus.android.importClass(info); return info.getDisplayName() + '' }
    } catch {}
    return slotIndex >= 0 ? `SIM${slotIndex + 1}` : 'SIM'
}
