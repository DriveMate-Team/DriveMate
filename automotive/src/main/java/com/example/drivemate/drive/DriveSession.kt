package com.example.drivemate.drive

import android.util.Log
import com.example.drivemate.coaching.AnswerEngine
import com.example.drivemate.coaching.Coaching
import com.example.drivemate.coaching.CoachingRules
import com.example.drivemate.data.DriveRepository
import com.example.drivemate.data.EventEntity
import com.example.drivemate.data.QuestionEntity
import com.example.drivemate.data.TripEntity
import com.example.drivemate.event.DetectedEvent
import com.example.drivemate.event.EventDetector
import com.example.drivemate.model.DriveMode
import com.example.drivemate.model.EventType
import com.example.drivemate.model.Gear
import com.example.drivemate.model.Maneuver
import com.example.drivemate.model.ManeuverType
import com.example.drivemate.model.NaviState
import com.example.drivemate.model.Topic
import com.example.drivemate.model.TurnSignal
import com.example.drivemate.model.VehicleState
import com.example.drivemate.model.toStorage
import com.example.drivemate.model.toTopics
import com.example.drivemate.report.ReportBuilder
import com.example.drivemate.vehicle.LocationSource
import com.example.drivemate.vehicle.NaviSource
import com.example.drivemate.vehicle.VehicleSource
import com.example.drivemate.voice.SpeechInput
import com.example.drivemate.voice.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 마이크 상태 */
enum class ListenState { IDLE, LISTENING, ANSWERING }

/** 주행 중 화면이 그리는 데 필요한 값 묶음 */
data class DriveState(
    val active: Boolean = false,                  // 주행(기록) 중인지
    val mode: DriveMode = DriveMode.BEGINNER,
    val topics: Set<Topic> = emptySet(),
    val hasRoute: Boolean = false,                // 내비 경로가 있는지 (없으면 상황 예고 코칭이 꺼진다)
    val next: Maneuver? = null,                   // 다음 상황 ("좌회전 · 300m")
    val bubble: String = "",                      // 코칭 말풍선 문구
    val listen: ListenState = ListenState.IDLE,
)

/**
 * 주행 한 번을 처음부터 끝까지 맡는 "두뇌".
 * 차량 값·내비 값을 받아서 ① 코칭을 말하고 ② 이벤트를 기록하고 ③ 질문에 답하고 ④ 끝나면 리포트를 준비한다.
 *
 * 화면과 분리되어 있다: 화면(ViewModel)은 state를 읽어서 그리기만 하고, 버튼이 눌리면 여기 함수를 부른다.
 * 모든 처리는 메인 스레드 한 곳에서 일어난다 (값이 꼬이지 않게).
 *
 * 안전 원칙 (설계문서 개요):
 *  - 내비 안내가 우선: 내비가 말하는 동안에는 기다린다
 *  - 주행 중에는 짧은 음성만
 *  - 실제 신호·주변 차량을 모르므로 "지금 가세요" 같은 지시는 하지 않는다 (문구는 CoachingRules·AnswerEngine에 고정)
 *  - 조작 기록과 운전 평가는 구분: 주행 중에는 기록만 하고 지적하지 않는다
 */
@Singleton
class DriveSession @Inject constructor(
    private val vehicle: VehicleSource,
    private val navi: NaviSource,
    private val location: LocationSource,
    private val speechOut: SpeechOutput,
    private val speechIn: SpeechInput,
    private val answerEngine: AnswerEngine,
    private val repo: DriveRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DriveState())
    val state: StateFlow<DriveState> = _state

    // 주행이 끝났다는 신호. 값 = 리포트를 보여줄 주행 번호 (기록할 것이 없어 버린 주행이면 null)
    private val _finished = MutableSharedFlow<Long?>(extraBufferCapacity = 4)
    val finished: SharedFlow<Long?> = _finished

    private val rules = CoachingRules()
    private val detector = EventDetector()

    private var tripId: Long? = null
    private var starting = false              // 시작 처리 중(DB에 쓰는 짧은 순간)인지
    private var ticker: Job? = null
    private var distanceM = 0.0               // 이번 주행에서 달린 거리
    private var leftPark = false              // 이번 주행에서 기어가 P를 벗어난 적이 있는지
    private var signalLatch = false           // 지난 1초 사이 방향지시등이 켜진 적이 있는지
    private var suppressAutoStart = false     // 직접 "주행 끝내기"를 누른 뒤, 차가 멈출 때까지 자동 시작을 막는다

    // 말하려고 기다리는 코칭 (내비가 말하는 중이면 잠시 기다렸다가 말한다)
    private var pending: Coaching? = null
    private var pendingManeuverId = 0
    private var bubbleManeuverId: Int? = null // 지금 말풍선이 어느 회전에 대한 것인지

    init {
        scope.launch { repo.closeUnfinishedTrips() }
        scope.launch { vehicle.state.collect { onVehicle(it) } }
        scope.launch { navi.state.collect { onNavi(it) } }
    }

    // =====================================================================
    // 화면에서 부르는 함수
    // =====================================================================

    /** 주행 시작 ("출발하기" 버튼, 또는 차가 움직여서 자동 시작) */
    fun start(mode: DriveMode, topics: Set<Topic>) {
        if (_state.value.active || starting) return
        starting = true
        scope.launch {
            val naviNow = navi.state.value
            // 고른 모드·주제를 기억해 두었다가 다음 주행 준비 화면에 미리 채워 준다
            repo.saveProfile(repo.getProfile().copy(mode = mode.name, topics = topics.toStorage()))
            tripId = repo.startTrip(
                TripEntity(
                    startTime = System.currentTimeMillis(),
                    destination = naviNow.destination.takeIf { naviNow.hasRoute },
                    mode = mode.name,
                    topics = topics.toStorage(),
                )
            )
            rules.reset()
            detector.reset()
            distanceM = 0.0
            signalLatch = false
            pending = null
            bubbleManeuverId = null
            leftPark = vehicle.state.value.gear.let { it != Gear.P && it != Gear.UNKNOWN }
            navi.onTripStart()
            location.start()
            _state.value = DriveState(
                active = true, mode = mode, topics = topics,
                hasRoute = naviNow.hasRoute, next = naviNow.next.takeIf { naviNow.hasRoute },
                bubble = defaultBubble(naviNow.hasRoute),
            )
            starting = false
            Log.i(TAG, "TRIP START id=$tripId mode=$mode topics=$topics route=${naviNow.hasRoute}")
            startTicker()
        }
    }

    /** 주행 끝 ("주행 끝내기"를 길게 누름, 또는 기어 P로 자동 종료) */
    fun end(manual: Boolean) {
        val id = tripId ?: return
        if (!_state.value.active) return
        tripId = null
        ticker?.cancel()
        speechIn.cancel()
        speechOut.stop()
        location.stop()
        if (manual) suppressAutoStart = true
        _state.value = DriveState()
        scope.launch {
            val trip = repo.getTrip(id) ?: return@launch
            val events = repo.getEvents(id)
            val questions = repo.getQuestions(id)
            if (distanceM < MIN_TRIP_M && events.isEmpty() && questions.isEmpty()) {
                // 움직이지도 않았고 기록도 없는 주행은 남기지 않는다
                repo.deleteTrip(id)
                Log.i(TAG, "TRIP DISCARDED id=$id")
                _finished.emit(null)
                return@launch
            }
            repo.updateTrip(trip.copy(endTime = System.currentTimeMillis(), distanceM = distanceM.toInt()))
            // 리포트의 "다음 목표 1개"를 저장해 두었다가 다음 주행의 홈 화면에 보여준다
            val report = ReportBuilder.build(events, questions)
            // 단, 아주 짧고 기록도 없는 주행은 지난 목표를 그대로 둔다 (300m 달리고 "코칭을 줄여 보자"는 섣부르다)
            val tooShortToJudge = distanceM < MIN_GOAL_TRIP_M && events.isEmpty() && questions.isEmpty()
            if (!tooShortToJudge) {
                repo.saveProfile(
                    repo.getProfile().copy(goalTitle = report.goal.title, goalDetail = "지난 주행 · ${report.goal.detail}")
                )
            }
            Log.i(TAG, "TRIP END id=$id distance=${distanceM.toInt()}m events=${events.size} questions=${questions.size} goal=${report.goal.title}")
            speechOut.speak("수고하셨어요. 오늘의 주행 리포트를 준비했어요.")
            _finished.emit(id)
        }
    }

    /** 마이크 버튼: 듣기 시작 (듣는 중에 다시 누르면 취소) */
    fun onMicPressed() {
        if (!_state.value.active) return
        when (_state.value.listen) {
            ListenState.LISTENING -> {
                speechIn.cancel()
                _state.update { it.copy(listen = ListenState.IDLE, bubble = defaultBubble(it.hasRoute)) }
            }
            ListenState.ANSWERING -> Unit   // 답하는 중에는 무시
            ListenState.IDLE -> {
                speechOut.stop()   // 코칭을 말하던 중이면 멈추고 듣는다
                // 화면이 바로 반응하도록 먼저 상태를 바꾼다 (Pleos 가이드: 입력 후 0.25초 안에 응답 표시)
                _state.update { it.copy(listen = ListenState.LISTENING, bubble = "듣고 있어요. 말씀해 주세요.") }
                bubbleManeuverId = null
                speechIn.listen { text ->
                    if (text == null) {
                        _state.update { it.copy(listen = ListenState.IDLE, bubble = "잘 못 들었어요. 마이크를 다시 눌러 주세요.") }
                        speechOut.speak("잘 못 들었어요.")
                    } else {
                        onQuestionText(text)
                    }
                }
            }
        }
    }

    /** 인식된 질문 문장을 처리한다 (마이크로 들은 문장, 또는 테스트에서 직접 넣은 문장) */
    fun onQuestionText(text: String) {
        val id = tripId ?: return
        val now = System.currentTimeMillis()
        val reply: String
        val bubble: String

        if ("어려웠" in text || "어려워" in text) {
            // "어려웠어" -> 지금 위치를 어려웠던 구간으로 표시한다 (설계문서 5단계)
            saveEvent(id, EventType.DIFFICULT, vehicle.state.value.speedKmh, difficult = true)
            reply = "여기를 어려웠던 구간으로 표시했어요."
            bubble = reply
        } else {
            val answer = answerEngine.answer(text, _state.value.topics)
            scope.launch {
                repo.addQuestion(QuestionEntity(tripId = id, time = now, question = text, answer = answer.text, topic = answer.topic))
            }
            reply = answer.text
            // 주행 중 화면에는 긴 답을 글자로 띄우지 않는다 (답은 음성으로). 짧은 답은 그대로 보여준다.
            bubble = if (answer.topic != null) "${answer.topic} 질문에 답하고 있어요." else answer.text
        }
        Log.i(TAG, "QUESTION: $text -> $reply")
        bubbleManeuverId = null
        _state.update { it.copy(listen = ListenState.ANSWERING, bubble = bubble) }
        val spoke = speechOut.speak(reply) {
            _state.update { if (it.active) it.copy(listen = ListenState.IDLE) else it }
        }
        if (!spoke) {
            // 음성을 낼 수 없으면(준비 안 됨 등) 답을 글자로라도 보여준다
            _state.update { it.copy(listen = ListenState.IDLE, bubble = reply) }
        }
    }

    // =====================================================================
    // 차량·내비 값이 들어올 때
    // =====================================================================

    private fun onVehicle(v: VehicleState) {
        if (v.turnSignal != TurnSignal.OFF) signalLatch = true

        if (_state.value.active) {
            if (v.gear != Gear.P && v.gear != Gear.UNKNOWN) leftPark = true
            // 주행 끝 = 달리다가 기어를 P에 넣었을 때
            if (v.gear == Gear.P && leftPark) end(manual = false)
            return
        }

        // 주행 중이 아닐 때: 차가 움직이기 시작하면 지난번 설정으로 자동 시작한다
        // (설계문서 3단계: 차가 움직이면 홈·준비·리포트 화면은 자동으로 주행 중 화면으로 바뀐다)
        if (suppressAutoStart && (v.gear == Gear.P || v.speedKmh < 1f)) suppressAutoStart = false
        if (!suppressAutoStart && !starting && v.gear != Gear.P && v.speedKmh >= AUTO_START_KMH) {
            scope.launch {
                val profile = repo.getProfile()
                val mode = DriveMode.entries.find { it.name == profile.mode } ?: DriveMode.BEGINNER
                start(mode, profile.topics.toTopics())
            }
        }
    }

    private fun onNavi(n: NaviState) {
        if (!_state.value.active) return
        val next = n.next.takeIf { n.hasRoute }
        _state.update { it.copy(hasRoute = n.hasRoute, next = next) }

        // 코칭 규칙표에 물어본다: 지금 말할 것이 있는가?
        val coaching = rules.onNavi(next, _state.value.mode, _state.value.topics, System.currentTimeMillis())
        if (coaching != null && next != null) {
            pending = coaching
            pendingManeuverId = next.id
        }
        trySpeakPending()

        // 말풍선의 회전을 지나갔으면 기본 문구로 되돌린다
        if (bubbleManeuverId != null && next?.id != bubbleManeuverId && _state.value.listen == ListenState.IDLE) {
            bubbleManeuverId = null
            _state.update { it.copy(bubble = defaultBubble(it.hasRoute)) }
        }
    }

    // 기다리던 코칭을 말할 수 있으면 말한다
    private fun trySpeakPending() {
        val coaching = pending ?: return
        // 기다리는 사이 그 회전을 지나갔거나 너무 가까워졌으면 포기한다 (늦은 안내는 방해가 된다)
        val next = navi.state.value.next
        if (next?.id != pendingManeuverId || next.distanceM < PENDING_GIVE_UP_M) {
            pending = null
            return
        }
        if (_state.value.listen != ListenState.IDLE) return   // 질문을 듣거나 답하는 중
        if (speechOut.isNaviSpeaking()) return                // 내비 안내가 끝난 뒤에 말한다
        if (speechOut.speak(coaching.speech)) {
            pending = null
            bubbleManeuverId = pendingManeuverId
            _state.update { it.copy(bubble = coaching.bubble) }
        }
    }

    // =====================================================================
    // 1초마다: 거리 누적 + 이벤트 판단
    // =====================================================================

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var last = System.currentTimeMillis()
            var count = 0
            while (true) {
                delay(1000)
                val id = tripId ?: break
                val now = System.currentTimeMillis()
                val v = vehicle.state.value
                val n = navi.state.value

                // 거리 = 속도(m/s) × 시간(초)
                distanceM += v.speedKmh / 3.6 * (now - last) / 1000.0
                last = now

                val signalOn = signalLatch || v.turnSignal != TurnSignal.OFF
                signalLatch = false
                detector.onSample(v.speedKmh, n.next.takeIf { n.hasRoute }, signalOn, now).forEach { event ->
                    when (event) {
                        is DetectedEvent.HardBrake ->
                            saveEvent(id, EventType.HARD_BRAKE, event.speedKmh, nearTurn = event.nearTurn)
                        is DetectedEvent.HardAccel ->
                            saveEvent(id, EventType.HARD_ACCEL, event.speedKmh, fromStop = event.fromStop)
                        is DetectedEvent.TurnPassed ->
                            saveEvent(
                                id, EventType.TURN, event.speedKmh,
                                direction = if (event.type == ManeuverType.LEFT) "LEFT" else "RIGHT",
                                signalUsed = event.signalUsed,
                            )
                    }
                }
                trySpeakPending()

                // 10초마다 거리를 저장해 둔다 (앱이 갑자기 꺼져도 기록이 남도록)
                if (++count % 10 == 0) {
                    repo.getTrip(id)?.let { repo.updateTrip(it.copy(distanceM = distanceM.toInt())) }
                }
            }
        }
    }

    // 이벤트 한 건을 저장한다. 주행 중에는 "기록만" 하고 아무 말도 하지 않는다.
    private fun saveEvent(
        id: Long, type: EventType, speedKmh: Float,
        direction: String? = null, signalUsed: Boolean? = null,
        nearTurn: Boolean? = null, fromStop: Boolean? = null, difficult: Boolean = false,
    ) {
        val v = vehicle.state.value
        val place = location.current()
        val event = EventEntity(
            tripId = id, time = System.currentTimeMillis(),
            latitude = place?.first, longitude = place?.second,
            type = type.name, speedKmh = speedKmh,
            turnSignal = v.turnSignal.name, brake = v.brake,
            direction = direction, signalUsed = signalUsed, nearTurn = nearTurn, fromStop = fromStop,
            difficult = difficult,
        )
        Log.i(TAG, "EVENT: ${type.name} speed=$speedKmh direction=$direction signalUsed=$signalUsed nearTurn=$nearTurn fromStop=$fromStop")
        scope.launch { repo.addEvent(event) }
    }

    private fun defaultBubble(hasRoute: Boolean) =
        if (hasRoute) "옆에서 같이 가고 있어요." else "경로가 없어서 기록과 질문만 도와드려요."

    private companion object {
        const val TAG = "DriveMate"
        const val AUTO_START_KMH = 5f        // 이 속도 이상으로 움직이면 자동으로 주행 시작
        const val MIN_TRIP_M = 30            // 이보다 짧고 기록도 없는 주행은 남기지 않는다
        const val MIN_GOAL_TRIP_M = 500      // 이보다 짧고 기록도 없는 주행은 "다음 목표"를 바꾸지 않는다
        const val PENDING_GIVE_UP_M = 120    // 내비 안내·질문 답변이 끝나기를 기다리다 이 거리 안으로 들어오면 코칭을 포기
    }
}
