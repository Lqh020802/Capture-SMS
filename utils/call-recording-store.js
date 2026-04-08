const STORAGE_KEY = 'call_recording_records'
const MAX_RECORDS = 100

export function loadCallRecordings() {
    try {
        const raw = uni.getStorageSync(STORAGE_KEY)
        const list = raw ? JSON.parse(raw) : []
        return Array.isArray(list) ? list : []
    } catch {
        return []
    }
}

export function saveCallRecording(record = {}) {
    const next = normalizeRecord(record)
    const list = loadCallRecordings()
    const exists = list.some(item => buildKey(item) === buildKey(next))
    if (exists) return list

    list.unshift(next)
    if (list.length > MAX_RECORDS) list.splice(MAX_RECORDS)
    uni.setStorageSync(STORAGE_KEY, JSON.stringify(list))
    return list
}

export function clearCallRecordings() {
    uni.setStorageSync(STORAGE_KEY, '[]')
}

function normalizeRecord(record = {}) {
    return {
        event_type: 'call_recording',
        sender: String(record.sender || 'unknown').trim() || 'unknown',
        sim_slot: Number.isFinite(Number(record.sim_slot)) ? Number(record.sim_slot) : -1,
        sim_name: String(record.sim_name || '').trim(),
        phone_number: String(record.phone_number || '').trim(),
        timestamp: Number(record.timestamp || Date.now()),
        answered_timestamp: Number(record.answered_timestamp || record.timestamp || Date.now()),
        end_timestamp: Number(record.end_timestamp || record.timestamp || Date.now()),
        duration: Number(record.duration || 0),
        file_path: String(record.file_path || '').trim(),
        file_name: String(record.file_name || '').trim(),
        file_size: Number(record.file_size || 0),
        modified_at: Number(record.modified_at || record.timestamp || Date.now())
    }
}

function buildKey(record = {}) {
    return [
        String(record.file_path || ''),
        String(record.sender || ''),
        String(record.end_timestamp || ''),
        String(record.modified_at || '')
    ].join('|')
}
