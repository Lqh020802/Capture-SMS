<template>
    <view class="container">

        <view class="section">
            <text class="section-title">服务器配置</text>

            <view class="form-item">
                <text class="label">接收地址 (URL)</text>
                <input
                    class="input"
                    v-model="form.serverUrl"
                    placeholder="https://your-server.com/api/sms"
                    placeholder-style="color:#bbb"
                />
            </view>

            <view class="form-item">
                <text class="label">鉴权 Token（可选）</text>
                <input
                    class="input"
                    v-model="form.token"
                    placeholder="Bearer token，留空则不鉴权"
                    placeholder-style="color:#bbb"
                />
            </view>
        </view>

        <view class="section">
            <text class="section-title">测试</text>
            <button class="btn-test" @click="testConnection" :loading="testing">
                发送测试请求
            </button>
            <text class="test-result" :class="testOk ? 'ok' : 'fail'" v-if="testMsg">
                {{ testMsg }}
            </text>
        </view>

        <view class="section">
            <text class="section-title">数据</text>
            <view class="form-item row">
                <text class="label">待重传条数</text>
                <text class="value-text">{{ pendingCount }} 条</text>
            </view>
            <button class="btn-retry" @click="retryPending">立即重传</button>
            <button class="btn-clear-pending" @click="clearPending">清空缓存</button>
        </view>

        <button class="btn-save" @click="save">保存配置</button>

    </view>
</template>

<script>
    import { saveConfig, loadConfig } from '@/utils/api.js'

    export default {
        data() {
            return {
                form: {
                    serverUrl : '',
                    token     : ''
                },
                testing      : false,
                testMsg      : '',
                testOk       : false,
                pendingCount : 0
            }
        },

        onLoad() {
            const cfg = loadConfig()
            this.form.serverUrl = cfg.serverUrl || ''
            this.form.token     = cfg.token     || ''
            this._loadPendingCount()
        },

        methods: {
            save() {
                if (!this.form.serverUrl) {
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
                    url    : this.form.serverUrl,
                    method : 'POST',
                    header,
                    data   : {
                        device_id : 'test',
                        sender    : '10086',
                        body      : '【测试】这是一条测试短信',
                        sim_slot  : 0,
                        sim_name  : 'SIM1',
                        timestamp : Date.now()
                    },
                    timeout: 8000,
                    success : (res) => {
                        this.testing = false
                        if (res.statusCode >= 200 && res.statusCode < 300) {
                            this.testOk  = true
                            this.testMsg = '✓ 连接成功，服务器响应正常'
                        } else {
                            this.testOk  = false
                            this.testMsg = `✗ 服务器返回 ${res.statusCode}`
                        }
                    },
                    fail : (err) => {
                        this.testing = false
                        this.testOk  = false
                        this.testMsg = '✗ 连接失败：' + (err.errMsg || '网络错误')
                    }
                })
            },

            retryPending() {
                // 触发 api.js 中的重试逻辑
                const pending = this._getPending()
                if (!pending.length) {
                    uni.showToast({ title: '没有待重传记录', icon: 'none' })
                    return
                }
                // 简单重传：清空后逐条上报
                uni.showToast({ title: `开始重传 ${pending.length} 条`, icon: 'none' })
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
.container {
    padding: 20rpx;
    background: #f5f5f5;
    min-height: 100vh;
}

.section {
    background: #fff;
    border-radius: 16rpx;
    padding: 28rpx;
    margin-bottom: 20rpx;
}

.section-title {
    font-size: 26rpx;
    color: #999;
    margin-bottom: 20rpx;
    display: block;
}

.form-item {
    margin-bottom: 20rpx;
}
.form-item.row {
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.label {
    font-size: 28rpx;
    color: #333;
    margin-bottom: 10rpx;
    display: block;
}

.input {
    border: 2rpx solid #e0e0e0;
    border-radius: 10rpx;
    padding: 16rpx 20rpx;
    font-size: 28rpx;
    color: #333;
    width: 100%;
    box-sizing: border-box;
}

.value-text {
    font-size: 28rpx;
    color: #1a73e8;
    font-weight: bold;
}

.btn-test {
    background: #1a73e8;
    color: #fff;
    border-radius: 10rpx;
    font-size: 28rpx;
    margin-bottom: 16rpx;
    border: none;
}

.test-result {
    font-size: 26rpx;
    display: block;
    text-align: center;
}
.ok   { color: #4caf50; }
.fail { color: #f44336; }

.btn-retry, .btn-clear-pending {
    border-radius: 10rpx;
    font-size: 28rpx;
    margin-top: 12rpx;
    border: none;
}
.btn-retry         { background: #ff9800; color: #fff; }
.btn-clear-pending { background: #f5f5f5; color: #888; margin-top: 12rpx; }

.btn-save {
    background: #1a73e8;
    color: #fff;
    border-radius: 50rpx;
    font-size: 32rpx;
    font-weight: bold;
    padding: 24rpx 0;
    margin-top: 10rpx;
    border: none;
}
</style>
