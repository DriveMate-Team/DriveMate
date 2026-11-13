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

    // 이미 안내한 회전 지점 번호. "같은 안내를 반복하지 않기"는 같은 회전 지점에만 적용한다.
    // (2026-11-12 결정: 예전에는 "좌회전 안내"라는 종류 단위로 30초 동안 막아서, 25초 뒤의 다른 좌회전을 놓쳤다)
    private val announced = mutableSetOf<Int>()

    /**
     * 내비의 "다음 상황"이 새로 들어올 때마다 부른다. 지금 말할 코칭이 있으면 돌려주고, 없으면 null.
     *
     * @param nowMs 지금 시각(ms). 지금 규칙에서는 쓰지 않지만, 시간 기준 규칙을 다시 넣을 때를 위해 남겨 둔다
     */
    @Suppress("UNUSED_PARAMETER")
    fun onNavi(next: Maneuver?, mode: DriveMode, topics: Set<Topic>, nowMs: Long): Coaching? {
        if (next == null) return null
        if (next.id in announced) return null                       // 이 회전 지점은 이미 안내함
        if (next.distanceM > announceDistance(next.type)) return null   // 아직 멀다
        if (next.distanceM < TOO_LATE_M) return null                // 너무 가까우면 말하지 않는다 (회전 중에 말 걸지 않기)
        if (!isWanted(next.type, mode, topics)) return null

        val coaching = messageFor(next.type, mode) ?: return null
        announced.add(next.id)
        return coaching
    }

    /** 이 회전 지점을 아직 멀어서 나중에 안내할 예정인지 (코칭 대기 말풍선 문구에 쓴다) */
    fun willAnnounce(next: Maneuver, mode: DriveMode, topics: Set<Topic>): Boolean =
        next.id !in announced && next.distanceM > announceDistance(next.type) &&
            isWanted(next.type, mode, topics) && messageFor(next.type, mode) != null

    /** 새 주행을 시작할 때 기억을 지운다 */
    fun reset() {
        announced.clear()
    }

    // 이 상황을 안내할지: 초보 모드는 좌·우회전을 항상, 일반 모드는 고른 연습 주제만. 교차로는 주제로 골랐을 때만.
    private fun isWanted(type: ManeuverType, mode: DriveMode, topics: Set<Topic>): Boolean = when (type) {
        ManeuverType.LEFT -> mode == DriveMode.BEGINNER || Topic.LEFT_TURN in topics
        ManeuverType.RIGHT -> mode == DriveMode.BEGINNER || Topic.RIGHT_TURN in topics
        ManeuverType.INTERSECTION -> Topic.INTERSECTION in topics
        ManeuverType.MERGE -> true   // 고속도로 진입·합류는 두 모드 모두 안내 (속도 차이가 커서 놓치면 위험)
        ManeuverType.OTHER -> false
    }

    companion object {
        const val TURN_ANNOUNCE_M = 300          // 좌회전·우회전: 300m 전에 안내
        const val INTERSECTION_ANNOUNCE_M = 200  // 교차로: 200m 전에 안내
        const val MERGE_ANNOUNCE_M = 500         // 고속도로 진입·합류: 500m 전에 안내 (팀 기준값, 테스트 후 조정)
        const val TOO_LATE_M = 50                // 이보다 가까우면 안내하지 않음

        fun announceDistance(type: ManeuverType): Int = when (type) {
            ManeuverType.INTERSECTION -> INTERSECTION_ANNOUNCE_M
            ManeuverType.MERGE -> MERGE_ANNOUNCE_M
            else -> TURN_ANNOUNCE_M
        }

        /**
         * 상황별 문구.
         * 초보(자세히): 참가신청서 예시 문장 그대로. 일반(간단히): 짧은 문장 (팀 초안 2026-11-13)
         */
        fun messageFor(type: ManeuverType, mode: DriveMode = DriveMode.BEGINNER): Coaching? =
            if (mode == DriveMode.NORMAL) shortMessageFor(type) else detailedMessageFor(type)

        /** 간단히: 상황 이름 + 확인할 것 하나만 */
        fun shortMessageFor(type: ManeuverType): Coaching? = when (type) {
            ManeuverType.LEFT -> Coaching("LEFT", "곧 좌회전이에요. 신호 확인해 주세요.", "곧 좌회전이에요. 신호 확인해 주세요.")
            ManeuverType.RIGHT -> Coaching("RIGHT", "곧 우회전이에요. 보행자 확인해 주세요.", "곧 우회전이에요. 보행자 확인해 주세요.")
            ManeuverType.INTERSECTION -> Coaching("INTERSECTION", "교차로예요. 속도 줄여 주세요.", "교차로예요. 속도 줄여 주세요.")
            ManeuverType.MERGE -> Coaching("MERGE", "곧 합류 구간이에요. 뒤차 확인해 주세요.", "곧 합류 구간이에요. 뒤차 확인해 주세요.")
            ManeuverType.OTHER -> null
        }

        /** 자세히: 신청서 예시 문장 */
        fun detailedMessageFor(type: ManeuverType): Coaching? = when (type) {
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
            ManeuverType.MERGE -> Coaching(
                key = "MERGE",
                speech = "곧 고속도로 합류 구간이에요. 사이드미러로 뒤차를 확인하고 속도를 맞춰 주세요.",
                bubble = "곧 합류 구간이에요. 뒤차를 확인하고 속도를 맞춰 주세요.",
            )
            ManeuverType.OTHER -> null
        }
    }
}
