<template>
    <view class="page">

        <!-- 顶部状态区 -->
        <view class="header">
            <!-- 状态栏占位 -->
            <view :style="{ height: statusBarHeight + 'px' }"></view>
            <view class="header-top">
                <text class="app-title">短信监控</text>
                <view class="status-pill" :class="monitoring ? 'pill-on' : 'pill-off'">
                    <view class="pill-dot" :class="monitoring ? 'dot-on' : 'dot-off'"></view>
                    <text class="pill-text">{{ monitoring ? '运行中' : '已停止' }}</text>
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
                            <text class="sim-tag-text">{{ item.sim_name || ('SIM' + (item.sim_slot + 1)) }}</text>
                        </view>
                        <text class="log-sender">{{ item.sender }}</text>
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
    import { startSmsMonitor, stopSmsMonitor, getMonitorStatus, eventBus } from '@/utils/sms-monitor.js'

    export default {
        data() {
            return {
                monitoring      : false,
                logs            : [],
                totalCount      : 0,
                todayCount      : 0,
                failCount       : 0,
                statusBarHeight : 0
            }
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
                return `${d.getMonth()+1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
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
    align-items: center;
    justify-content: space-between;
    margin-bottom: 40rpx;
}

.app-title {
    font-size: 40rpx;
    font-weight: 700;
    color: #111;
    letter-spacing: 2rpx;
}

.status-pill {
    display: flex;
    align-items: center;
    padding: 10rpx 24rpx;
    border-radius: 40rpx;
    gap: 10rpx;
}
.pill-on  { background: #e8f5e9; }
.pill-off { background: #f5f5f5; }

.pill-dot {
    width: 14rpx;
    height: 14rpx;
    border-radius: 50%;
}
.dot-on  { background: #4caf50; }
.dot-off { background: #bdbdbd; }

.pill-text {
    font-size: 24rpx;
    font-weight: 600;
}
.pill-on .pill-text  { color: #2e7d32; }
.pill-off .pill-text { color: #9e9e9e; }

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
.num-warn { color: #f57c00; }
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
.ctrl-row { display: flex; }
.btn-main {
    flex: 1;
    height: 96rpx;
    border-radius: 16rpx;
    display: flex;
    align-items: center;
    justify-content: center;
}
.btn-start { background: #111; }
.btn-stop  { background: #fff; border: 2rpx solid #e0e0e0; }

.btn-main-text {
    font-size: 30rpx;
    font-weight: 600;
    letter-spacing: 2rpx;
}
.btn-start .btn-main-text { color: #fff; }
.btn-stop  .btn-main-text { color: #f44336; }

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

.log-list { height: 820rpx; }

.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 120rpx 0;
    gap: 20rpx;
}
.empty-icon { font-size: 80rpx; }
.empty-text { font-size: 28rpx; color: #bdbdbd; }

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
.tag-blue   { background: #e3f2fd; }
.tag-orange { background: #fff3e0; }

.sim-tag-text {
    font-size: 20rpx;
    font-weight: 600;
}
.tag-blue .sim-tag-text   { color: #1565c0; }
.tag-orange .sim-tag-text { color: #e65100; }

.log-sender {
    font-size: 28rpx;
    font-weight: 600;
    color: #212121;
    flex: 1;
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
.dot-ok   { background: #66bb6a; }
.dot-fail { background: #ef5350; }

.upload-text { font-size: 22rpx; }
.text-ok   { color: #66bb6a; }
.text-fail { color: #ef5350; }

.safe-bottom {
    background: #ffffff;
    padding-bottom: env(safe-area-inset-bottom);
    height: env(safe-area-inset-bottom);
}
</style>
