// 默认配置 key
const CONFIG_KEY   = 'sms_config'
const PENDING_KEY  = 'sms_pending'

/**
 * 读取服务器配置
 */
function getConfig() {
    try {
        const str = uni.getStorageSync(CONFIG_KEY)
        return str ? JSON.parse(str) : {}
    } catch {
        return {}
    }
}

/**
 * 保存服务器配置
 */
export function saveConfig(config) {
    uni.setStorageSync(CONFIG_KEY, JSON.stringify(config))
}

/**
 * 读取配置（供设置页使用）
 */
export function loadConfig() {
    return getConfig()
}

/**
 * 上报短信到服务器
 */
export function uploadSms(record) {
    const config = getConfig()
    const url    = config.serverUrl || ''
    const token  = config.token     || ''

    if (!url) {
        console.warn('[API] 未配置服务器地址，跳过上报')
        savePending(record)
        return
    }

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
    if (token) header['Authorization'] = 'Bearer ' + token

    uni.request({
        url,
        method : 'POST',
        data,
        header,
        timeout: 10000,
        success(res) {
            if (res.statusCode >= 200 && res.statusCode < 300) {
                console.log('[API] 上报成功', res.data)
                // 上报成功后尝试发送缓存的失败记录
                retryPending()
            } else {
                console.error('[API] 上报失败', res.statusCode)
                savePending(record)
            }
        },
        fail(err) {
            console.error('[API] 网络错误', err)
            savePending(record)
        }
    })
}

/**
 * 重试发送缓存的失败记录
 */
function retryPending() {
    const list = getPending()
    if (!list.length) return

    const config = getConfig()
    if (!config.serverUrl) return

    // 逐条重试，成功则移除
    const remaining = []
    let idx = 0

    function next() {
        if (idx >= list.length) {
            savePendingList(remaining)
            return
        }
        const record = list[idx++]
        uploadSms(record) // 异步，不等待
        setTimeout(next, 500)
    }
    next()
}

// ---- 本地缓存 ----

function savePending(record) {
    const list = getPending()
    list.push(record)
    // 最多缓存 200 条
    if (list.length > 200) list.splice(0, list.length - 200)
    savePendingList(list)
}

function getPending() {
    try {
        const str = uni.getStorageSync(PENDING_KEY)
        return str ? JSON.parse(str) : []
    } catch {
        return []
    }
}

function savePendingList(list) {
    uni.setStorageSync(PENDING_KEY, JSON.stringify(list))
}

// ---- 设备 ID ----

function getDeviceId() {
    // #ifdef APP-PLUS
    try {
        const Settings = plus.android.importClass('android.provider.Settings')
        const activity = plus.android.runtimeMainActivity()
        const resolver = activity.getContentResolver()
        return Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID) || 'unknown'
    } catch {
        return 'unknown'
    }
    // #endif
    return 'unknown'
}
