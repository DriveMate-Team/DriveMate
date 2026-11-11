package com.example.drivemate.event

import com.example.drivemate.model.Maneuver
import com.example.drivemate.model.ManeuverType

/** EventDetector가 찾아낸 이벤트 */
sealed interface DetectedEvent {
    /** 급감속. nearTurn = 회전·교차로 100m 안에서 생겼는지 */
    data class HardBrake(val speedKmh: Float, val nearTurn: Boolean) : DetectedEvent

    /** 급가속. fromStop = 정차했다가 출발하는 중에 생겼는지 */
    data class HardAccel(val speedKmh: Float, val fromStop: Boolean) : DetectedEvent

    /** 회전 구간을 지나감. signalUsed = 회전 지점 앞 100m 안에서 방향지시등이 켜진 기록이 있는지 */
    data class TurnPassed(val type: ManeuverType, val signalUsed: Boolean, val speedKmh: Float) : DetectedEvent
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

    // 지금 다가가고 있는 회전을 추적하는 값
    private var trackedId: Int? = null
    private var trackedType = ManeuverType.OTHER
    private var cameWithinRange = false   // 100m 안까지 들어왔는지
    private var signalSeen = false        // 100m 안에서 방향지시등이 켜진 적이 있는지

    /**
     * 약 1초마다 부른다.
     *
     * @param signalOn 지난 호출 이후로 방향지시등이 한 번이라도 켜졌는지 (좌/우 구분 없이 "작동 여부"만 본다)
     * @return 이번에 새로 생긴 이벤트들 (대부분은 빈 목록)
     */
    fun onSample(speedKmh: Float, next: Maneuver?, signalOn: Boolean, nowMs: Long): List<DetectedEvent> {
        val events = mutableListOf<DetectedEvent>()

        // ---------- 1) 회전 구간 통과 ----------
        if (next?.id != trackedId) {
            // 추적하던 회전이 사라졌거나 다음 회전으로 바뀜 = 그 회전을 지나갔다
            if (trackedId != null && cameWithinRange && trackedType.isTurn()) {
                events += DetectedEvent.TurnPassed(trackedType, signalSeen, speedKmh)
            }
            trackedId = next?.id
            trackedType = next?.type ?: ManeuverType.OTHER
            cameWithinRange = false
            signalSeen = false
        }
        // 회전·교차로 100m 안인지 (톨게이트·터널 같은 그 밖의 안내는 세지 않는다)
        val nearManeuver = next != null && next.type != ManeuverType.OTHER && next.distanceM <= NEAR_TURN_M
        if (nearManeuver) {
            cameWithinRange = true
            if (signalOn) signalSeen = true
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
        trackedId = null
        cameWithinRange = false
        signalSeen = false
    }

    private fun ManeuverType.isTurn() = this == ManeuverType.LEFT || this == ManeuverType.RIGHT

    companion object {
        const val HARD_ACCEL_KMH_PER_S = 11f     // eTAS 급가속 기준
        const val HARD_BRAKE_KMH_PER_S = 7.5f    // eTAS 급감속 기준
        const val NEAR_TURN_M = 100              // "회전 지점 앞 100m" (팀 기준값)
        const val STOPPED_KMH = 3f               // 이보다 느리면 멈춘 것으로 본다
        const val FROM_STOP_WINDOW_MS = 5_000L   // 멈춘 뒤 5초 안의 급가속은 "출발할 때"로 본다
    }
}
