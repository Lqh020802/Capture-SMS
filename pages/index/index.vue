<template>
    <view class="container">

        <!-- 状态卡片 -->
        <view class="status-card" :class="monitoring ? 'status-on' : 'status-off'">
            <view class="status-dot" :class="monitoring ? 'dot-on' : 'dot-off'"></view>
            <text class="status-text">{{ monitoring ? '监控运行中' : '监控已停止' }}</text>
            <view class="status-actions">
                <button class="btn-ctrl" :class="monitoring ? 'btn-stop' : 'btn-start'"
                    @click="toggleMonitor">
                    {{ monitoring ? '停止' : '启动' }}
                </button>
                <button class="btn-setting" @click="goSetting">设置</button>
            </view>
        </view>

        <!-- 统计数据 -->
        <view class="stats-row">
            <view class="stat-item">
                <text class="stat-num">{{ totalCount }}</text>
                <text class="stat-label">累计接收</text>
            </view>
            <view class="stat-divider"></view>
            <view class="stat-item">
                <text class="stat-num">{{ todayCount }}</text>
                <text class="stat-label">今日接收</text>
            </view>
            <view class="stat-divider"></view>
            <view class="stat-item">
                <text class="stat-num">{{ failCount }}</text>
                <text class="stat-label">待重传</text>
            </view>
        </view>

        <!-- 日志列表 -->
        <view class="log-header">
            <text class="log-title">最近记录</text>
            <text class="log-clear" @click="clearLogs">清空</text>
        </view>

        <scroll-view class="log-list" scroll-y>
            <view v-if="logs.length === 0" class="log-empty">
                <text class="log-empty-text">暂无短信记录</text>
            </view>
            <view v-for="(item, idx) in logs" :key="idx" class="log-item">
                <view class="log-item-header">
                    <view class="sim-badge" :class="item.sim_slot === 0 ? 'sim1' : 'sim2'">
                        {{ item.sim_name || ('SIM' + (item.sim_slot + 1)) }}
                    </view>
                    <text class="log-sender">{{ item.sender }}</text>
                    <text class="log-time">{{ formatTime(item.timestamp) }}</text>
                </view>
                <text class="log-body">{{ item.body }}</text>
                <view class="log-status" :class="item.uploaded ? 'up-ok' : 'up-fail'">
                    {{ item.uploaded ? '已上报' : '待上报' }}
                </view>
            </view>
        </scroll-view>

    </view>
</template>

<script>
    import { startSmsMonitor, stopSmsMonitor, getMonitorStatus, eventBus } from '@/utils/sms-monitor.js'

    export default {
        data() {
            return {
                monitoring : false,
                logs       : [],
                totalCount : 0,
                todayCount : 0,
                failCount  : 0
            }
        },

        onLoad() {
            this.monitoring = getMonitorStatus()
            this._loadStats()

            // 监听新短信事件
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

            goSetting() {
                uni.navigateTo({ url: '/pages/setting/setting' })
            },

            clearLogs() {
                uni.showModal({
                    title: '确认清空',
                    content: '将清空本地日志记录（不影响已上报数据）',
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
                return `${d.getMonth()+1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
            },

            _loadStats() {
                this.totalCount = uni.getStorageSync('stat_total') || 0
                this.todayCount = uni.getStorageSync('stat_today') || 0
                this.failCount  = 0

                try {
                    const pending = uni.getStorageSync('sms_pending')
                    this.failCount = pending ? JSON.parse(pending).length : 0
                } catch {}
            },

            _saveStats() {
                uni.setStorageSync('stat_total', this.totalCount)
                uni.setStorageSync('stat_today', this.todayCount)
            }
        }
    }
</script>

<style scoped>
.container {
    padding: 20rpx;
    min-height: 100vh;
    background: #f5f5f5;
}

/* 状态卡片 */
.status-card {
    border-radius: 16rpx;
    padding: 32rpx;
    margin-bottom: 20rpx;
    display: flex;
    flex-direction: column;
    align-items: center;
}
.status-on  { background: linear-gradient(135deg, #1a73e8, #0d5cbf); }
.status-off { background: linear-gradient(135deg, #757575, #555); }

.status-dot {
    width: 20rpx;
    height: 20rpx;
    border-radius: 50%;
    margin-bottom: 16rpx;
}
.dot-on  { background: #4cff82; box-shadow: 0 0 12rpx #4cff82; }
.dot-off { background: #bbb; }

.status-text {
    color: #fff;
    font-size: 36rpx;
    font-weight: bold;
    margin-bottom: 24rpx;
}

.status-actions {
    display: flex;
    gap: 20rpx;
}

.btn-ctrl {
    padding: 12rpx 48rpx;
    border-radius: 40rpx;
    font-size: 28rpx;
    border: none;
    line-height: 1.6;
}
.btn-start { background: #4cff82; color: #1a3; }
.btn-stop  { background: #ff5252; color: #fff; }

.btn-setting {
    padding: 12rpx 40rpx;
    border-radius: 40rpx;
    font-size: 28rpx;
    background: rgba(255,255,255,0.25);
    color: #fff;
    border: none;
    line-height: 1.6;
}

/* 统计 */
.stats-row {
    background: #fff;
    border-radius: 16rpx;
    display: flex;
    justify-content: space-around;
    align-items: center;
    padding: 24rpx 0;
    margin-bottom: 20rpx;
}
.stat-item { display: flex; flex-direction: column; align-items: center; }
.stat-num  { font-size: 44rpx; font-weight: bold; color: #1a73e8; }
.stat-label{ font-size: 24rpx; color: #888; margin-top: 4rpx; }
.stat-divider { width: 2rpx; height: 60rpx; background: #eee; }

/* 日志 */
.log-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12rpx;
    padding: 0 8rpx;
}
.log-title { font-size: 28rpx; color: #444; font-weight: bold; }
.log-clear { font-size: 26rpx; color: #999; }

.log-list {
    height: 900rpx;
}

.log-empty {
    display: flex;
    justify-content: center;
    padding: 80rpx 0;
}
.log-empty-text { color: #bbb; font-size: 28rpx; }

.log-item {
    background: #fff;
    border-radius: 12rpx;
    padding: 24rpx;
    margin-bottom: 16rpx;
}

.log-item-header {
    display: flex;
    align-items: center;
    margin-bottom: 12rpx;
}

.sim-badge {
    font-size: 20rpx;
    padding: 4rpx 14rpx;
    border-radius: 20rpx;
    color: #fff;
    margin-right: 14rpx;
}
.sim1 { background: #1a73e8; }
.sim2 { background: #e67e22; }

.log-sender { font-size: 28rpx; font-weight: bold; color: #333; flex: 1; }
.log-time   { font-size: 22rpx; color: #aaa; }

.log-body {
    font-size: 28rpx;
    color: #555;
    line-height: 1.5;
    margin-bottom: 10rpx;
}

.log-status {
    font-size: 22rpx;
    text-align: right;
}
.up-ok   { color: #4caf50; }
.up-fail { color: #ff5252; }
</style>
