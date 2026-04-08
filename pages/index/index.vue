<template>
<view class="page">
    <view class="header">
        <view :style="{ height: statusBarHeight + 'px' }"></view>
        <view class="header-top">
            <view>
                <text class="app-title">短信监控</text>
                <text class="header-subtitle">短信监听继续保留，未接来电会单独进入新的记录页</text>
            </view>
            <view class="header-actions">
                <view class="btn-nav" @click="goMissedCalls">
                    <text class="btn-nav-text">未接来电</text>
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
                <text class="stat-num">{{ todayCount }}</text>
                <text class="stat-label">今日短信</text>
            </view>
            <view class="stat-divider"></view>
            <view class="stat-item">
                <text class="stat-num">{{ missedCount }}</text>
                <text class="stat-label">未接来电</text>
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
                <view class="log-footer">
                    <view class="upload-dot" :class="item.uploaded ? 'dot-ok' : 'dot-fail'"></view>
                    <text class="upload-text" :class="item.uploaded ? 'text-ok' : 'text-fail'">
                        {{ item.uploaded ? '已处理' : '待上报' }}
                    </text>
                </view>
            </view>
        </scroll-view>
    </view>

    <view class="safe-bottom"></view>
</view>
</template>

<script>
import { startSmsMonitor, stopSmsMonitor, getMonitorStatus, eventBus, simulateSms } from '@/utils/sms-monitor.js'
import { loadMissedCalls } from '@/utils/missed-call-store.js'

export default {
    data() {
        return {
            monitoring: false,
            logs: [],
            totalCount: 0,
            todayCount: 0,
            failCount: 0,
            missedCount: 0,
            statusBarHeight: 0
        }
    },

    onReady() {
        uni.setNavigationBarColor({
            frontColor: '#000000',
            backgroundColor: '#ffffff'
        })
        // #ifdef APP-PLUS
        try {
            const window = plus.android.runtimeMainActivity().getWindow()
            plus.android.importClass(window)
            window.setNavigationBarColor(0xFFFFFFFF)
        } catch (e) {}
        // #endif
    },

    onLoad() {
        this.statusBarHeight = uni.getSystemInfoSync().statusBarHeight
        this.monitoring = getMonitorStatus()
        this._loadStats()

        this._smsListener = (record) => {
            this.logs.unshift({ ...record, uploaded: true })
            if (this.logs.length > 100) this.logs.pop()
            this.totalCount++
            this.todayCount++
            this._saveStats()
        }
        this._missedListener = () => {
            this.missedCount = loadMissedCalls().length
        }

        eventBus.on('sms', this._smsListener)
        eventBus.on('missed-call', this._missedListener)
    },

    onShow() {
        this.missedCount = loadMissedCalls().length
    },

    onUnload() {
        if (this._smsListener) eventBus.off('sms', this._smsListener)
        if (this._missedListener) eventBus.off('missed-call', this._missedListener)
    },

    methods: {
        goSetting() {
            uni.navigateTo({ url: '/pages/setting/setting' })
        },

        goMissedCalls() {
            uni.navigateTo({ url: '/pages/missed-calls/index' })
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
            }
        },

        runDiag() {
            // #ifdef APP-PLUS
            let msg = ''
            try {
                const p = uni.requireNativePlugin('Capture-Keepalive')
                msg += (p ? '√' : '×') + ' 原生插件 ' + (p ? '已加载' : '加载失败') + '\n'
            } catch (e) {
                msg += '× 原生插件加载失败\n'
            }
            try {
                const ctx = plus.android.runtimeMainActivity()
                plus.android.importClass(ctx)
                ;['RECEIVE_SMS', 'READ_SMS', 'READ_PHONE_STATE', 'READ_CALL_LOG'].forEach(name => {
                    const full = 'android.permission.' + name
                    const ok = ctx.checkSelfPermission(full) === 0
                    msg += (ok ? '√' : '×') + ' ' + name + '\n'
                })
            } catch (e) {
                msg += '权限检查失败\n'
            }

            msg += '\n' + (this.monitoring ? '√' : '×') + ' 监听状态 ' + (this.monitoring ? '运行中' : '已停止')
            msg += '\n未接来电记录 ' + this.missedCount + ' 条'

            uni.showModal({
                title: '诊断结果',
                content: msg,
                confirmText: '去权限设置',
                cancelText: '关闭',
                success: (res) => {
                    if (!res.confirm) return
                    // #ifdef APP-PLUS
                    const Intent = plus.android.importClass('android.content.Intent')
                    const Settings = plus.android.importClass('android.provider.Settings')
                    const Uri = plus.android.importClass('android.net.Uri')
                    const intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.setData(Uri.fromParts('package', plus.runtime.packageName || plus.runtime.appid, null))
                    plus.android.runtimeMainActivity().startActivity(intent)
                    // #endif
                }
            })
            // #endif
        },

        testSms() {
            simulateSms()
            uni.showToast({ title: '已模拟短信', icon: 'none' })
        },

        clearLogs() {
            uni.showModal({
                title: '确认清空',
                content: '将清空本地短信日志记录',
                success: (res) => {
                    if (!res.confirm) return
                    this.logs = []
                    uni.showToast({ title: '已清空', icon: 'success' })
                }
            })
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
            this.todayCount = uni.getStorageSync('stat_today') || 0
            this.missedCount = loadMissedCalls().length
            try {
                const pending = uni.getStorageSync('sms_pending')
                this.failCount = pending ? JSON.parse(pending).length : 0
            } catch {
                this.failCount = 0
            }
        },

        _saveStats() {
            uni.setStorageSync('stat_total', this.totalCount)
            uni.setStorageSync('stat_today', this.todayCount)
        }
    }
}
</script>

<style scoped>
.page {
    min-height: 100vh;
    background: #f7f8fa;
}

.header {
    background: #ffffff;
    padding: 48rpx 40rpx 32rpx;
    border-bottom: 1rpx solid #f0f0f0;
}

.header-top {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 20rpx;
    margin-bottom: 40rpx;
}

.app-title {
    font-size: 40rpx;
    font-weight: 700;
    color: #111;
    letter-spacing: 2rpx;
    display: block;
}

.header-subtitle {
    display: block;
    margin-top: 10rpx;
    font-size: 22rpx;
    color: #9aa0a6;
    letter-spacing: 1rpx;
    line-height: 1.6;
}

.header-actions {
    display: flex;
    align-items: center;
    gap: 16rpx;
}

.btn-nav,
.btn-setting {
    min-width: 108rpx;
    height: 60rpx;
    padding: 0 24rpx;
    border-radius: 999rpx;
    display: flex;
    align-items: center;
    justify-content: center;
}

.btn-nav {
    background: #eef2ff;
}

.btn-setting {
    background: #111;
}

.btn-nav-text {
    font-size: 24rpx;
    font-weight: 600;
    color: #3730a3;
}

.btn-setting-text {
    font-size: 24rpx;
    font-weight: 600;
    color: #fff;
}

.stats-row {
    display: flex;
    align-items: center;
    justify-content: space-around;
    background: #f7f8fa;
    border-radius: 20rpx;
    padding: 28rpx 0;
    margin-bottom: 28rpx;
}

.stat-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    flex: 1;
}

.stat-num {
    font-size: 52rpx;
    font-weight: 700;
    color: #111;
    line-height: 1.1;
}

.stat-label {
    font-size: 22rpx;
    color: #9e9e9e;
    margin-top: 8rpx;
}

.stat-divider {
    width: 1rpx;
    height: 60rpx;
    background: #e0e0e0;
}

.status-row {
    display: flex;
    flex-direction: column;
    gap: 18rpx;
}

.status-pill {
    display: flex;
    align-items: center;
    padding: 12rpx 24rpx;
    border-radius: 40rpx;
    gap: 10rpx;
    align-self: flex-start;
}

.pill-on {
    background: #e8f5e9;
}

.pill-off {
    background: #f5f5f5;
}

.pill-dot {
    width: 14rpx;
    height: 14rpx;
    border-radius: 50%;
}

.dot-on {
    background: #4caf50;
}

.dot-off {
    background: #bdbdbd;
}

.pill-text {
    font-size: 24rpx;
    font-weight: 600;
}

.ctrl-row {
    display: flex;
    gap: 16rpx;
}

.btn-main {
    flex: 1;
    height: 96rpx;
    border-radius: 16rpx;
    display: flex;
    align-items: center;
    justify-content: center;
}

.btn-diag {
    width: 120rpx;
    height: 96rpx;
    border-radius: 16rpx;
    background: #f5f5f5;
    display: flex;
    align-items: center;
    justify-content: center;
}

.btn-start {
    background: #111;
}

.btn-stop {
    background: #fff;
    border: 2rpx solid #e0e0e0;
}

.btn-main-text {
    font-size: 30rpx;
    font-weight: 600;
    letter-spacing: 2rpx;
}

.btn-start .btn-main-text {
    color: #fff;
}

.btn-stop .btn-main-text {
    color: #f44336;
}

.btn-diag-text {
    font-size: 26rpx;
    color: #888;
}

.section {
    padding: 32rpx 40rpx 0;
}

.section-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 20rpx;
}

.section-title {
    font-size: 28rpx;
    font-weight: 700;
    color: #111;
}

.section-action {
    font-size: 26rpx;
    color: #bdbdbd;
}

.log-list {
    height: 820rpx;
}

.empty-state {
    padding: 120rpx 0;
    text-align: center;
}

.empty-text {
    font-size: 28rpx;
    color: #bdbdbd;
}

.log-card {
    background: #fff;
    border-radius: 20rpx;
    padding: 28rpx 28rpx 20rpx;
    margin-bottom: 16rpx;
}

.log-top {
    display: flex;
    align-items: center;
    margin-bottom: 16rpx;
    gap: 14rpx;
}

.sim-tag {
    padding: 6rpx 16rpx;
    border-radius: 8rpx;
}

.tag-blue {
    background: #e3f2fd;
}

.tag-orange {
    background: #fff3e0;
}

.sim-tag-text {
    font-size: 20rpx;
    font-weight: 600;
}

.tag-blue .sim-tag-text {
    color: #1565c0;
}

.tag-orange .sim-tag-text {
    color: #e65100;
}

.log-meta {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 6rpx;
}

.log-sender {
    font-size: 28rpx;
    font-weight: 600;
    color: #212121;
}

.log-phone {
    font-size: 22rpx;
    color: #8a94a6;
    line-height: 1.4;
    word-break: break-all;
}

.log-time {
    font-size: 22rpx;
    color: #bdbdbd;
}

.log-body {
    font-size: 28rpx;
    color: #424242;
    line-height: 1.6;
    margin-bottom: 16rpx;
}

.log-footer {
    display: flex;
    align-items: center;
    gap: 8rpx;
}

.upload-dot {
    width: 10rpx;
    height: 10rpx;
    border-radius: 50%;
}

.dot-ok {
    background: #66bb6a;
}

.dot-fail {
    background: #ef5350;
}

.upload-text {
    font-size: 22rpx;
}

.text-ok {
    color: #66bb6a;
}

.text-fail {
    color: #ef5350;
}

.safe-bottom {
    background: #ffffff;
    padding-bottom: env(safe-area-inset-bottom);
    height: env(safe-area-inset-bottom);
}
</style>
