<template>
<view class="page">

    <!-- 顶部状态区 -->
    <view class="header">
        <!-- 状态栏占位 -->
        <view :style="{ height: statusBarHeight + 'px' }"></view>
        <view class="header-top">
            <view>
                <text class="app-title">短信监控</text>
                <text class="header-subtitle">短信接收、缓存与自动上报</text>
            </view>
            <view class="header-actions">
                <view class="btn-setting" @click="goSetting">
                    <text class="btn-setting-text">设置</text>
                </view>
                <view class="status-pill" :class="monitoring ? 'pill-on' : 'pill-off'">
                    <view class="pill-dot" :class="monitoring ? 'dot-on' : 'dot-off'"></view>
                    <text class="pill-text">{{ monitoring ? '运行中' : '已停止' }}</text>
                </view>
            </view>
        </view>

        <!-- 统计数据 -->
        <view class="stats-row">
            <view class="stat-item">
                <text class="stat-num">{{ totalCount }}</text>
                <text class="stat-label">累计</text>
            </view>
            <view class="stat-divider"></view>
            <view class="stat-item">
                <text class="stat-num">{{ todayCount }}</text>
                <text class="stat-label">今日</text>
            </view>
            <view class="stat-divider"></view>
            <view class="stat-item">
                <text class="stat-num" :class="failCount > 0 ? 'num-warn' : ''">{{ failCount }}</text>
                <text class="stat-label">待重传</text>
            </view>
        </view>

        <!-- 控制按钮 -->
        <view class="ctrl-row">
            <view class="btn-main" :class="monitoring ? 'btn-stop' : 'btn-start'" @click="toggleMonitor">
                <text class="btn-main-text">{{ monitoring ? '停止监控' : '启动监控' }}</text>
            </view>
            <view class="btn-diag" @click="runDiag">
                <text class="btn-diag-text">诊断</text>
            </view>
            <view class="btn-diag" @click="testSms">
                <text class="btn-diag-text">模拟</text>
            </view>
        </view>
    </view>

    <!-- 记录列表 -->
    <view class="section">
        <view class="section-header">
            <text class="section-title">最近记录</text>
            <text class="section-action" @click="clearLogs">清空</text>
        </view>

        <scroll-view class="log-list" scroll-y>
            <view v-if="logs.length === 0" class="empty-state">
                <text class="empty-icon">📭</text>
                <text class="empty-text">暂无短信记录</text>
            </view>

            <view v-for="(item, idx) in logs" :key="idx" class="log-card">
                <view class="log-top">
                    <view class="sim-tag" :class="item.sim_slot === 0 ? 'tag-blue' : 'tag-orange'">
                        <text class="sim-tag-text">{{ item.sim_name }}</text>
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
                        {{ item.uploaded ? '已上报' : '待上报' }}
                    </text>
                </view>
            </view>
        </scroll-view>
    </view>

    <!-- 底部安全区 -->
    <view class="safe-bottom"></view>

</view>
</template>

<script>
import { startSmsMonitor, stopSmsMonitor, getMonitorStatus, eventBus, simulateSms } from '@/utils/sms-monitor.js'

export default {
    data() {
        return {
            monitoring: false,
            logs: [],
            totalCount: 0,
            todayCount: 0,
            failCount: 0,
            statusBarHeight: 0
        }
    },

    onReady() {
        uni.setNavigationBarColor({
            frontColor: '#000000',
            backgroundColor: '#ffffff'
        })
        // 设置 Android 底部系统导航栏为白色
        // #ifdef APP-PLUS
        try {
            const window = plus.android.runtimeMainActivity().getWindow()
            plus.android.importClass(window)
            window.setNavigationBarColor(0xFFFFFFFF)
        } catch (e) { }
        // #endif
    },

    onLoad() {
        this.statusBarHeight = uni.getSystemInfoSync().statusBarHeight
        this.monitoring = getMonitorStatus()
        this._loadStats()

        eventBus.on('sms', (record) => {
            this.logs.unshift({ ...record, uploaded: true })
            if (this.logs.length > 100) this.logs.pop()
            this.totalCount++
            this.todayCount++
            this._saveStats()
        })
    },

    methods: {
        goSetting() {
            uni.navigateTo({
                url: '/pages/setting/setting'
            })
        },

        toggleMonitor() {
            if (this.monitoring) {
                stopSmsMonitor()
                this.monitoring = false
                uni.showToast({ title: '监控已停止', icon: 'none' })
            } else {
                startSmsMonitor()
                this.monitoring = true
                uni.showToast({ title: '监控已启动', icon: 'success' })
            }
        },

        runDiag() {
            // #ifdef APP-PLUS
            let msg = ''
            // 1. 插件
            try {
                const p = uni.requireNativePlugin('Capture-Keepalive')
                msg += (p ? '✅' : '❌') + ' 原生插件：' + (p ? '已加载' : '返回空') + '\n'
            } catch (e) { msg += '❌ 原生插件加载失败\n' }
            // 2. 权限
            try {
                const ctx = plus.android.runtimeMainActivity()
                plus.android.importClass(ctx)
                    ;['RECEIVE_SMS', 'READ_SMS', 'READ_PHONE_STATE'].forEach(name => {
                        const full = 'android.permission.' + name
                        const ok = ctx.checkSelfPermission(full) === 0
                        msg += (ok ? '✅' : '❌') + ' ' + name + '\n'
                    })
            } catch (e) { msg += '⚠️ 权限检查失败\n' }

            // 3. 小米/MIUI 检测
            try {
                const Build = plus.android.importClass('android.os.Build')
                const manufacturer = Build.MANUFACTURER + ''
                const isXiaomi = manufacturer.toLowerCase().includes('xiaomi')
                if (isXiaomi) {
                    msg += '\n⚠️ 检测到小米设备\n'
                    msg += '请手动开启：\n设置→应用→本应用→权限\n→打开「短信」和「通知类短信」'
                }
            } catch (e) { }

            // 4. 监控状态
            msg += '\n' + (this.monitoring ? '✅' : '❌') + ' 监控：' + (this.monitoring ? '运行中' : '已停止')
            uni.showModal({
                title: '诊断结果',
                content: msg,
                confirmText: '去权限设置',
                cancelText: '关闭',
                success: (res) => {
                    if (res.confirm) {
                        // 跳转到 App 权限设置页
                        // #ifdef APP-PLUS
                        const Intent = plus.android.importClass('android.content.Intent')
                        const Settings = plus.android.importClass('android.provider.Settings')
                        const Uri = plus.android.importClass('android.net.Uri')
                        const intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.setData(Uri.fromParts('package', plus.runtime.appid, null))
                        plus.android.runtimeMainActivity().startActivity(intent)
                        // #endif
                    }
                }
            })
            // #endif
        },

        testSms() {
            simulateSms()
            uni.showToast({ title: '已模拟发送', icon: 'none' })
        },

        clearLogs() {
            uni.showModal({
                title: '确认清空',
                content: '将清空本地日志记录',
                success: (res) => {
                    if (res.confirm) {
                        this.logs = []
                        uni.showToast({ title: '已清空', icon: 'success' })
                    }
                }
            })
        },

        formatTime(ts) {
            const d = new Date(ts)
            const pad = n => String(n).padStart(2, '0')
            return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
        },

        _loadStats() {
            this.totalCount = uni.getStorageSync('stat_total') || 0
            this.todayCount = uni.getStorageSync('stat_today') || 0
            try {
                const pending = uni.getStorageSync('sms_pending')
                this.failCount = pending ? JSON.parse(pending).length : 0
            } catch { this.failCount = 0 }
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

/* ── 顶部 Header ── */
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
}

.header-actions {
    display: flex;
    align-items: center;
    gap: 16rpx;
}

.btn-setting {
    min-width: 108rpx;
    height: 60rpx;
    padding: 0 24rpx;
    border-radius: 999rpx;
    background: #111;
    display: flex;
    align-items: center;
    justify-content: center;
    box-shadow: 0 8rpx 20rpx rgba(17, 17, 17, 0.08);
}

.btn-setting-text {
    font-size: 24rpx;
    font-weight: 600;
    color: #fff;
    letter-spacing: 2rpx;
}

.status-pill {
    display: flex;
    align-items: center;
    padding: 10rpx 24rpx;
    border-radius: 40rpx;
    gap: 10rpx;
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

.pill-on .pill-text {
    color: #2e7d32;
}

.pill-off .pill-text {
    color: #9e9e9e;
}

/* 统计 */
.stats-row {
    display: flex;
    align-items: center;
    justify-content: space-around;
    background: #f7f8fa;
    border-radius: 20rpx;
    padding: 28rpx 0;
    margin-bottom: 32rpx;
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

.num-warn {
    color: #f57c00;
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

/* 控制按钮 */
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

.btn-diag-text {
    font-size: 26rpx;
    color: #888;
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

/* ── 记录列表 ── */
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
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 120rpx 0;
    gap: 20rpx;
}

.empty-icon {
    font-size: 80rpx;
}

.empty-text {
    font-size: 28rpx;
    color: #bdbdbd;
}

/* 卡片 */
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
