package com.example.drivemate.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 운전자의 말을 글자로 바꾸는 통로 */
interface SpeechInput {
    /**
     * 듣기를 시작한다. 말이 끝나면 인식된 문장을, 못 들었거나 오류면 null을 onResult로 한 번 알려준다.
     */
    fun listen(onResult: (String?) -> Unit)

    /** 듣기를 그만둔다 (onResult는 불리지 않는다) */
    fun cancel()
}

/**
 * Pleos SpeechToText SDK 구현. ON_DEVICE 모드(인터넷·앱 등록 불필요), 한국어.
 * 녹음한 소리는 저장하지 않고, 인식된 글자만 질문 기록에 남긴다.
 */
@Singleton
class PleosSpeechInput @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechInput {

    private val handler = Handler(Looper.getMainLooper())
    private var stt: SpeechToText? = null
    private var ready = false
    private var callback: ((String?) -> Unit)? = null
    private var lastText = ""

    // 말이 없으면 LISTEN_TIMEOUT_MS 뒤에 듣기를 끝낸다 (그때까지 들은 글자가 있으면 그것을 쓴다)
    private val timeout = Runnable { deliver(lastText.ifBlank { null }) }

    private val listener = object : ResultListener {
        override fun onUpdated(text: String, completed: Boolean) {
            handler.post {
                if (callback == null) return@post
                lastText = text
                if (completed) deliver(text.ifBlank { null })
            }
        }

        override fun onUpdatedEpdData(start: Long, end: Long) {}
        override fun onStartedRecognition() {}
        override fun onEndedRecognition() {
            handler.post { if (callback != null) deliver(lastText.ifBlank { null }) }
        }

        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "STT 오류: $errorType")
            handler.post { deliver(null) }
        }

        override fun onReady() {}
        override fun onUpdatedRms(rms: Double) {}
    }

    override fun listen(onResult: (String?) -> Unit) {
        callback = onResult
        lastText = ""
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, LISTEN_TIMEOUT_MS)

        if (ready) {
            request()
            return
        }
        // 처음 한 번: SDK 준비 -> 한국어 설정 -> 듣기 시작
        try {
            val engine = SpeechToText(context, Mode.ON_DEVICE)
            stt = engine
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    handler.post {
                        engine.setLocale(Locale.KOREA)
                        engine.addListener(listener)
                        ready = true
                        if (callback != null) request()
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "STT 초기화 실패: $errorType")
                    handler.post { deliver(null) }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "STT 오류: ${e.message}")
            deliver(null)
        }
    }

    private fun request() {
        try {
            stt?.request()
        } catch (e: Exception) {
            Log.w(TAG, "STT request 오류: ${e.message}")
            deliver(null)
        }
    }

    override fun cancel() {
        callback = null
        handler.removeCallbacks(timeout)
        try { stt?.stop() } catch (_: Exception) {}
    }

    // 결과를 한 번만 전달하고 듣기를 끝낸다
    private fun deliver(text: String?) {
        val cb = callback ?: return
        callback = null
        handler.removeCallbacks(timeout)
        try { stt?.stop() } catch (_: Exception) {}
        Log.i(TAG, "HEARD: $text")
        cb(text)
    }

    private companion object {
        const val TAG = "DriveMate"
        const val LISTEN_TIMEOUT_MS = 8_000L
    }
}
