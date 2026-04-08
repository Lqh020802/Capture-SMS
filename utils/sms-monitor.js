import { uploadSms, loadConfig, uploadPhoneStatus } from './api.js'
import { saveCallRecording } from './call-recording-store.js'
import { saveMissedCall } from './missed-call-store.js'

let isMonitoring = false
const INSTALL_TS_KEY = 'sms_install_timestamp'
const PHONE_REPORT_INTERVAL_MS = 10000
const RECORD_SCAN_DELAY_MS = 8000
const MIUI_RECORD_DIRS = [
    '/storage/emulated/0/MIUI/sound_recorder/call_rec',
    '/sdcard/MIUI/sound_recorder/call_rec',
    '/storage/emulated/0/Sounds/CallRecordings',
    '/storage/emulated/0/CallRecordings',
    '/sdcard/Sounds/CallRecordings',
    '/sdcard/CallRecordings'
]

const eventBus = {
    listeners: {},
    on(event, fn) {
        if (!this.listeners[event]) this.listeners[event] = []
        this.listeners[event].push(fn)
    },
    off(event, fn) {
        const list = this.listeners[event]
        if (!list) return
        this.listeners[event] = list.filter(item => item !== fn)
    },
    emit(event, data) {
        const list = this.listeners[event] || []
        list.forEach(fn => {
            try { fn(data) } catch (e) { console.error('[BUS] listener failed', e) }
        })
    }
}
export { eventBus }

export function simulateSms() {
    _handleSms({
        sender: '10086',
        body: 'mock sms',
        sim_slot: 0,
        sim_name: 'SIM1',
        phone_number: '',
        timestamp: Date.now()
    })
}

// #ifdef APP-PLUS
let keepalivePlugin = null
let _phoneReportTimer = null
let _lastRecordingPath = ''
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
    const installTimestamp = ensureInstallTimestamp()
    const plugin = getPlugin()
    if (plugin) _startWithPlugin(plugin, installTimestamp)
    _ensurePhoneReportFallback(plugin)
    _startPolling()
    _startWithPlusAndroid()
    isMonitoring = true
    // #endif
}

export function stopSmsMonitor() {
    // #ifdef APP-PLUS
    const plugin = getPlugin()
    if (plugin) plugin.stopService(() => {})
    _stopPhoneReportFallback()
    _stopPolling()
    if (_receiver) {
        try { plus.android.runtimeMainActivity().unregisterReceiver(_receiver) } catch (e) {}
        _receiver = null
    }
    isMonitoring = false
    // #endif
}

export function getMonitorStatus() { return isMonitoring }

function ensureInstallTimestamp() {
    let ts = Number(uni.getStorageSync(INSTALL_TS_KEY) || 0)
    if (!ts) {
        ts = Date.now()
        uni.setStorageSync(INSTALL_TS_KEY, String(ts))
    }
    return ts
}

function _shouldUploadSms(record) {
    const installTimestamp = ensureInstallTimestamp()
    const smsTimestamp = Number(record && record.timestamp ? record.timestamp : 0)
    return !smsTimestamp || smsTimestamp >= installTimestamp
}

function _startWithPlugin(plugin, installTimestamp) {
    const config = loadConfig()
    plugin.startService({
        serverUrl: config.serverUrl || '',
        token: config.token || '',
        deviceId: _getDeviceId(),
        installTimestamp
    }, () => {})
    plugin.onSmsReceived((record) => { _handleSms(record) })
    if (typeof plugin.onPhoneEventReceived === 'function') {
        plugin.onPhoneEventReceived((record) => { _handlePhoneEvent(record) })
    }
    console.log('[SMS] native plugin started')
}

function _handlePhoneEvent(record) {
    const eventType = String(record && record.event_type || '')
    if (eventType === 'missed_call') {
        console.log('[PHONE] missed call:', JSON.stringify(record))
        saveMissedCall(record)
        eventBus.emit('missed-call', record)
        return
    }
    if (eventType === 'call_completed') {
        console.log('[PHONE] call completed:', JSON.stringify(record))
        _scheduleRecordingScan(record)
    }
}

function _scheduleRecordingScan(callRecord) {
    setTimeout(() => {
        console.log('[PHONE] start recording scan:', JSON.stringify({
            sender: callRecord.sender,
            answered_timestamp: callRecord.answered_timestamp,
            end_timestamp: callRecord.end_timestamp
        }))
        _resolveLatestCallRecording(callRecord, (recording) => {
            if (!recording) {
                console.warn('[PHONE] no call recording matched')
                return
            }
            const merged = {
                ...callRecord,
                event_type: 'call_recording',
                file_path: recording.path,
                file_name: recording.name,
                file_size: recording.size,
                modified_at: recording.modifiedAt,
                timestamp: callRecord.end_timestamp || callRecord.timestamp || Date.now()
            }
            _lastRecordingPath = recording.path
            saveCallRecording(merged)
            eventBus.emit('call-recording', merged)
            console.log('[PHONE] call recording:', JSON.stringify(merged))
        })
    }, RECORD_SCAN_DELAY_MS)
}

function _resolveLatestCallRecording(callRecord, callback) {
    try {
        const startAt = Number(callRecord.answered_timestamp || callRecord.ring_timestamp || callRecord.timestamp || Date.now())
        const endAt = Number(callRecord.end_timestamp || callRecord.timestamp || Date.now())
        const number = _normalizePhoneNumber(callRecord.sender || '')
        const plugin = getPlugin()
        if (plugin && typeof plugin.queryLatestCallRecording === 'function') {
            plugin.queryLatestCallRecording({
                startAt,
                endAt,
                number,
                lastPath: _lastRecordingPath
            }, (result) => {
                const nativeMatch = result && result.path ? result : null
                if (nativeMatch) {
                    console.log('[PHONE] native recording matched:', JSON.stringify(nativeMatch))
                    callback(nativeMatch)
                    return
                }
                const fallback = _findLatestCallRecordingFallback({ startAt, endAt, number })
                callback(fallback)
            })
            return
        }
        callback(_findLatestCallRecordingFallback({ startAt, endAt, number }))
    } catch (e) {
        console.error('[PHONE] scan call recording failed', e)
        callback(null)
    }
}

function _findLatestCallRecordingFallback({ startAt, endAt, number }) {
    const mediaStoreMatch = _findLatestCallRecordingFromMediaStore({ startAt, endAt, number })
    if (mediaStoreMatch) return mediaStoreMatch
    return _findLatestCallRecordingFromDirs({ startAt, endAt, number })
}

function _findLatestCallRecordingFromMediaStore({ startAt, endAt, number }) {
    let cursor = null
    try {
        const MediaStoreAudio = plus.android.importClass('android.provider.MediaStore$Audio$Media')
        const activity = plus.android.runtimeMainActivity()
        const resolver = activity.getContentResolver()
        plus.android.importClass(resolver)
        const uri = MediaStoreAudio.EXTERNAL_CONTENT_URI
        const projection = ['_data', '_display_name', 'date_modified', '_size']
        cursor = resolver.query(
            uri,
            projection,
            '_data like ?',
            ['%MIUI/sound_recorder/call_rec%'],
            'date_modified DESC'
        )
        if (!cursor) {
            console.log('[PHONE] MediaStore query returned null')
            return null
        }
        plus.android.importClass(cursor)
        const candidates = []
        let count = 0
        while (cursor.moveToNext() && count < 30) {
            count++
            const path = String(cursor.getString(cursor.getColumnIndex('_data')) || '')
            const name = String(cursor.getString(cursor.getColumnIndex('_display_name')) || '')
            const modifiedAt = Number(cursor.getLong(cursor.getColumnIndex('date_modified'))) * 1000
            const fileSize = Number(cursor.getLong(cursor.getColumnIndex('_size')))
            const fileNumber = _extractRecordingNumber(name)
            const fileTimestamp = _extractRecordingTimestamp(name)
            console.log('[PHONE] MediaStore file detail:', JSON.stringify({
                path,
                name,
                modifiedAt,
                fileSize,
                fileNumber,
                fileTimestamp
            }))
            if (!path || path === _lastRecordingPath) continue
            const numberMatched = !!(number && fileNumber && fileNumber.includes(number))
            const timeAnchor = fileTimestamp || modifiedAt
            const withinWindow = timeAnchor >= startAt - 120_000 && timeAnchor <= endAt + 180_000
            const score = _scoreRecordingCandidate({
                modifiedAt,
                fileTimestamp,
                startAt,
                endAt,
                numberMatched
            })
            if (!withinWindow && !numberMatched) continue
            candidates.push({
                path,
                name,
                size: fileSize,
                modifiedAt,
                fileTimestamp,
                score
            })
        }
        candidates.sort((a, b) => b.score - a.score || b.modifiedAt - a.modifiedAt)
        console.log('[PHONE] MediaStore candidate count:', candidates.length)
        if (candidates[0]) {
            console.log('[PHONE] MediaStore best candidate:', JSON.stringify(candidates[0]))
        }
        return candidates[0] || null
    } catch (e) {
        console.error('[PHONE] MediaStore query failed', e)
        return null
    } finally {
        try { if (cursor) cursor.close() } catch (_) {}
    }
}

function _findLatestCallRecordingFromDirs({ startAt, endAt, number }) {
    try {
        const File = plus.android.importClass('java.io.File')
        const JavaArray = plus.android.importClass('java.lang.reflect.Array')
        const candidates = []
        MIUI_RECORD_DIRS.forEach(dirPath => {
            const dir = new File(dirPath)
            plus.android.importClass(dir)
            const exists = !!dir.exists()
            const isDirectory = exists && !!dir.isDirectory()
            console.log('[PHONE] scan dir:', JSON.stringify({ dirPath, exists, isDirectory }))
            if (!exists || !isDirectory) return
            const files = dir.listFiles()
            if (!files) {
                console.log('[PHONE] dir has no files:', dirPath)
                return
            }
            const length = JavaArray.getLength(files)
            console.log('[PHONE] dir file count:', JSON.stringify({ dirPath, count: length }))
            for (let i = 0; i < length; i++) {
                const file = JavaArray.get(files, i)
                if (!file) continue
                plus.android.importClass(file)
                if (!file.isFile()) continue
                const name = String(file.getName() || '')
                const lower = name.toLowerCase()
                const path = String(file.getAbsolutePath() || '')
                const modifiedAt = Number(file.lastModified())
                const fileSize = Number(file.length())
                const fileNumber = _extractRecordingNumber(name)
                const fileTimestamp = _extractRecordingTimestamp(name)
                console.log('[PHONE] dir file detail:', JSON.stringify({
                    dirPath,
                    name,
                    path,
                    modifiedAt,
                    fileSize,
                    fileNumber,
                    fileTimestamp
                }))
                if (!lower.endsWith('.mp3') && !lower.endsWith('.m4a') && !lower.endsWith('.amr') && !lower.endsWith('.aac') && !lower.endsWith('.wav')) continue
                if (!path || path === _lastRecordingPath) continue
                const numberMatched = !!(number && fileNumber && fileNumber.includes(number))
                const timeAnchor = fileTimestamp || modifiedAt
                const withinWindow = timeAnchor >= startAt - 120_000 && timeAnchor <= endAt + 180_000
                const score = _scoreRecordingCandidate({
                    modifiedAt,
                    fileTimestamp,
                    startAt,
                    endAt,
                    numberMatched
                })
                if (!withinWindow && !numberMatched) continue
                console.log('[PHONE] recording candidate:', JSON.stringify({
                    dirPath,
                    name,
                    fileNumber,
                    targetNumber: number,
                    numberMatched,
                    modifiedAt,
                    fileTimestamp,
                    startAt,
                    endAt,
                    score
                }))
                candidates.push({
                    path,
                    name,
                    size: Number(file.length()),
                    modifiedAt,
                    fileTimestamp,
                    score
                })
            }
        })

        candidates.sort((a, b) => b.score - a.score || b.modifiedAt - a.modifiedAt)
        console.log('[PHONE] recording candidate count:', candidates.length)
        if (candidates[0]) {
            console.log('[PHONE] recording best candidate:', JSON.stringify(candidates[0]))
        }
        return candidates[0] || null
    } catch (e) {
        console.error('[PHONE] dir scan failed', e)
        return null
    }
}

function _scoreRecordingCandidate({ modifiedAt, fileTimestamp, startAt, endAt, numberMatched }) {
    const anchor = fileTimestamp || modifiedAt
    const distanceToEnd = Math.abs(anchor - endAt)
    const distanceToStart = Math.abs(anchor - startAt)
    let score = 0
    if (numberMatched) score += 5000
    if (fileTimestamp) score += 1500
    score -= Math.floor(distanceToEnd / 1000)
    score -= Math.floor(distanceToStart / 5000)
    return score
}

function _extractRecordingNumber(name) {
    const match = String(name || '').match(/^(\d{6,})/)
    return match ? match[1] : _normalizePhoneNumber(name)
}

function _extractRecordingTimestamp(name) {
    const match = String(name || '').match(/_(\d{14})(?:\.[^.]+)?$/)
    if (!match) return 0
    const raw = match[1]
    const year = Number(raw.slice(0, 4))
    const month = Number(raw.slice(4, 6)) - 1
    const day = Number(raw.slice(6, 8))
    const hour = Number(raw.slice(8, 10))
    const minute = Number(raw.slice(10, 12))
    const second = Number(raw.slice(12, 14))
    const ts = new Date(year, month, day, hour, minute, second).getTime()
    return Number.isFinite(ts) ? ts : 0
}

let _pollTimer = null
let _lastSmsId = -1
let _polling = false

function _ensurePhoneReportFallback(plugin) {
    if (!plugin || typeof plugin.isRunning !== 'function') {
        _startPhoneReportFallback()
        return
    }

    setTimeout(() => {
        try {
            plugin.isRunning((result) => {
                const running = !!(result && result.running)
                if (running) {
                    _stopPhoneReportFallback()
                    console.log('[SMS] native keepalive running, skip frontend phone reporter')
                } else {
                    console.warn('[SMS] native keepalive unavailable, enable frontend phone reporter')
                    _startPhoneReportFallback()
                }
            })
        } catch (e) {
            console.warn('[SMS] native keepalive check failed, enable frontend phone reporter', e)
            _startPhoneReportFallback()
        }
    }, 1200)
}

function _startPhoneReportFallback() {
    if (_phoneReportTimer) return
    _reportPhoneStatusFallback()
    _phoneReportTimer = setInterval(_reportPhoneStatusFallback, PHONE_REPORT_INTERVAL_MS)
}

function _stopPhoneReportFallback() {
    if (_phoneReportTimer) {
        clearInterval(_phoneReportTimer)
        _phoneReportTimer = null
    }
}

function _reportPhoneStatusFallback() {
    const simNames = _getActiveSimNames()
    if (!simNames.length) return
    simNames.forEach(name => {
        uploadPhoneStatus(name)
    })
}

function _getActiveSimNames() {
    try {
        const SubscriptionManager = plus.android.importClass('android.telephony.SubscriptionManager')
        const manager = SubscriptionManager.from(plus.android.runtimeMainActivity())
        if (!manager) return []
        plus.android.importClass(manager)

        const infoList = manager.getActiveSubscriptionInfoList()
        if (!infoList) return []
        plus.android.importClass(infoList)

        const list = []
        const size = infoList.size()
        for (let i = 0; i < size; i++) {
            const info = infoList.get(i)
            if (!info) continue
            plus.android.importClass(info)
            const slotIndex = info.getSimSlotIndex()
            const subId = info.getSubscriptionId()
            const number = _normalizePhoneNumber(info.getNumber())
            const displayName = String(info.getDisplayName() || '').trim()
            const value = (displayName || _formatPhoneLabel(number || _getPhoneNumberFromSubId(subId, slotIndex))).trim()
            if (value && !list.includes(value)) list.push(value)
        }
        return list
    } catch (e) {
        console.error('[SMS] get active sim names failed', e)
        return []
    }
}

function _startPolling() {
    _lastSmsId = _queryMaxSmsId()
    console.log('[SMS] poll start id:', _lastSmsId)
    if (_pollTimer) clearInterval(_pollTimer)
    _pollTimer = setInterval(_pollNewSms, 5000)
}

function _stopPolling() {
    if (_pollTimer) { clearInterval(_pollTimer); _pollTimer = null }
    _polling = false
}

function _queryMaxSmsId() {
    let cursor = null
    try {
        const Uri = plus.android.importClass('android.net.Uri')
        const resolver = plus.android.runtimeMainActivity().getContentResolver()
        plus.android.importClass(resolver)
        cursor = resolver.query(Uri.parse('content://sms'), null, null, null, '_id DESC')
        if (!cursor) return 0
        plus.android.importClass(cursor)
        return cursor.moveToFirst() ? cursor.getLong(cursor.getColumnIndex('_id')) : 0
    } catch (e) {
        console.error('[SMS] query init id failed', e)
        return 0
    } finally {
        try { if (cursor) cursor.close() } catch (_) {}
    }
}

function _pollNewSms() {
    if (_polling) return
    _polling = true
    let cursor = null
    try {
        const Uri = plus.android.importClass('android.net.Uri')
        const resolver = plus.android.runtimeMainActivity().getContentResolver()
        plus.android.importClass(resolver)
        cursor = resolver.query(
            Uri.parse('content://sms'),
            null,
            '_id > ' + _lastSmsId,
            null,
            '_id ASC'
        )
        if (!cursor) return
        plus.android.importClass(cursor)

        while (cursor.moveToNext()) {
            const id = cursor.getLong(cursor.getColumnIndex('_id'))
            const type = cursor.getInt(cursor.getColumnIndex('type'))
            if (id > _lastSmsId) _lastSmsId = id
            if (type !== 1) continue

            const sender = cursor.getString(cursor.getColumnIndex('address')) || ''
            const body = cursor.getString(cursor.getColumnIndex('body')) || ''
            const date = cursor.getLong(cursor.getColumnIndex('date'))
            const subId = cursor.getInt(cursor.getColumnIndex('subscription_id'))
            const slot = _getSimSlotFromSubId(subId)
            const phoneNumber = _getPhoneNumberFromSubId(subId, slot)
            const name = _getSimNameFromSubId(subId, slot, phoneNumber)

            console.log('[SMS] poll new sms from:', sender)
            _handleSms({ sender, body, sim_slot: slot, sim_name: name, phone_number: phoneNumber, timestamp: date })
        }
    } catch (e) {
        console.error('[SMS] poll failed', e)
    } finally {
        try { if (cursor) cursor.close() } catch (_) {}
        _polling = false
    }
}

let _receiver = null

function _startWithPlusAndroid() {
    try {
        const IntentFilter = plus.android.importClass('android.content.IntentFilter')
        const SmsMessage = plus.android.importClass('android.telephony.SmsMessage')
        const filter = new IntentFilter()
        filter.addAction('android.provider.Telephony.SMS_RECEIVED')
        filter.setPriority(999)

        _receiver = plus.android.implements('android.content.BroadcastReceiver', {
            onReceive(context, intent) {
                plus.android.importClass(intent)
                if (intent.getAction() !== 'android.provider.Telephony.SMS_RECEIVED') return
                try {
                    const slotIndex = intent.getIntExtra('android.telephony.extra.SLOT_INDEX', -1)
                    const subId = intent.getIntExtra('subscription', -1)
                    const phoneNumber = _getPhoneNumber(context, subId, slotIndex)
                    const simName = _getSimName(context, subId, slotIndex, phoneNumber)
                    const bundle = intent.getExtras()
                    plus.android.importClass(bundle)
                    const pdus = bundle.get('pdus')
                    const format = bundle.getString('format') || 'pdu'
                    const len = plus.android.getAttribute(pdus, 'length')
                    let sender = ''
                    let body = ''
                    for (let i = 0; i < len; i++) {
                        const pdu = plus.android.invoke(pdus, 'get', i)
                        const smsMsg = SmsMessage.createFromPdu(pdu, format)
                        plus.android.importClass(smsMsg)
                        sender += smsMsg.getOriginatingAddress() || ''
                        body += smsMsg.getMessageBody() || ''
                    }
                    console.log('[SMS] broadcast sms from:', sender)
                    _handleSms({ sender, body, sim_slot: slotIndex, sim_name: simName, phone_number: phoneNumber, timestamp: Date.now() })
                } catch (e) {
                    console.error('[SMS] broadcast parse failed', e)
                }
            }
        })
        plus.android.runtimeMainActivity().registerReceiver(_receiver, filter)
        console.log('[SMS] broadcast receiver registered')
    } catch (e) {
        console.error('[SMS] broadcast register failed', e)
    }
}

const _recentKeys = new Set()
function _isDuplicate(record) {
    const key = `${record.sender}:${String(record.body).slice(0, 20)}:${Math.floor((record.timestamp || 0) / 10000)}`
    if (_recentKeys.has(key)) return true
    _recentKeys.add(key)
    if (_recentKeys.size > 100) _recentKeys.clear()
    return false
}

function _handleSms(record) {
    if (!_shouldUploadSms(record)) return
    if (_isDuplicate(record)) return
    eventBus.emit('sms', record)
    uploadSms(record)
}

function _getDeviceId() {
    try {
        const Settings = plus.android.importClass('android.provider.Settings')
        const activity = plus.android.runtimeMainActivity()
        return Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID) || 'unknown'
    } catch {
        return 'unknown'
    }
}

function _getSimSlotFromSubId(subId) {
    try {
        const SubscriptionManager = plus.android.importClass('android.telephony.SubscriptionManager')
        const manager = SubscriptionManager.from(plus.android.runtimeMainActivity())
        plus.android.importClass(manager)
        const info = manager.getActiveSubscriptionInfo(subId)
        if (info) {
            plus.android.importClass(info)
            return info.getSimSlotIndex()
        }
    } catch {}
    return 0
}

function _formatPhoneLabel(phoneNumber) {
    const normalized = _normalizePhoneNumber(phoneNumber)
    return normalized || ''
}

function _resolveSimName(info, slotIndex, phoneNumber = '') {
    try {
        if (info) {
            plus.android.importClass(info)
            const displayName = (info.getDisplayName() + '').trim()
            if (displayName) return displayName
        }
    } catch {}
    return _formatPhoneLabel(phoneNumber)
}

function _getSimNameFromSubId(subId, slotIndex, phoneNumber = '') {
    try {
        const SubscriptionManager = plus.android.importClass('android.telephony.SubscriptionManager')
        const manager = SubscriptionManager.from(plus.android.runtimeMainActivity())
        plus.android.importClass(manager)
        const info = manager.getActiveSubscriptionInfo(subId)
        return _resolveSimName(info, slotIndex, phoneNumber)
    } catch {}
    return _resolveSimName(null, slotIndex, phoneNumber)
}

function _getSimName(context, subId, slotIndex, phoneNumber = '') {
    try {
        const manager = context.getSystemService('telephony_subscription_service')
        plus.android.importClass(manager)
        const info = subId !== -1
            ? manager.getActiveSubscriptionInfo(subId)
            : (manager.getActiveSubscriptionInfoList()?.toArray() || []).find(i => {
                plus.android.importClass(i)
                return i.getSimSlotIndex() === slotIndex
            })
        return _resolveSimName(info, slotIndex, phoneNumber)
    } catch {}
    return _resolveSimName(null, slotIndex, phoneNumber)
}

function _getPhoneNumberFromSubId(subId, slotIndex) {
    return _getPhoneNumber(plus.android.runtimeMainActivity(), subId, slotIndex)
}

function _getPhoneNumber(context, subId, slotIndex) {
    try {
        const SubscriptionManager = plus.android.importClass('android.telephony.SubscriptionManager')
        plus.android.importClass('android.telephony.TelephonyManager')
        const Build = plus.android.importClass('android.os.Build')
        const manager = SubscriptionManager.from(context)
        if (!manager) return ''
        plus.android.importClass(manager)

        if (Build.VERSION.SDK_INT >= 33 && subId !== -1) {
            const number = _normalizePhoneNumber(manager.getPhoneNumber(subId))
            if (number) return number
        }

        let info = null
        if (subId !== -1) {
            info = manager.getActiveSubscriptionInfo(subId)
        } else {
            const infoList = manager.getActiveSubscriptionInfoList()
            if (infoList) {
                plus.android.importClass(infoList)
                const size = infoList.size()
                for (let i = 0; i < size; i++) {
                    const item = infoList.get(i)
                    if (!item) continue
                    plus.android.importClass(item)
                    if (item.getSimSlotIndex() === slotIndex) {
                        info = item
                        break
                    }
                }
            }
        }
        if (info) {
            plus.android.importClass(info)
            const number = _normalizePhoneNumber(info.getNumber())
            if (number) return number
        }

        const telephony = context.getSystemService('phone')
        if (!telephony) return ''
        plus.android.importClass(telephony)
        let telephonyForSim = telephony
        if (subId !== -1 && telephony.createForSubscriptionId) {
            const scopedTelephony = telephony.createForSubscriptionId(subId)
            if (scopedTelephony) {
                telephonyForSim = scopedTelephony
                plus.android.importClass(telephonyForSim)
            }
        }
        const phoneNumber = _normalizePhoneNumber(telephonyForSim.getLine1Number())
        if (phoneNumber) return phoneNumber
    } catch (e) {
        console.error('[SMS] get phone number failed', e)
    }
    return ''
}

function _normalizePhoneNumber(phoneNumber) {
    return String(phoneNumber || '').replace(/\D/g, '')
}
