package com.example.drivemate.coaching

import com.example.drivemate.model.DriveMode
import com.example.drivemate.model.Maneuver
import com.example.drivemate.model.ManeuverType
import com.example.drivemate.model.Topic

/**
 * 코칭 한 건.
 * @param speech 음성으로 읽는 문장 (설계문서 4단계 표의 문구 그대로)
 * @param bubble 화면 말풍선에 보여줄 짧은 문장 (주행 중에는 3줄을 넘지 않게 줄인 것)
 */
data class Coaching(val key: String, val speech: String, val bubble: String)

/**
 * 설계문서 4단계 "코칭 규칙표"를 그대로 옮긴 곳. 앱은 이 표대로만 말한다.
 *
 * 안드로이드와 상관없는 순수한 계산이라 PC에서 단위 테스트로 확인할 수 있다.
 * 안내 거리(300m, 200m)는 출처가 없는 팀 기준값이라 맨 위 상수로 빼 두었다.
 */
class CoachingRules {

    private val announced = mutableSetOf<Int>()            // 이미 안내한 상황 번호 (같은 회전을 두 번 말하지 않기)
    private val lastSpokenAt = mutableMapOf<String, Long>() // 종류별 마지막 안내 시각 (짧은 시간에 반복하지 않기)

    /**
     * 내비의 "다음 상황"이 새로 들어올 때마다 부른다. 지금 말할 코칭이 있으면 돌려주고, 없으면 null.
     *
     * @param nowMs 지금 시각(ms). 테스트에서 시간을 마음대로 넣을 수 있게 밖에서 받는다
     */
    fun onNavi(next: Maneuver?, mode: DriveMode, topics: Set<Topic>, nowMs: Long): Coaching? {
        if (next == null) return null
        if (next.id in announced) return null                       // 이 회전은 이미 안내함
        if (next.distanceM > announceDistance(next.type)) return null   // 아직 멀다
        if (next.distanceM < TOO_LATE_M) return null                // 너무 가까우면 말하지 않는다 (회전 중에 말 걸지 않기)
        if (!isWanted(next.type, mode, topics)) return null

        val coaching = messageFor(next.type) ?: return null
        val last = lastSpokenAt[coaching.key]
        if (last != null && nowMs - last < REPEAT_COOLDOWN_MS) {    // 같은 안내를 방금 했으면 이번 회전은 조용히 넘어간다
            announced.add(next.id)
            return null
        }
        announced.add(next.id)
        lastSpokenAt[coaching.key] = nowMs
        return coaching
    }

    /** 새 주행을 시작할 때 기억을 지운다 */
    fun reset() {
        announced.clear()
        lastSpokenAt.clear()
    }

    // 이 상황을 안내할지: 초보 모드는 좌·우회전을 항상, 일반 모드는 고른 연습 주제만. 교차로는 주제로 골랐을 때만.
    private fun isWanted(type: ManeuverType, mode: DriveMode, topics: Set<Topic>): Boolean = when (type) {
        ManeuverType.LEFT -> mode == DriveMode.BEGINNER || Topic.LEFT_TURN in topics
        ManeuverType.RIGHT -> mode == DriveMode.BEGINNER || Topic.RIGHT_TURN in topics
        ManeuverType.INTERSECTION -> Topic.INTERSECTION in topics
        ManeuverType.OTHER -> false
    }

    companion object {
        const val TURN_ANNOUNCE_M = 300          // 좌회전·우회전: 300m 전에 안내
        const val INTERSECTION_ANNOUNCE_M = 200  // 교차로: 200m 전에 안내
        const val TOO_LATE_M = 50                // 이보다 가까우면 안내하지 않음
        const val REPEAT_COOLDOWN_MS = 30_000L   // 같은 안내는 30초 안에 반복하지 않음

        fun announceDistance(type: ManeuverType): Int =
            if (type == ManeuverType.INTERSECTION) INTERSECTION_ANNOUNCE_M else TURN_ANNOUNCE_M

        /** 상황별 문구 (음성 문구는 참가신청서 예시 그대로) */
        fun messageFor(type: ManeuverType): Coaching? = when (type) {
            ManeuverType.LEFT -> Coaching(
                key = "LEFT",
                speech = "곧 좌회전 구간이에요. 진행할 차로와 신호를 미리 확인해 주세요.",
                bubble = "곧 좌회전이에요. 차로와 신호를 확인해 주세요.",
            )
            ManeuverType.RIGHT -> Coaching(
                key = "RIGHT",
                speech = "곧 우회전이에요. 오른쪽 깜빡이를 켜고, 횡단보도의 보행자를 확인해 주세요.",
                bubble = "곧 우회전이에요. 깜빡이를 켜고 보행자를 확인해 주세요.",
            )
            ManeuverType.INTERSECTION -> Coaching(
                key = "INTERSECTION",
                speech = "교차로가 나와요. 신호가 바뀔 수 있으니 속도를 조금 줄여 주세요.",
                bubble = "교차로가 나와요. 속도를 조금 줄여 주세요.",
            )
            ManeuverType.OTHER -> null
        }
    }
}
