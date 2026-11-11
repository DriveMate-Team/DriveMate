package com.example.drivemate.shared

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs

// ====================================================================================
// [2단계] 코칭 기준값 모음 -- 나중에 숫자나 문구를 바꾸고 싶으면 "이 부분만" 고치면 된다.
// ====================================================================================
// 아래 세 값은 "기록 기준"이다: 이 기준을 넘으면 말하든 안 말하든 실수로 기록한다 (졸업 판단에 쓰임).
// "말하는 기준"은 코치 수준마다 다르다 -> CoachLevels.kt (수준 1은 이 값과 같다)
const val SPEED_LIMIT_KMH = 60f          // 이 속도(km/h)를 "넘으면" 과속으로 기록
const val CURVE_MIN_SPEED_KMH = 30f      // 이 속도(km/h) "이상"일 때만 급커브를 판단
const val CURVE_ANGLE_DEG = 90f          // 조향각 절댓값(도)이 이 값을 "넘으면" 급커브
const val COOLDOWN_SECONDS = 30L         // 같은 종류의 기록/안내는 이 시간(초) 안에 다시 하지 않음
val TTS_LOCALE: Locale = Locale.KOREA    // 말하는 언어. 안 정하면 영어(en_US)로 읽어서 한국어 문장이 "무음"이 된다(실제로 겪음)

const val SPEED_MESSAGE = "조금 빨라요. 속도 줄여볼까요?"                       // 과속 안내 문구
const val CURVE_MESSAGE = "커브에서는 속도부터 줄이고, 핸들은 천천히 돌려요."     // 급커브 안내 문구
// ====================================================================================

/**
 * 속도와 조향각을 받아서 "말할지 말지" 판단하고, TTS(음성 안내)로 말해 주는 클래스.
 *
 * 사용법: start() -> onSpeed()/onSteering()에 값을 계속 넘김 -> 끝나면 release()
 *
 * @param onStatus 화면에 보여줄 상태 글자를 받는 함수 (예: "TTS 준비됨", "마지막 안내: ...")
 */
class DriveCoach(
    context: Context,
    private val store: DriveEventStore,   // [3단계] 안내를 말할 때 이벤트를 기록할 저장소
    private val onStatus: (String) -> Unit,
    private val onAlert: (eventType: String) -> Unit = {},   // [화면] 경고(과속/급커브)를 말한 순간 화면이 "주의" 표시를 하도록 알린다
) {
    // 안내 종류. 종류별로 쿨다운을 따로 센다.
    private enum class Kind { SPEED, CURVE }

    // ON_DEVICE = 인터넷/앱 등록 없이 차량 안에서 음성을 만드는 모드
    private val tts = TextToSpeech(context, Mode.ON_DEVICE)

    // 차량 콜백은 여러 스레드에서 올 수 있어서, 모든 판단은 메인 스레드 한 곳에서만 처리한다.
    private val handler = Handler(Looper.getMainLooper())

    private var ready = false                 // TTS 초기화가 끝났는지
    private var speaking = false              // 지금 말하는 중인지 (말이 겹치지 않게 하려고)
    private var released = false              // release()가 불렸는지
    private var listenerAdded = false         // 수신기를 등록했는지 (등록 안 했으면 제거도 하면 안 됨)

    private var speedKmh = 0f                 // 가장 최근 속도 (km/h)
    private var angleDeg = 0f                 // 가장 최근 조향각 (도)
    private var lastMessage = "없음"          // 마지막으로 말한 문구 (화면 표시용)
    private var startedAt = 0L                // onStart가 온 시각 (걸린 시간 계산용)
    private var lastDurationText = ""         // 마지막 발화에 걸린 시간 (화면 표시용)

    // 종류별로 "마지막으로 말한 시각" / "마지막으로 기록한 시각". 없으면 아직 한 번도 안 한 것.
    private val lastSpokenAt = mutableMapOf<Kind, Long>()
    private val lastRecordedAt = mutableMapOf<Kind, Long>()

    // 말하는 중이라는 표시가 안 풀리는 사고를 막는 안전장치(끝 신호가 안 오면 10초 뒤 강제로 풂)
    private val speakingTimeout = Runnable { speaking = false }

    // TTS가 "시작/끝/오류"를 알려주는 수신기
    private val eventListener = object : EventListener {
        override fun onReady() {}
        override fun onStart() {
            handler.post {
                startedAt = SystemClock.elapsedRealtime()   // 시작 시각 기록
                if (!released) report("TTS 말하는 중")
            }
        }
        override fun onUpdatedRms(rms: Double) {}   // 소리 크기 정보. 지금은 안 씀
        override fun onDone() {
            handler.post {
                // 시작~끝 걸린 시간. 너무 짧으면(수십 ms) 실제로 소리를 안 만든 것일 수 있다.
                // onStart 없이 onDone만 오는 경우(startedAt=0)에는 표시하지 않는다 (예전에 "발화 4358654ms" 같은 엉뚱한 값이 찍힘)
                if (startedAt > 0L) lastDurationText = " (발화 ${SystemClock.elapsedRealtime() - startedAt}ms)"
                startedAt = 0L
                finishSpeaking("TTS 준비됨")
            }
        }
        override fun onStop() {
            handler.post { finishSpeaking("TTS 준비됨") }
        }
        override fun onError(errorType: ErrorType) {
            // 오류 이유를 화면에 그대로 보여준다 (에뮬레이터에서 소리가 안 날 때 원인을 알기 위해)
            handler.post { finishSpeaking("TTS 오류: $errorType") }
        }
    }

    // TTS를 준비한다. 준비가 끝나야(onSuccess) 말할 수 있다.
    fun start() {
        active = this   // [6단계] Gleo 답변 수신기가 "지금 켜져 있는 코치"를 찾을 수 있게 등록
        report("TTS 초기화 중...")
        // 주의: 수신기 등록(addEventListener)은 initialize()가 "성공한 뒤"에 해야 한다.
        // 초기화 전에 부르면 SDK 내부가 아직 안 만들어져서 앱이 죽는다 (실제로 겪은 오류).
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                handler.post {
                    if (released) return@post   // 이미 화면이 닫혔다면 아무것도 하지 않음
                    tts.addEventListener(eventListener)   // 초기화가 끝났으니 이제 수신기를 등록
                    tts.setLocale(TTS_LOCALE)             // 말하는 언어를 한국어로 지정 (말하기 전에 해야 함)
                    listenerAdded = true
                    ready = true
                    report("TTS 준비됨")
                }
            }

            override fun onFailure(errorType: ErrorType) {
                handler.post { if (!released) report("TTS 초기화 실패: $errorType") }
            }
        })
    }

    // 화면에 보여줄 글자를 만든다: "TTS 상태 / 마지막 안내"
    private fun report(state: String) {
        onStatus("$state$lastDurationText / 마지막 안내: $lastMessage")
    }

    // 새 속도(km/h)가 왔을 때 호출
    fun onSpeed(kmh: Float) {
        handler.post {
            // 소수점 첫째 자리까지만 사용: 16.666668 m/s x 3.6 = 60.0000038 처럼 아주 작은 오차 때문에
            // 딱 60km/h인데 "60 초과"로 판정되는 일을 막는다 (실제로 겪음)
            speedKmh = Math.round(kmh * 10) / 10f
            store.setSpeed(kmh)   // [3단계] 거리 누적에 쓰도록 저장소에도 속도를 알려준다
            evaluate()
        }
    }

    // 새 조향각(도)이 왔을 때 호출. 부호(왼쪽/오른쪽)는 상관없어서 evaluate에서 절댓값만 쓴다.
    fun onSteering(deg: Float) {
        handler.post {
            angleDeg = deg
            evaluate()
        }
    }

    // 정리: 화면이 닫힐 때 호출
    fun release() {
        if (active === this) active = null   // [6단계] 등록 해제
        released = true
        handler.removeCallbacksAndMessages(null)
        // 문서 권장 순서: 수신기 제거 -> release. 초기화가 안 끝난 채 닫혀도 앱이 죽지 않게 try로 감싼다.
        try {
            if (listenerAdded) tts.removeEventListener(eventListener)
            tts.release()
        } catch (e: Exception) {
            // 정리 중 오류는 무시 (어차피 종료 중)
        }
    }

    // 지금 값으로 "기록할지"와 "말할지"를 따로 판단한다.
    //  - 기록: 기본 기준(60km/h 초과, 30km/h 이상+90도 초과)을 넘으면 항상 기록 (말하지 않는 조용한 코치 수준에서도)
    //  - 말하기: 지금 코치 수준(CoachLevels.kt)의 기준을 넘을 때만 말한다
    private fun evaluate() {
        if (released || !ready) return   // 준비 전에는 아무것도 하지 않음 (말하는 중이어도 기록은 계속한다)

        val rule = LIVE_RULES[store.coachLevel()] ?: LIVE_RULES.getValue(COACH_LEVEL_CHATTY)
        val speed = speedKmh
        val angle = abs(angleDeg)

        // 급커브를 과속보다 먼저 처리한다 (둘 다 해당하면 급커브 안내가 우선)
        if (speed >= CURVE_MIN_SPEED_KMH && angle > CURVE_ANGLE_DEG) {
            val live = speed >= rule.curveMinSpeedKmh && angle > rule.curveAngleDeg
            handle(Kind.CURVE, EVENT_CURVE, CURVE_MESSAGE, live)
        }
        if (speed > SPEED_LIMIT_KMH) {
            val live = speed > rule.speedOverKmh
            handle(Kind.SPEED, EVENT_SPEED, SPEED_MESSAGE, live)
        }
    }

    // 실수 하나를 처리한다: 말할 수 있으면 말하고, 어떤 경우든 (쿨다운이 지났다면) 기록한다.
    private fun handle(kind: Kind, eventType: String, message: String, liveRule: Boolean) {
        // 실제로 말하는 경우: 이 수준의 기준을 넘었고, 다른 말을 하는 중이 아니고, 같은 종류의 쿨다운이 지났을 때
        val willSpeak = liveRule && !speaking && canSpeak(kind)
        record(kind, eventType, willSpeak)
        if (willSpeak) speak(kind, message, eventType)
    }

    // 기록: 같은 종류는 30초에 1건. 이미 기록된 같은 건이 조용했는데 이번에 말하게 되면 "말함"으로 바꾼다 (이중 집계 방지)
    private fun record(kind: Kind, eventType: String, live: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val last = lastRecordedAt[kind]
        if (last == null || now - last >= COOLDOWN_SECONDS * 1000L) {
            lastRecordedAt[kind] = now
            store.addEvent(eventType, speedKmh, angleDeg, live)
        } else if (live) {
            store.markLastEventLive(eventType)
        }
    }

    // 이 종류의 안내를 지금 말해도 되는지(쿨다운이 지났는지)
    private fun canSpeak(kind: Kind): Boolean {
        val last = lastSpokenAt[kind] ?: return true   // 한 번도 안 말했으면 OK
        return SystemClock.elapsedRealtime() - last >= COOLDOWN_SECONDS * 1000L
    }

    // 실제로 말하기. 말하기를 "요청한 시점"부터 쿨다운을 센다. (기록은 handle()에서 이미 했다)
    private fun speak(kind: Kind, text: String, eventType: String) {
        lastSpokenAt[kind] = SystemClock.elapsedRealtime()
        speaking = true
        handler.removeCallbacks(speakingTimeout)
        handler.postDelayed(speakingTimeout, 10_000L)
        lastMessage = text
        onAlert(eventType)   // 화면에 "주의" 표시: 실제로 말한 안내만 (EVENT_SPEED 또는 EVENT_CURVE)
        tts.speak(text)
    }

    // [4단계] 주행 요약 말하기. 경고(과속/급커브)와 달리 쿨다운이 없고, 이벤트로 기록하지도 않는다.
    // 말하는 중이어도 요약이 우선이다(SDK가 앞선 발화를 줄여서 끊고 새 문장을 말함 - 로그로 확인됨).
    fun speakSummary(text: String) {
        handler.post {
            if (released || !ready) return@post
            speaking = true
            handler.removeCallbacks(speakingTimeout)
            handler.postDelayed(speakingTimeout, 15_000L)   // 요약은 길어서 안전장치 시간을 조금 더 길게
            lastMessage = text
            tts.speak(text)
        }
    }

    // [6단계] Gleo 질문에 대한 답 말하기. 준비가 안 됐으면 false를 돌려줘서 호출한 쪽이 다른 방법(일회용 TTS)을 쓰게 한다.
    // (메인 스레드에서만 호출: 수신기 onReceive는 메인 스레드)
    fun speakAnswer(text: String): Boolean {
        if (released || !ready) return false
        speakSummary(text)   // 경고와 달리 쿨다운이 없고 말하는 중이어도 우선하는 요약용 말하기를 그대로 쓴다
        return true
    }

    // 말하기가 끝났을 때(정상 종료/중단/오류) 공통 처리
    private fun finishSpeaking(status: String) {
        speaking = false
        handler.removeCallbacks(speakingTimeout)
        if (!released) report(status)
    }

    companion object {
        // [6단계] 지금 화면이 쓰고 있는 코치 (없으면 null). 다른 스레드에서도 읽으므로 @Volatile
        @Volatile
        var active: DriveCoach? = null
    }
}
