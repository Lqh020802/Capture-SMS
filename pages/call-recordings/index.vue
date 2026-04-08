<template>
<view class="page">
    <view class="hero">
        <view>
            <text class="hero-title">通话录音</text>
            <text class="hero-subtitle">接通后会在挂断后扫描小米系统录音目录，并把命中的录音文件展示在这里。</text>
        </view>
        <view class="hero-count">
            <text class="hero-count-num">{{ records.length }}</text>
            <text class="hero-count-label">条录音</text>
        </view>
    </view>

    <view class="toolbar">
        <text class="toolbar-tip">请确保小米系统的自动录音已开启，目录为 MIUI/sound_recorder/call_rec。</text>
        <view class="toolbar-action" @click="clearAll">
            <text class="toolbar-action-text">清空</text>
        </view>
    </view>

    <scroll-view class="list" scroll-y>
        <view v-if="records.length === 0" class="empty-state">
            <text class="empty-text">还没有匹配到通话录音</text>
        </view>

        <view v-for="(item, idx) in records" :key="idx" class="card">
            <view class="card-top">
                <view class="pill">
                    <text class="pill-text">{{ item.sim_name || simLabel(item.sim_slot) }}</text>
                </view>
                <text class="time">{{ formatTime(item.timestamp) }}</text>
            </view>
            <text class="number">{{ item.sender || 'unknown' }}</text>
            <text class="file">{{ item.file_name || item.file_path }}</text>
            <text class="meta">时长 {{ formatDuration(item.duration) }} · {{ formatSize(item.file_size) }}</text>
        </view>
    </scroll-view>
</view>
</template>

<script>
import { eventBus } from '@/utils/sms-monitor.js'
import { clearCallRecordings, loadCallRecordings } from '@/utils/call-recording-store.js'

export default {
    data() {
        return {
            records: []
        }
    },

    onLoad() {
        this.refresh()
        this._listener = () => this.refresh()
        eventBus.on('call-recording', this._listener)
    },

    onShow() {
        this.refresh()
    },

    onUnload() {
        if (this._listener) eventBus.off('call-recording', this._listener)
    },

    methods: {
        refresh() {
            this.records = loadCallRecordings()
        },

        clearAll() {
            uni.showModal({
                title: '确认清空',
                content: '将删除所有通话录音记录',
                success: (res) => {
                    if (!res.confirm) return
                    clearCallRecordings()
                    this.records = []
                    uni.showToast({ title: '已清空', icon: 'success' })
                }
            })
        },

        simLabel(slot) {
            if (slot === 0) return 'SIM1'
            if (slot === 1) return 'SIM2'
            return '未知卡槽'
        },

        formatTime(ts) {
            const d = new Date(ts)
            const pad = n => String(n).padStart(2, '0')
            return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
        },

        formatDuration(sec) {
            const value = Number(sec || 0)
            const mm = String(Math.floor(value / 60)).padStart(2, '0')
            const ss = String(value % 60).padStart(2, '0')
            return `${mm}:${ss}`
        },

        formatSize(size) {
            const value = Number(size || 0)
            if (value >= 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`
            if (value >= 1024) return `${(value / 1024).toFixed(1)} KB`
            return `${value} B`
        }
    }
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f6f7f9; padding: 24rpx; box-sizing: border-box; }
.hero { display:flex; align-items:flex-start; justify-content:space-between; gap:20rpx; padding:32rpx; border-radius:32rpx; background:linear-gradient(135deg,#10261b,#1f5b40); color:#fff; }
.hero-title { display:block; font-size:38rpx; font-weight:700; }
.hero-subtitle { display:block; margin-top:14rpx; font-size:24rpx; color:rgba(255,255,255,.76); line-height:1.6; }
.hero-count { min-width:136rpx; padding:18rpx 20rpx; border-radius:24rpx; background:rgba(255,255,255,.08); text-align:center; }
.hero-count-num { display:block; font-size:42rpx; font-weight:700; }
.hero-count-label { display:block; margin-top:8rpx; font-size:22rpx; color:rgba(255,255,255,.74); }
.toolbar { display:flex; align-items:center; gap:16rpx; margin:24rpx 0; }
.toolbar-tip { flex:1; padding:20rpx 22rpx; border-radius:20rpx; background:#fff; border:1rpx solid #eceef2; color:#6b7280; font-size:24rpx; line-height:1.6; }
.toolbar-action { min-width:112rpx; height:84rpx; border-radius:20rpx; background:#111827; display:flex; align-items:center; justify-content:center; }
.toolbar-action-text { color:#fff; font-size:26rpx; font-weight:600; }
.list { height: calc(100vh - 330rpx); }
.empty-state { padding:180rpx 0; text-align:center; }
.empty-text { color:#9ca3af; font-size:26rpx; }
.card { margin-bottom:16rpx; padding:28rpx; border-radius:28rpx; background:#fff; border:1rpx solid #eceef2; }
.card-top { display:flex; align-items:center; justify-content:space-between; gap:14rpx; }
.pill { padding:8rpx 18rpx; border-radius:999rpx; background:#dcfce7; }
.pill-text { font-size:22rpx; font-weight:600; color:#166534; }
.time { font-size:22rpx; color:#9ca3af; }
.number { display:block; margin-top:18rpx; font-size:34rpx; font-weight:700; color:#111827; }
.file { display:block; margin-top:12rpx; font-size:24rpx; color:#2563eb; word-break:break-all; }
.meta { display:block; margin-top:18rpx; padding-top:18rpx; border-top:1rpx solid #f1f5f9; font-size:24rpx; color:#4b5563; }
</style>
