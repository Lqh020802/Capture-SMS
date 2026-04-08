/**
 * 音频播放器封装（单例模式）
 * 用于通话录音回听，同一时间只播放一条录音
 */

let _instance = null

export function getAudioPlayer() {
    if (_instance) return _instance
    _instance = new AudioPlayer()
    return _instance
}

export function destroyAudioPlayer() {
    if (_instance) {
        _instance.stop()
        _instance = null
    }
}

class AudioPlayer {
    constructor() {
        this._audio = null
        this._currentPath = ''
        this._state = 'stopped' // playing | paused | stopped | error
        this._progressCb = null
        this._stateCb = null
        this._timer = null
        this._isFallback = false
    }

    /** 播放指定文件，自动停止上一个 */
    play(filePath) {
        if (this._currentPath === filePath && this._state === 'paused') {
            return this.resume()
        }
        this.stop()
        this._currentPath = filePath
        this._isFallback = false

        const audio = uni.createInnerAudioContext()
        // Android 本地文件需要 file:// 前缀
        audio.src = filePath.startsWith('/') ? ('file://' + filePath) : filePath
        audio.autoplay = true

        audio.onPlay(() => {
            this._setState('playing')
            this._startProgressTimer()
        })
        audio.onPause(() => {
            this._setState('paused')
            this._stopProgressTimer()
        })
        audio.onEnded(() => {
            this._setState('stopped')
            this._stopProgressTimer()
            this._emitProgress(0, audio.duration || 0)
        })
        audio.onError((err) => {
            console.error('[AudioPlayer] innerAudio error:', err)
            this._tryFallback(filePath)
        })

        this._audio = audio
    }

    pause() {
        if (this._audio && this._state === 'playing') {
            if (this._isFallback) {
                try { this._audio.pause() } catch (_) {}
            } else {
                this._audio.pause()
            }
        }
    }

    resume() {
        if (this._audio && this._state === 'paused') {
            if (this._isFallback) {
                try {
                    this._audio.resume()
                    this._setState('playing')
                    this._startProgressTimer()
                } catch (_) {}
            } else {
                this._audio.play()
            }
        }
    }

    stop() {
        this._stopProgressTimer()
        if (this._audio) {
            try { this._audio.stop() } catch (_) {}
            try { this._audio.destroy() } catch (_) {}
            this._audio = null
        }
        this._currentPath = ''
        this._isFallback = false
        this._setState('stopped')
    }

    seek(time) {
        if (this._audio) {
            if (this._isFallback) {
                try { this._audio.seekTo(time) } catch (_) {}
            } else {
                this._audio.seek(time)
            }
        }
    }

    get currentPath() { return this._currentPath }
    get state() { return this._state }

    onProgress(cb) { this._progressCb = cb }
    onStateChange(cb) { this._stateCb = cb }

    // ─── 内部方法 ───────────────────────────────────

    _setState(s) {
        this._state = s
        if (this._stateCb) this._stateCb(s, this._currentPath)
    }

    _emitProgress(currentTime, duration) {
        if (!this._progressCb) return
        const percent = duration > 0 ? Math.min(currentTime / duration, 1) : 0
        this._progressCb({ currentTime, duration, percent }, this._currentPath)
    }

    _startProgressTimer() {
        this._stopProgressTimer()
        this._timer = setInterval(() => {
            if (!this._audio) return
            if (this._isFallback) return // plus.audio 无法实时获取进度
            this._emitProgress(this._audio.currentTime, this._audio.duration)
        }, 500)
    }

    _stopProgressTimer() {
        if (this._timer) {
            clearInterval(this._timer)
            this._timer = null
        }
    }

    /** plus.audio 兜底方案 */
    _tryFallback(filePath) {
        // #ifdef APP-PLUS
        try {
            this._stopProgressTimer()
            if (this._audio) {
                try { this._audio.destroy() } catch (_) {}
                this._audio = null
            }

            const player = plus.audio.createPlayer(filePath)
            player.play(
                () => {
                    // 播放结束回调
                    this._setState('stopped')
                },
                (err) => {
                    console.error('[AudioPlayer] fallback play failed:', err)
                    this._setState('error')
                }
            )
            this._isFallback = true
            this._audio = player
            this._setState('playing')
        } catch (e) {
            console.error('[AudioPlayer] fallback init failed:', e)
            this._setState('error')
        }
        // #endif
    }
}
