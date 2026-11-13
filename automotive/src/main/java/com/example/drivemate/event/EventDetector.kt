package com.example.drivemate.event

import com.example.drivemate.model.Maneuver
import com.example.drivemate.model.ManeuverType
import com.example.drivemate.model.TurnSignal

/** EventDetector가 찾아낸 이벤트 */
sealed interface DetectedEvent {
    /** 급감속. nearTurn = 회전·교차로 100m 안에서 생겼는지 */
    data class HardBrake(val speedKmh: Float, val nearTurn: Boolean) : DetectedEvent

    /** 급가속. fromStop = 정차했다가 출발하는 중에 생겼는지 */
    data class HardAccel(val speedKmh: Float, val fromStop: Boolean) : DetectedEvent

    /** 불규칙한 속도 변화 (팀 기준값: 10초 안에 시속 5km 이상 변화가 방향을 바꿔 가며 3번 이상) */
    data class IrregularSpeed(val speedKmh: Float) : DetectedEvent

    /**
     * 회전 구간을 지나감.
     * @param signalUsed 회전 지점 앞 100m 안에서 "회전 방향과 같은 쪽" 방향지시등이 켜진 기록이 있는지
     * @param signalsSeen 100m 안에서 켜진 방향지시등 쪽 (비어 있으면 기록 없음, 반대쪽만 있으면 반대쪽 기록)
     */
    /**
     * 교차로나 고속도로 합류 지점을 지나감 (홈의 연수 로드맵 단계 판단에 쓴다).
     * 회전과 달리 깜빡이는 보지 않는다.
     */
    data class PointPassed(val type: ManeuverType, val speedKmh: Float) : DetectedEvent

    data class TurnPassed(
        val type: ManeuverType,
        val signalUsed: Boolean,
        val speedKmh: Float,
        val signalsSeen: Set<TurnSignal> = emptySet(),
    ) : DetectedEvent
}

/**
 * 1초마다 속도를 받아 급가속·급감속·회전 구간 통과를 판단한다. (설계문서 4단계 표)
 *
 * 급가속·급감속 기준은 한국교통안전공단 운행기록분석시스템(eTAS)의 위험운전행동 정의:
 *  - 급가속: 1초에 시속 11km 이상 증가
 *  - 급감속: 1초에 시속 7.5km 이상 감소
 *
 * "기록"만 하는 곳이다. 잘했다/못했다는 판단하지 않는다.
 * 안드로이드와 상관없는 순수한 계산이라 PC에서 단위 테스트로 확인할 수 있다.
 */
class EventDetector {

    private var prevSpeed: Float? = null
    private var prevTimeMs = 0L
    private var lastStoppedAtMs = Long.MIN_VALUE / 2   // 마지막으로 멈춰 있던 시각
    private var inBrakeEpisode = false                 // 급감속이 이어지는 동안에는 한 번만 기록하려고 둔 표시
    private var inAccelEpisode = false

    // 불규칙한 속도 변화 판단용: 최근 10초 동안 "시속 5km 이상 변한 순간"의 (시각, 방향 +1/-1)
    private val bigChanges = ArrayDeque<Pair<Long, Int>>()

    // 지금 다가가고 있는 회전을 추적하는 값
    private var trackedId: Int? = null
    private var trackedType = ManeuverType.OTHER
    private var cameWithinRange = false   // 100m 안까지 들어왔는지
    private val signalsSeen = mutableSetOf<TurnSignal>()   // 100m 안에서 켜진 방향지시등 쪽 (왼쪽/오른쪽)

    /**
     * 약 1초마다 부른다.
     *
     * @param signals 지난 호출 이후로 켜졌던 방향지시등 쪽 (예: {LEFT}). OFF는 넣지 않는다
     * @return 이번에 새로 생긴 이벤트들 (대부분은 빈 목록)
     */
    fun onSample(speedKmh: Float, next: Maneuver?, signals: Set<TurnSignal>, nowMs: Long): List<DetectedEvent> {
        val events = mutableListOf<DetectedEvent>()

        // ---------- 1) 회전 구간 통과 ----------
        if (next?.id != trackedId) {
            // 추적하던 회전이 사라졌거나 다음 회전으로 바뀜 = 그 회전을 지나갔다
            if (trackedId != null && cameWithinRange && trackedType.isTurn()) {
                // 회전 방향과 같은 쪽 깜빡이를 켰을 때만 "작동 기록 있음"으로 본다 (2026-11-13: 좌우 구분 추가)
                val correct = if (trackedType == ManeuverType.LEFT) TurnSignal.LEFT else TurnSignal.RIGHT
                events += DetectedEvent.TurnPassed(trackedType, correct in signalsSeen, speedKmh, signalsSeen.toSet())
            }
            // 교차로·합류 지점도 100m 안까지 들어왔다가 바뀌면 "지나감"으로 기록한다
            if (trackedId != null && cameWithinRange &&
                (trackedType == ManeuverType.INTERSECTION || trackedType == ManeuverType.MERGE)
            ) {
                events += DetectedEvent.PointPassed(trackedType, speedKmh)
            }
            trackedId = next?.id
            trackedType = next?.type ?: ManeuverType.OTHER
            cameWithinRange = false
            signalsSeen.clear()
        }
        // 회전·교차로 100m 안인지 (톨게이트·터널 같은 그 밖의 안내는 세지 않는다)
        val nearManeuver = next != null && next.type != ManeuverType.OTHER && next.distanceM <= NEAR_TURN_M
        if (nearManeuver) {
            cameWithinRange = true
            signalsSeen += signals - TurnSignal.OFF
        }

        // ---------- 2) 급가속·급감속 ----------
        if (speedKmh < STOPPED_KMH) lastStoppedAtMs = nowMs
        val prev = prevSpeed
        if (prev != null) {
            val seconds = (nowMs - prevTimeMs) / 1000f
            if (seconds in 0.5f..2.5f) {   // 간격이 너무 짧거나 길면(앱이 잠깐 멈춤 등) 믿지 않는다
                val changePerSecond = (speedKmh - prev) / seconds

                if (changePerSecond <= -HARD_BRAKE_KMH_PER_S) {
                    if (!inBrakeEpisode) events += DetectedEvent.HardBrake(prev, nearTurn = nearManeuver)
                    inBrakeEpisode = true
                } else {
                    inBrakeEpisode = false
                }

                if (changePerSecond >= HARD_ACCEL_KMH_PER_S) {
                    val fromStop = nowMs - lastStoppedAtMs <= FROM_STOP_WINDOW_MS
                    if (!inAccelEpisode) events += DetectedEvent.HardAccel(speedKmh, fromStop)
                    inAccelEpisode = true
                } else {
                    inAccelEpisode = false
                }

                // ---------- 3) 불규칙한 속도 변화 (D항목, 팀 기준값) ----------
                // 1초 사이 시속 5km 이상 변하면 그 방향(가속 +1 / 감속 -1)을 기억한다.
                // 최근 10초 안에서 방향이 바뀌며 이어진 변화가 3번 이상이면 (예: 가속-감속-가속) 한 번 기록한다.
                val delta = speedKmh - prev
                if (kotlin.math.abs(delta) >= IRREGULAR_STEP_KMH) {
                    val dir = if (delta > 0) 1 else -1
                    // 같은 방향이 이어지면(계속 가속 중) 새로 세지 않고 마지막 시각만 갱신
                    if (bigChanges.lastOrNull()?.second == dir) bigChanges.removeLast()
                    bigChanges.addLast(nowMs to dir)
                }
                while (bigChanges.isNotEmpty() && nowMs - bigChanges.first().first > IRREGULAR_WINDOW_MS) bigChanges.removeFirst()
                if (bigChanges.size >= IRREGULAR_MIN_CHANGES) {
                    events += DetectedEvent.IrregularSpeed(speedKmh)
                    bigChanges.clear()   // 한 번 기록하면 처음부터 다시 센다 (같은 구간을 여러 번 세지 않게)
                }
            }
        }
        prevSpeed = speedKmh
        prevTimeMs = nowMs
        return events
    }

    /** 새 주행을 시작할 때 기억을 지운다 */
    fun reset() {
        prevSpeed = null
        inBrakeEpisode = false
        inAccelEpisode = false
        bigChanges.clear()
        trackedId = null
        cameWithinRange = false
        signalsSeen.clear()
    }

    private fun ManeuverType.isTurn() = this == ManeuverType.LEFT || this == ManeuverType.RIGHT

    companion object {
        const val HARD_ACCEL_KMH_PER_S = 11f     // eTAS 급가속 기준
        const val HARD_BRAKE_KMH_PER_S = 7.5f    // eTAS 급감속 기준
        const val NEAR_TURN_M = 100              // "회전 지점 앞 100m" (팀 기준값)
        const val STOPPED_KMH = 3f               // 이보다 느리면 멈춘 것으로 본다
        const val FROM_STOP_WINDOW_MS = 5_000L   // 멈춘 뒤 5초 안의 급가속은 "출발할 때"로 본다

        // 불규칙한 속도 변화: 공인 기준을 찾지 못해 정한 "팀 기준값, 테스트 후 조정" (2026-11-13)
        const val IRREGULAR_STEP_KMH = 5f        // 1초 사이 이만큼 이상 변하면 "큰 변화"
        const val IRREGULAR_WINDOW_MS = 10_000L  // 이 시간 안에서
        const val IRREGULAR_MIN_CHANGES = 3      // 방향이 바뀌며 이어진 큰 변화가 이 횟수 이상이면 기록
    }
}
