package com.example.drivemate.shared

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 화면(DriveCoach)이 없을 때도 한 문장을 말해 주는 "일회용" TTS.
 * Gleo가 질문했을 때 앱 화면이 꺼져 있어도 답을 말하려고 쓴다. 말이 끝나면(또는 실패/시간 초과) 스스로 정리한다.
 * 주의: 브로드캐스트 수신기는 오래 살 수 없어서(약 10초) 안전장치 시간을 짧게(8초) 둔다.
 */
class OneShotSpeaker(context: Context) {
    private val tts = TextToSpeech(context, Mode.ON_DEVICE)
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private var listenerAdded = false
    private var onFinished: (() -> Unit)? = null

    private val listener = object : EventListener {
        override fun onReady() {}
        override fun onStart() {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onDone() { handler.post { finish() } }
        override fun onStop() { handler.post { finish() } }
        override fun onError(errorType: ErrorType) { handler.post { finish() } }
    }

    fun speak(text: String, onFinished: () -> Unit) {
        this.onFinished = onFinished
        handler.postDelayed({ finish() }, SAFETY_TIMEOUT_MS)   // 어떤 이유로든 끝 신호가 없으면 강제로 정리
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                handler.post {
                    if (finished) return@post
                    tts.addEventListener(listener)   // 주의: 초기화가 끝난 뒤에 등록해야 한다
                    listenerAdded = true
                    tts.setLocale(TTS_LOCALE)        // 한국어 지정 (안 하면 영어 엔진이라 무음)
                    tts.speak(text)
                }
            }

            override fun onFailure(errorType: ErrorType) {
                handler.post { finish() }
            }
        })
    }

    private fun finish() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        try {
            if (listenerAdded) tts.removeEventListener(listener)
            tts.release()
        } catch (e: Exception) {
            // 정리 중 오류는 무시
        }
        onFinished?.invoke()
    }

    companion object {
        private const val SAFETY_TIMEOUT_MS = 8000L
    }
}
