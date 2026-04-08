<template>
<view class="page">
    <view class="header">
        <view :style="{ height: statusBarHeight + 'px' }"></view>
        <view class="header-top">
            <view>
                <text class="app-title">短信监控</text>
                <text class="header-subtitle">短信监听保留，未接来电和通话录音都已拆成独立页面。</text>
            </view>
            <view class="header-actions">
                <view class="btn-nav" @click="goMissedCalls">
                    <text class="btn-nav-text">未接来电</text>
                </view>
                <view class="btn-nav btn-green" @click="goCallRecordings">
                    <text class="btn-nav-text btn-green-text">通话录音</text>
                </view>
                <view class="btn-setting" @click="goSetting">
                    <text class="btn-setting-text">设置</text>
                </view>
            </view>
        </view>

        <view class="stats-row">
            <view class="stat-item">
                <text class="stat-num">{{ totalCount }}</text>
                <text class="stat-label">短信累计</text>
            </view>
            <view class="stat-divider"></view>
            <view class="stat-item">
                <text class="stat-num">{{ missedCount }}</text>
                <text class="stat-label">未接来电</text>
            </view>
            <view class="stat-divider"></view>
            <view class="stat-item">
                <text class="stat-num">{{ recordingCount }}</text>
                <text class="stat-label">通话录音</text>
            </view>
        </view>

        <view class="status-row">
            <view class="status-pill" :class="monitoring ? 'pill-on' : 'pill-off'">
                <view class="pill-dot" :class="monitoring ? 'dot-on' : 'dot-off'"></view>
                <text class="pill-text">{{ monitoring ? '监听运行中' : '监听已停止' }}</text>
            </view>
            <view class="ctrl-row">
                <view class="btn-main" :class="monitoring ? 'btn-stop' : 'btn-start'" @click="toggleMonitor">
                    <text class="btn-main-text">{{ monitoring ? '停止监听' : '启动监听' }}</text>
                </view>
                <view class="btn-diag" @click="runDiag">
                    <text class="btn-diag-text">诊断</text>
                </view>
                <view class="btn-diag" @click="testSms">
                    <text class="btn-diag-text">模拟</text>
                </view>
            </view>
        </view>
    </view>

    <view class="section">
        <view class="section-header">
            <text class="section-title">最近短信</text>
            <text class="section-action" @click="clearLogs">清空</text>
        </view>

        <scroll-view class="log-list" scroll-y>
            <view v-if="logs.length === 0" class="empty-state">
                <text class="empty-text">暂无短信记录</text>
            </view>

            <view v-for="(item, idx) in logs" :key="idx" class="log-card">
                <view class="log-top">
                    <view class="sim-tag" :class="item.sim_slot === 0 ? 'tag-blue' : 'tag-orange'">
                        <text class="sim-tag-text">{{ item.sim_name || simLabel(item.sim_slot) }}</text>
                    </view>
                    <view class="log-meta">
                        <text class="log-sender">{{ item.sender }}</text>
                        <text v-if="item.phone_number" class="log-phone">{{ item.phone_number }}</text>
                    </view>
                    <text class="log-time">{{ formatTime(item.timestamp) }}</text>
                </view>
                <text class="log-body">{{ item.body }}</text>
            </view>
        </scroll-view>
    </view>

    <view class="safe-bottom"></view>
</view>
</template>

<script>
import { startSmsMonitor, stopSmsMonitor, getMonitorStatus, eventBus, simulateSms } from '@/utils/sms-monitor.js'
import { loadCallRecordings } from '@/utils/call-recording-store.js'
import { loadMissedCalls } from '@/utils/missed-call-store.js'

export default {
    data() {
        return {
            monitoring: false,
            logs: [],
            totalCount: 0,
            missedCount: 0,
            recordingCount: 0,
            statusBarHeight: 0
        }
    },

    onReady() {
        uni.setNavigationBarColor({
            frontColor: '#000000',
            backgroundColor: '#ffffff'
        })
    },

    onLoad() {
        this.statusBarHeight = uni.getSystemInfoSync().statusBarHeight
        this.monitoring = getMonitorStatus()
        this._loadStats()

        this._smsListener = (record) => {
            this.logs.unshift({ ...record, uploaded: true })
            if (this.logs.length > 100) this.logs.pop()
            this.totalCount++
            uni.setStorageSync('stat_total', this.totalCount)
        }
        this._missedListener = () => {
            this.missedCount = loadMissedCalls().length
        }
        this._recordingListener = () => {
            this.recordingCount = loadCallRecordings().length
        }

        eventBus.on('sms', this._smsListener)
        eventBus.on('missed-call', this._missedListener)
        eventBus.on('call-recording', this._recordingListener)
    },

    onShow() {
        this.missedCount = loadMissedCalls().length
        this.recordingCount = loadCallRecordings().length
    },

    onUnload() {
        if (this._smsListener) eventBus.off('sms', this._smsListener)
        if (this._missedListener) eventBus.off('missed-call', this._missedListener)
        if (this._recordingListener) eventBus.off('call-recording', this._recordingListener)
    },

    methods: {
        goSetting() {
            uni.navigateTo({ url: '/pages/setting/setting' })
        },

        goMissedCalls() {
            uni.navigateTo({ url: '/pages/missed-calls/index' })
        },

        goCallRecordings() {
            uni.navigateTo({ url: '/pages/call-recordings/index' })
        },

        toggleMonitor() {
            if (this.monitoring) {
                stopSmsMonitor()
                this.monitoring = false
                uni.showToast({ title: '监听已停止', icon: 'none' })
            } else {
                startSmsMonitor()
                this.monitoring = true
                uni.showToast({ title: '监听已启动', icon: 'success' })
                // 启动后检查"所有文件访问"权限，未授权则引导
                this._checkStoragePermission()
            }
        },

        _checkStoragePermission() {
            // #ifdef APP-PLUS
            try {
                const plugin = uni.requireNativePlugin('Capture-Keepalive')
                if (!plugin || typeof plugin.checkStoragePermission !== 'function') return
                plugin.checkStoragePermission((res) => {
                    if (res && res.granted) return
                    uni.showModal({
                        title: '需要文件访问权限',
                        content: '通话录音功能需要"所有文件访问"权限才能读取系统录音目录，是否前往设置？',
                        confirmText: '去授权',
                        cancelText: '稍后',
                        success: (r) => {
                            if (!r.confirm) return
                            plugin.requestStoragePermission(() => {})
                        }
                    })
                })
            } catch (e) {
                console.error('[INDEX] check storage permission failed', e)
            }
            // #endif
        },

        runDiag() {
            let msg = ''
            // #ifdef APP-PLUS
            try {
                const ctx = plus.android.runtimeMainActivity()
                plus.android.importClass(ctx)
                ;['RECEIVE_SMS', 'READ_SMS', 'READ_PHONE_STATE', 'READ_CALL_LOG', 'READ_EXTERNAL_STORAGE', 'READ_MEDIA_AUDIO'].forEach(name => {
                    const full = 'android.permission.' + name
                    const ok = ctx.checkSelfPermission(full) === 0
                    msg += (ok ? '√' : '×') + ' ' + name + '\n'
                })
                try {
                    const Environment = plus.android.importClass('android.os.Environment')
                    const allFiles = Environment.isExternalStorageManager()
                    msg += (allFiles ? '√' : '×') + ' MANAGE_EXTERNAL_STORAGE\n'
                } catch (e) {
                    msg += '× MANAGE_EXTERNAL_STORAGE\n'
                }
            } catch (e) {
                msg += '权限检查失败\n'
            }
            // #endif
            msg += `\n未接来电 ${this.missedCount} 条`
            msg += `\n通话录音 ${this.recordingCount} 条`
            uni.showModal({
                title: '诊断结果',
                content: msg,
                confirmText: '去授权',
                cancelText: '关闭',
                success: (res) => {
                    if (!res.confirm) return
                    // #ifdef APP-PLUS
                    try {
                        const Intent = plus.android.importClass('android.content.Intent')
                        const Settings = plus.android.importClass('android.provider.Settings')
                        const Uri = plus.android.importClass('android.net.Uri')
                        const activity = plus.android.runtimeMainActivity()
                        let intent = null
                        try {
                            intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.setData(Uri.parse('package:' + (plus.runtime.packageName || plus.runtime.appid)))
                        } catch (e) {
                            intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        }
                        activity.startActivity(intent)
                    } catch (e) {
                        console.error('[DIAG] open all files access settings failed', e)
                    }
                    // #endif
                }
            })
        },

        testSms() {
            simulateSms()
            uni.showToast({ title: '已模拟短信', icon: 'none' })
        },

        clearLogs() {
            this.logs = []
            uni.showToast({ title: '已清空', icon: 'success' })
        },

        simLabel(slot) {
            if (slot === 0) return 'SIM1'
            if (slot === 1) return 'SIM2'
            return 'SIM'
        },

        formatTime(ts) {
            const d = new Date(ts)
            const pad = n => String(n).padStart(2, '0')
            return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
        },

        _loadStats() {
            this.totalCount = uni.getStorageSync('stat_total') || 0
            this.missedCount = loadMissedCalls().length
            this.recordingCount = loadCallRecordings().length
        }
    }
}
</script>

<style scoped>
.page { min-height: 100vh; background:#f7f8fa; }
.header { background:#fff; padding:48rpx 40rpx 32rpx; border-bottom:1rpx solid #f0f0f0; }
.header-top { display:flex; align-items:flex-start; justify-content:space-between; gap:20rpx; margin-bottom:40rpx; }
.app-title { font-size:40rpx; font-weight:700; color:#111; display:block; }
.header-subtitle { display:block; margin-top:10rpx; font-size:22rpx; color:#9aa0a6; line-height:1.6; }
.header-actions { display:flex; align-items:center; gap:12rpx; flex-wrap:wrap; justify-content:flex-end; }
.btn-nav,.btn-setting { min-width:108rpx; height:60rpx; padding:0 24rpx; border-radius:999rpx; display:flex; align-items:center; justify-content:center; }
.btn-nav { background:#eef2ff; }
.btn-green { background:#dcfce7; }
.btn-setting { background:#111; }
.btn-nav-text { font-size:24rpx; font-weight:600; color:#3730a3; }
.btn-green-text { color:#166534; }
.btn-setting-text { font-size:24rpx; font-weight:600; color:#fff; }
.stats-row { display:flex; align-items:center; justify-content:space-around; background:#f7f8fa; border-radius:20rpx; padding:28rpx 0; margin-bottom:28rpx; }
.stat-item { display:flex; flex-direction:column; align-items:center; flex:1; }
.stat-num { font-size:52rpx; font-weight:700; color:#111; line-height:1.1; }
.stat-label { font-size:22rpx; color:#9e9e9e; margin-top:8rpx; }
.stat-divider { width:1rpx; height:60rpx; background:#e0e0e0; }
.status-row { display:flex; flex-direction:column; gap:18rpx; }
.status-pill { display:flex; align-items:center; padding:12rpx 24rpx; border-radius:40rpx; gap:10rpx; align-self:flex-start; }
.pill-on { background:#e8f5e9; }
.pill-off { background:#f5f5f5; }
.pill-dot { width:14rpx; height:14rpx; border-radius:50%; }
.dot-on { background:#4caf50; }
.dot-off { background:#bdbdbd; }
.pill-text { font-size:24rpx; font-weight:600; }
.ctrl-row { display:flex; gap:16rpx; }
.btn-main { flex:1; height:96rpx; border-radius:16rpx; display:flex; align-items:center; justify-content:center; }
.btn-diag { width:120rpx; height:96rpx; border-radius:16rpx; background:#f5f5f5; display:flex; align-items:center; justify-content:center; }
.btn-start { background:#111; }
.btn-stop { background:#fff; border:2rpx solid #e0e0e0; }
.btn-main-text { font-size:30rpx; font-weight:600; color:#fff; }
.btn-stop .btn-main-text { color:#f44336; }
.btn-diag-text { font-size:26rpx; color:#888; }
.section { padding:32rpx 40rpx 0; }
.section-header { display:flex; align-items:center; justify-content:space-between; margin-bottom:20rpx; }
.section-title { font-size:28rpx; font-weight:700; color:#111; }
.section-action { font-size:26rpx; color:#bdbdbd; }
.log-list { height:820rpx; }
.empty-state { padding:120rpx 0; text-align:center; }
.empty-text { font-size:28rpx; color:#bdbdbd; }
.log-card { background:#fff; border-radius:20rpx; padding:28rpx 28rpx 20rpx; margin-bottom:16rpx; }
.log-top { display:flex; align-items:center; margin-bottom:16rpx; gap:14rpx; }
.sim-tag { padding:6rpx 16rpx; border-radius:8rpx; }
.tag-blue { background:#e3f2fd; }
.tag-orange { background:#fff3e0; }
.sim-tag-text { font-size:20rpx; font-weight:600; }
.tag-blue .sim-tag-text { color:#1565c0; }
.tag-orange .sim-tag-text { color:#e65100; }
.log-meta { flex:1; min-width:0; display:flex; flex-direction:column; gap:6rpx; }
.log-sender { font-size:28rpx; font-weight:600; color:#212121; }
.log-phone { font-size:22rpx; color:#8a94a6; line-height:1.4; word-break:break-all; }
.log-time { font-size:22rpx; color:#bdbdbd; }
.log-body { font-size:28rpx; color:#424242; line-height:1.6; }
.safe-bottom { background:#fff; padding-bottom:env(safe-area-inset-bottom); height:env(safe-area-inset-bottom); }
</style>
