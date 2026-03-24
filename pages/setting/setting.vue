<template>
<view class="page">

    <!-- 主内容区 -->
    <view class="content">

        <!-- 服务器配置 -->
        <view class="card">
            <view class="card-header">
                <view class="card-icon card-icon-network">
                    <view class="icon-network-ring"></view>
                    <view class="icon-network-dot"></view>
                </view>
                <text class="card-title">服务器配置</text>
            </view>

            <view class="field">
                <text class="field-label">接收地址</text>
                <input class="field-input" v-model="form.serverUrl" placeholder="http://192.168.30.70:8014/sms/upload"
                    placeholder-class="field-placeholder" />
            </view>

            <view class="field">
                <text class="field-label">鉴权令牌</text>
                <input class="field-input" v-model="form.token" placeholder="可选，留空则不鉴权"
                    placeholder-class="field-placeholder" />
            </view>
        </view>

        <!-- 连接测试 -->
        <view class="card">
            <view class="card-header">
                <view class="card-icon card-icon-flash">
                    <view class="icon-flash-main"></view>
                </view>
                <text class="card-title">连接测试</text>
            </view>

            <button class="btn-primary" :class="{ 'btn-loading': testing }" @click="testConnection" :disabled="testing">
                <text class="btn-text">{{ testing ? '测试中...' : '发送测试请求' }}</text>
            </button>

            <view v-if="testMsg" class="test-feedback" :class="testOk ? 'feedback-success' : 'feedback-error'">
                <text class="feedback-icon">{{ testOk ? '✓' : '✗' }}</text>
                <text class="feedback-text">{{ testMsg }}</text>
            </view>
        </view>

        <!-- 数据管理 -->
        <view class="card">
            <view class="card-header">
                <view class="card-icon card-icon-stack">
                    <view class="icon-stack-top"></view>
                    <view class="icon-stack-bottom"></view>
                </view>
                <text class="card-title">数据管理</text>
            </view>

            <view class="stat-row">
                <text class="stat-label">待重传条数</text>
                <text class="stat-value">{{ pendingCount }}</text>
            </view>

            <view class="btn-group">
                <button class="btn-secondary" @click="retryPending">
                    <text class="btn-text">立即重传</text>
                </button>
                <button class="btn-ghost" @click="clearPending">
                    <text class="btn-text">清空缓存</text>
                </button>
            </view>
        </view>

    </view>

    <!-- 底部保存按钮 -->
    <view class="footer">
        <button class="btn-save" @click="save">
            <text class="btn-save-text">保存配置</text>
        </button>
    </view>

</view>
</template>

<script>
import { saveConfig, loadConfig, retryPendingNow } from '@/utils/api.js'

export default {
    data() {
        return {
            form: {
                serverUrl: '',
                token: ''
            },
            testing: false,
            testMsg: '',
            testOk: false,
            pendingCount: 0
        }
    },

    onLoad() {
        const cfg = loadConfig()
        this.form.serverUrl = cfg.serverUrl
        this.form.token = cfg.token
        this._loadPendingCount()
    },

    methods: {
        save() {
            const url = (this.form.serverUrl || '').trim()
            if (!url) {
                uni.showToast({ title: '请填写服务器地址', icon: 'none' })
                return
            }
            saveConfig(this.form)
            uni.showToast({ title: '保存成功', icon: 'success' })
        },

        testConnection() {
            if (!this.form.serverUrl) {
                uni.showToast({ title: '请先填写服务器地址', icon: 'none' })
                return
            }
            this.testing = true
            this.testMsg = ''

            const header = { 'Content-Type': 'application/json' }
            if (this.form.token) header['Authorization'] = 'Bearer ' + this.form.token

            uni.request({
                url: this.form.serverUrl,
                method: 'POST',
                header,
                data: {
                    device_id: 'test',
                    sender: '10086',
                    body: '【测试】这是一条测试短信',
                    sim_slot: 0,
                    sim_name: 'SIM1',
                    timestamp: Date.now()
                },
                timeout: 8000,
                success: (res) => {
                    this.testing = false
                    if (res.statusCode >= 200 && res.statusCode < 300) {
                        this.testOk = true
                        this.testMsg = '✓ 连接成功，服务器响应正常'
                    } else {
                        this.testOk = false
                        this.testMsg = `✗ 服务器返回 ${res.statusCode}`
                    }
                },
                fail: (err) => {
                    this.testing = false
                    this.testOk = false
                    this.testMsg = '✗ 连接失败：' + (err.errMsg || '网络错误')
                }
            })
        },

        retryPending() {
            const pending = this._getPending()
            if (!pending.length) {
                uni.showToast({ title: '没有待重传记录', icon: 'none' })
                return
            }
            retryPendingNow()
            uni.showToast({ title: `开始重传 ${pending.length} 条`, icon: 'none' })
            setTimeout(() => {
                this._loadPendingCount()
            }, 2000)
        },

        clearPending() {
            uni.showModal({
                title: '确认清空',
                content: '将清除所有待重传的短信缓存',
                success: (res) => {
                    if (res.confirm) {
                        uni.setStorageSync('sms_pending', '[]')
                        this.pendingCount = 0
                        uni.showToast({ title: '已清空', icon: 'success' })
                    }
                }
            })
        },

        _loadPendingCount() {
            this.pendingCount = this._getPending().length
        },

        _getPending() {
            try {
                const str = uni.getStorageSync('sms_pending')
                return str ? JSON.parse(str) : []
            } catch { return [] }
        }
    }
}
</script>

<style scoped>
.page {
    min-height: 100vh;
    background: #f6f7f9;
}

.content {
    padding: 24rpx;
}

.card {
    background: #ffffff;
    border: 1rpx solid #eceef2;
    border-radius: 28rpx;
    padding: 30rpx;
    margin-bottom: 20rpx;
    box-shadow: 0 12rpx 40rpx rgba(15, 23, 42, 0.04);
}

.card-header {
    display: flex;
    align-items: center;
    gap: 12rpx;
    margin-bottom: 28rpx;
}

.card-icon {
    position: relative;
    width: 52rpx;
    height: 52rpx;
    border-radius: 16rpx;
    background: #f7f8fa;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1rpx solid #eceff3;
}

.card-icon-network,
.card-icon-flash,
.card-icon-stack {
    overflow: hidden;
}

.icon-network-ring {
    width: 26rpx;
    height: 26rpx;
    border: 3rpx solid #111827;
    border-radius: 50%;
    opacity: 0.9;
}

.icon-network-dot {
    position: absolute;
    width: 8rpx;
    height: 8rpx;
    border-radius: 50%;
    background: #111827;
}

.icon-flash-main {
    width: 16rpx;
    height: 24rpx;
    background: #111827;
    clip-path: polygon(48% 0, 100% 0, 66% 42%, 100% 42%, 28% 100%, 44% 58%, 8% 58%);
}

.icon-stack-top,
.icon-stack-bottom {
    position: absolute;
    width: 22rpx;
    height: 14rpx;
    border: 2rpx solid #111827;
    border-radius: 6rpx;
    background: rgba(17, 24, 39, 0.04);
}

.icon-stack-top {
    transform: translateY(-6rpx);
}

.icon-stack-bottom {
    transform: translateY(8rpx);
}

.card-title {
    font-size: 30rpx;
    font-weight: 600;
    color: #111827;
    letter-spacing: 1rpx;
}

.field {
    margin-bottom: 24rpx;
}

.field:last-child {
    margin-bottom: 0;
}

.field-label {
    display: block;
    margin-bottom: 14rpx;
    font-size: 24rpx;
    color: #6b7280;
    letter-spacing: 1rpx;
}

.field-input {
    width: 100%;
    height: 92rpx;
    padding: 0 24rpx;
    box-sizing: border-box;
    border-radius: 22rpx;
    border: 1rpx solid #e6e9ef;
    background: #fbfbfc;
    color: #111827;
    font-size: 28rpx;
    transition: all 0.2s ease;
}

.field-placeholder {
    color: #b1b8c3;
}

.stat-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 24rpx;
    padding: 24rpx;
    border-radius: 22rpx;
    background: #fafbfc;
    border: 1rpx solid #edf0f3;
}

.stat-label {
    font-size: 26rpx;
    color: #4b5563;
}

.stat-value {
    min-width: 84rpx;
    height: 56rpx;
    padding: 0 18rpx;
    border-radius: 999rpx;
    background: #111827;
    color: #ffffff;
    font-size: 26rpx;
    font-weight: 700;
    text-align: center;
    line-height: 56rpx;
}

.btn-group {
    display: flex;
    gap: 16rpx;
}

.btn-primary,
.btn-secondary,
.btn-ghost,
.btn-save {
    display: flex;
    align-items: center;
    justify-content: center;
    border: none;
    overflow: hidden;
}

.btn-primary::after,
.btn-secondary::after,
.btn-ghost::after,
.btn-save::after {
    border: none;
}

.btn-primary {
    height: 92rpx;
    border-radius: 22rpx;
    background: #111827;
    box-shadow: 0 16rpx 36rpx rgba(17, 24, 39, 0.12);
}

.btn-loading {
    opacity: 0.82;
}

.btn-secondary,
.btn-ghost {
    flex: 1;
    height: 84rpx;
    border-radius: 20rpx;
}

.btn-secondary {
    background: #111827;
}

.btn-ghost {
    background: #f3f4f6;
}

.btn-text {
    font-size: 28rpx;
    font-weight: 600;
    letter-spacing: 1rpx;
}

.btn-primary .btn-text,
.btn-secondary .btn-text,
.btn-save-text {
    color: #ffffff;
}

.btn-ghost .btn-text {
    color: #4b5563;
}

.test-feedback {
    margin-top: 22rpx;
    padding: 20rpx 22rpx;
    border-radius: 20rpx;
    display: flex;
    align-items: center;
    gap: 12rpx;
}

.feedback-success {
    background: #f3fbf6;
    border: 1rpx solid #d7f1df;
}

.feedback-error {
    background: #fff6f6;
    border: 1rpx solid #ffe0e0;
}

.feedback-icon {
    font-size: 26rpx;
    font-weight: 700;
}

.feedback-success .feedback-icon,
.feedback-success .feedback-text {
    color: #15803d;
}

.feedback-error .feedback-icon,
.feedback-error .feedback-text {
    color: #dc2626;
}

.feedback-text {
    flex: 1;
    font-size: 25rpx;
    line-height: 1.5;
}

.footer {
    position: sticky;
    bottom: 0;
    padding: 12rpx 24rpx calc(24rpx + env(safe-area-inset-bottom));
    background: linear-gradient(to top, #f6f7f9 72%, rgba(246, 247, 249, 0));
}

.btn-save {
    width: 100%;
    height: 96rpx;
    border-radius: 999rpx;
    background: #111827;
    box-shadow: 0 18rpx 44rpx rgba(17, 24, 39, 0.16);
}

.btn-save-text {
    font-size: 30rpx;
    font-weight: 700;
    letter-spacing: 2rpx;
}
</style>
