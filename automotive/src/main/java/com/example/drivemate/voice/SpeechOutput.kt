package com.example.drivemate.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 음성으로 말하는 통로 (차량 스피커) */
interface SpeechOutput {
    /** 지금 말하는 중인지 */
    val speaking: StateFlow<Boolean>

    /** 준비를 시작한다 (여러 번 불러도 한 번만) */
    fun start()

    /**
     * 문장을 말한다. 이미 말하는 중이거나 준비가 안 됐으면 말하지 않고 false를 돌려준다 (말이 겹치지 않게).
     * @param onDone 말이 끝났을 때(또는 실패했을 때) 한 번 불린다
     */
    fun speak(text: String, onDone: () -> Unit = {}): Boolean

    /** 말하던 것을 멈춘다 */
    fun stop()

    /** 지금 차량 내비가 음성 안내를 하고 있는지 (내비와 같은 시간에 말하지 않기 위해) */
    fun isNaviSpeaking(): Boolean
}

/**
 * Pleos TextToSpeech SDK 구현. ON_DEVICE 모드라 인터넷이나 앱 등록 없이 차량 안에서 소리를 만든다.
 *
 * 에뮬레이터로 확인된 주의점:
 *  - addEventListener는 initialize()가 "성공한 뒤"에 불러야 한다 (먼저 부르면 앱이 죽는다)
 *  - setLocale(한국어)을 하지 않으면 한국어 문장이 소리 없이 끝난다
 */
@Singleton
class PleosSpeechOutput @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechOutput {

    private val tts = TextToSpeech(context, Mode.ON_DEVICE)
    private val handler = Handler(Looper.getMainLooper())   // 모든 처리를 메인 스레드 한 곳에서 한다
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking

    private var started = false
    private var ready = false
    private var onDone: (() -> Unit)? = null

    // "끝났다"는 신호가 안 오는 사고를 대비한 안전장치: 20초 뒤 강제로 끝난 것으로 처리
    private val timeout = Runnable { finish() }

    private val listener = object : EventListener {
        override fun onReady() {}
        override fun onStart() {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onDone() { handler.post { finish() } }
        override fun onStop() { handler.post { finish() } }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "TTS 오류: $errorType")
            handler.post { finish() }
        }
    }

    override fun start() {
        if (started) return
        started = true
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                handler.post {
                    tts.addEventListener(listener)
                    tts.setLocale(Locale.KOREA)
                    ready = true
                    Log.i(TAG, "TTS 준비됨")
                }
            }

            override fun onFailure(errorType: ErrorType) {
                Log.w(TAG, "TTS 초기화 실패: $errorType")
            }
        })
    }

    override fun speak(text: String, onDone: () -> Unit): Boolean {
        if (!ready || _speaking.value) return false
        _speaking.value = true
        this.onDone = onDone
        Log.i(TAG, "SAY: $text")   // 테스트에서 "무슨 말을 했는지" 확인하는 기록
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 20_000L)
        try {
            tts.speak(text, false)
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak 오류: ${e.message}")
            finish()
        }
        return true
    }

    override fun stop() {
        if (!_speaking.value) return
        try { tts.stop() } catch (_: Exception) {}
        finish()
    }

    private fun finish() {
        handler.removeCallbacks(timeout)
        if (!_speaking.value) return
        _speaking.value = false
        val callback = onDone
        onDone = null
        callback?.invoke()
    }

    // 지금 재생 중인 소리 가운데 "내비 길 안내" 용도가 있는지 본다 (안드로이드 표준 기능)
    override fun isNaviSpeaking(): Boolean = try {
        audioManager.activePlaybackConfigurations.any {
            it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        }
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val TAG = "DriveMate"
    }
}
