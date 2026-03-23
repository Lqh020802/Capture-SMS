// ─── 服务器配置（直接硬编码）────────────────────────────────
const SERVER_URL = 'http://192.168.30.194:8014/sms/upload'
const TOKEN      = ''  // 留空 = 不鉴权
const PENDING_KEY = 'sms_pending'

let _retrying = false  // 防止重试重入

/**
 * 上报短信到服务器（新短信入口，成功后触发重试队列）
 */
export function uploadSms(record) {
    _request(record, true)
}

/**
 * 内部请求，isNew=true 时成功后触发 retryPending
 */
function _request(record, isNew) {
    const deviceId = getDeviceId()
    const data = {
        device_id : deviceId,
        sender    : record.sender,
        body      : record.body,
        sim_slot  : record.sim_slot,
        sim_name  : record.sim_name,
        timestamp : record.timestamp
    }

    const header = { 'Content-Type': 'application/json' }
    if (TOKEN) header['Authorization'] = 'Bearer ' + TOKEN

    uni.request({
        url    : SERVER_URL,
        method : 'POST',
        data,
        header,
        timeout: 10000,
        success(res) {
            if (res.statusCode >= 200 && res.statusCode < 300) {
                console.log('[API] 上报成功', res.data)
                if (isNew) retryPending()
            } else {
                console.error('[API] 上报失败', res.statusCode, res.data)
                savePending(record)
            }
        },
        fail(err) {
            console.error('[API] 网络错误', err)
            savePending(record)
        }
    })
}

// ─── 保留给 SmsReceiver.kt 兜底上报使用 ─────────────────────
export function loadConfig() {
    return { serverUrl: SERVER_URL, token: TOKEN }
}

export function saveConfig() {}  // 硬编码模式下无需保存

// ─── 重试缓存 ────────────────────────────────────────────────

function retryPending() {
    if (_retrying) return
    const list = getPending()
    if (!list.length) return
    _retrying = true
    savePendingList([])  // 先清空，避免重复重试
    let idx = 0
    function next() {
        if (idx >= list.length) { _retrying = false; return }
        _request(list[idx++], false)
        setTimeout(next, 1000)
    }
    next()
}

// ─── 本地缓存 ────────────────────────────────────────────────

function savePending(record) {
    const list = getPending()
    list.push(record)
    if (list.length > 200) list.splice(0, list.length - 200)
    savePendingList(list)
}

function getPending() {
    try {
        const str = uni.getStorageSync(PENDING_KEY)
        return str ? JSON.parse(str) : []
    } catch { return [] }
}

function savePendingList(list) {
    uni.setStorageSync(PENDING_KEY, JSON.stringify(list))
}

// ─── 设备 ID ─────────────────────────────────────────────────

function getDeviceId() {
    // #ifdef APP-PLUS
    try {
        const Settings = plus.android.importClass('android.provider.Settings')
        const activity = plus.android.runtimeMainActivity()
        const resolver = activity.getContentResolver()
        return Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID) || 'unknown'
    } catch { return 'unknown' }
    // #endif
    return 'unknown'
}
