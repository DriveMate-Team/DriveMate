package com.example.drivemate.report

import com.example.drivemate.data.EventEntity
import com.example.drivemate.model.EventType

/** 비교 결과 한 줄의 방향 (화면 색을 고를 때 쓴다: 줄었으면 초록 칭찬, 그 밖에는 사실만) */
enum class Trend { FEWER, SAME, MORE }

/**
 * 이전 주행과 비교한 한 줄. 예: "깜빡이 기록 없음 3 → 1 · 지난번보다 2번 줄었어요"
 */
data class CompareRow(val label: String, val before: Int, val now: Int, val trend: Trend, val text: String)

/**
 * 바로 전 주행과 이번 주행의 기록 횟수를 비교한다. (신청서 3-④ "이전 주행 대비 변화", 4-⑥)
 *
 * 원칙: 줄었으면 칭찬(설계문서의 초록 "지난번보다 줄었어요"), 늘었으면 혼내지 않고 횟수만 말한다.
 * 주행 거리가 달라도 횟수만 비교한다 (1차 단순화. 거리당 비율은 2차에서 검토).
 * 안드로이드와 상관없는 순수한 계산이라 PC에서 단위 테스트로 확인할 수 있다.
 */
object TripComparison {

    private data class Metric(val label: String, val count: (List<EventEntity>) -> Int)

    private val metrics = listOf(
        Metric("깜빡이 기록 없음") { ev -> ev.count { it.type == EventType.TURN.name && it.signalUsed == false } },
        Metric("급감속") { ev -> ev.count { it.type == EventType.HARD_BRAKE.name } },
        Metric("급가속") { ev -> ev.count { it.type == EventType.HARD_ACCEL.name } },
        Metric("불규칙한 속도 변화") { ev -> ev.count { it.type == EventType.IRREGULAR_SPEED.name } },
    )

    /** 두 주행 모두 0번인 항목은 빼고 돌려준다 (보여줄 것이 없으면 빈 목록) */
    fun compare(previous: List<EventEntity>, current: List<EventEntity>): List<CompareRow> =
        metrics.mapNotNull { m ->
            val before = m.count(previous)
            val now = m.count(current)
            if (before == 0 && now == 0) return@mapNotNull null
            val trend = when {
                now < before -> Trend.FEWER
                now > before -> Trend.MORE
                else -> Trend.SAME
            }
            val text = when (trend) {
                Trend.FEWER -> "지난번보다 ${before - now}번 줄었어요"
                Trend.MORE -> "지난번보다 ${now - before}번 많았어요"
                Trend.SAME -> "지난번과 같아요"
            }
            CompareRow(m.label, before, now, trend, text)
        }
}
