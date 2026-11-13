package com.example.drivemate.report

import com.example.drivemate.data.EventEntity
import com.example.drivemate.data.TripEntity
import com.example.drivemate.model.EventType

/** 연수 로드맵의 단계 (홈 화면에서 이 순서로 길 위에 놓인다) */
enum class Stage(val label: String) {
    LEFT_TURN("좌회전"),
    RIGHT_TURN("우회전"),
    INTERSECTION("교차로"),
    MERGE("고속도로 합류"),
}

/**
 * 홈 화면의 연수 로드맵 값.
 * @param session "초보 연수 N회차"의 N (500m 이상 달린 끝난 주행 수)
 * @param done 끝낸 단계들
 * @param current 지금 연습할 단계 (위 순서에서 아직 안 끝낸 첫 단계). 다 끝냈으면 null
 */
data class Roadmap(val session: Int, val done: Set<Stage>, val current: Stage?)

/**
 * 저장된 주행 기록으로 단계 완료를 판단한다. (2026-11-13 결정. 숫자는 팀 기준값, 테스트 후 조정)
 *
 *  - 좌회전/우회전: 그 회전 기록이 있는 주행이 2번 연속, 그 회전 모두 같은 쪽 깜빡이 기록 있음
 *  - 교차로: 교차로를 지난 주행이 2번 연속, 그 주행에 회전·교차로 앞 급감속 없음
 *  - 고속도로 합류: 합류 지점을 지난 주행 1번
 *
 * "연속"은 그 단계와 관계있는 주행끼리만 센다 (회전이 없던 주행은 건너뛴다).
 * 한 번 끝낸 단계는 그 뒤에 기록이 나빠져도 끝낸 것으로 둔다 (칭찬을 거둬 가지 않는다).
 * 실제 도로 상황은 모르므로 "잘했다"가 아니라 "기록 기준을 채웠다"는 뜻이다.
 */
object RoadmapBuilder {
    const val MIN_TRIP_M = 500        // 이보다 짧은 주행은 회차로 세지 않는다 (리포트 다음 목표 기준과 같음)
    const val TURN_STREAK = 2         // 팀 기준값, 테스트 후 조정
    const val INTERSECTION_STREAK = 2 // 팀 기준값, 테스트 후 조정
    const val MERGE_COUNT = 1         // 팀 기준값, 테스트 후 조정

    /** @param trips 끝난 주행들 (순서 상관없음) @param events 모든 주행의 이벤트 */
    fun build(trips: List<TripEntity>, events: List<EventEntity>): Roadmap {
        val sorted = trips.filter { it.endTime != null }.sortedBy { it.startTime }   // 오래된 것부터
        val byTrip = events.groupBy { it.tripId }

        val done = mutableSetOf<Stage>()
        if (turnDone(sorted, byTrip, "LEFT")) done += Stage.LEFT_TURN
        if (turnDone(sorted, byTrip, "RIGHT")) done += Stage.RIGHT_TURN
        if (intersectionDone(sorted, byTrip)) done += Stage.INTERSECTION
        val merges = sorted.count { t -> byTrip[t.id].orEmpty().any { it.isPassed("MERGE") } }
        if (merges >= MERGE_COUNT) done += Stage.MERGE

        return Roadmap(
            session = sorted.count { it.distanceM >= MIN_TRIP_M },
            done = done,
            current = Stage.entries.firstOrNull { it !in done },
        )
    }

    // 관계있는 주행마다 true(기준 충족)/false를 내고, true가 need번 이어진 적이 있는지 본다
    private fun streakReached(results: List<Boolean>, need: Int): Boolean {
        var streak = 0
        for (ok in results) {
            streak = if (ok) streak + 1 else 0
            if (streak >= need) return true
        }
        return false
    }

    private fun turnDone(trips: List<TripEntity>, byTrip: Map<Long, List<EventEntity>>, direction: String): Boolean {
        val results = trips.mapNotNull { t ->
            val turns = byTrip[t.id].orEmpty().filter { it.type == EventType.TURN.name && it.direction == direction }
            if (turns.isEmpty()) null else turns.all { it.signalUsed == true }
        }
        return streakReached(results, TURN_STREAK)
    }

    private fun intersectionDone(trips: List<TripEntity>, byTrip: Map<Long, List<EventEntity>>): Boolean {
        val results = trips.mapNotNull { t ->
            val ev = byTrip[t.id].orEmpty()
            if (ev.none { it.isPassed("INTERSECTION") }) null
            else ev.none { it.type == EventType.HARD_BRAKE.name && it.nearTurn == true }
        }
        return streakReached(results, INTERSECTION_STREAK)
    }

    private fun EventEntity.isPassed(kind: String) = type == EventType.POINT_PASSED.name && direction == kind
}
