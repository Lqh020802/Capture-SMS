const STORAGE_KEY = 'missed_call_records'
const MAX_RECORDS = 100

export function loadMissedCalls() {
    try {
        const raw = uni.getStorageSync(STORAGE_KEY)
        const list = raw ? JSON.parse(raw) : []
        return Array.isArray(list) ? list : []
    } catch {
        return []
    }
}

export function saveMissedCall(record = {}) {
    const next = normalizeRecord(record)
    const list = loadMissedCalls()
    const exists = list.some(item => buildKey(item) === buildKey(next))
    if (exists) return list

    list.unshift(next)
    if (list.length > MAX_RECORDS) list.splice(MAX_RECORDS)
    uni.setStorageSync(STORAGE_KEY, JSON.stringify(list))
    return list
}

export function clearMissedCalls() {
    uni.setStorageSync(STORAGE_KEY, '[]')
}

function normalizeRecord(record = {}) {
    return {
        event_type: 'missed_call',
        sender: String(record.sender || record.number || 'unknown').trim() || 'unknown',
        body: String(record.body || 'missed call').trim() || 'missed call',
        sim_slot: Number.isFinite(Number(record.sim_slot)) ? Number(record.sim_slot) : -1,
        sim_name: String(record.sim_name || '').trim(),
        phone_number: String(record.phone_number || '').trim(),
        timestamp: Number(record.timestamp || Date.now()),
        duration: Number(record.duration || 0)
    }
}

function buildKey(record = {}) {
    return [
        String(record.sender || ''),
        String(record.phone_number || ''),
        String(record.timestamp || ''),
        String(record.duration || 0)
    ].join('|')
}
