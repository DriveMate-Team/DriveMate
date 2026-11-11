package com.example.drivemate.shared

// ====================================================================================
// [실력이 늘수록 말수가 줄어드는 코치] 단계별 실시간 안내 기준 -- 숫자와 문구는 "이 부분만" 고치면 된다.
//
// 코치 수준은 졸업 단계에서 정해진다 (단계는 주행이 끝날 때 바뀌므로 "다음 주행부터" 적용):
//   수준 1 (수다쟁이)      : 1단계 새싹            -> 기본 기준으로 실수마다 바로 안내
//   수준 2 (위험할 때만)   : 2단계 적응 중, 3단계 거의 졸업 -> 더 위험할 때만 실시간 안내, 나머지는 주차 후 요약에만
//   수준 3 (아주 위험할 때만): 졸업               -> 아주 위험할 때만 실시간 안내, 주차 후에는 짧은 요약만
//
// 중요: 말하지 않는 실수도 "기록"은 항상 한다(기본 기준: DriveCoach.kt 맨 위 SPEED_LIMIT_KMH 등).
//       그래야 졸업 판단의 "깨끗한 주행"이 부풀려지지 않는다. 기준 단계가 올라가도 기록 기준은 그대로다.
// ====================================================================================

/** 한 수준의 실시간 안내 기준 */
data class LiveRule(
    val speedOverKmh: Float,        // 이 속도(km/h)를 "넘으면" 과속 안내를 말한다
    val curveMinSpeedKmh: Float,    // 이 속도(km/h) "이상"일 때만 급커브 안내를 말한다
    val curveAngleDeg: Float,       // 조향각 절댓값(도)이 이 값을 "넘으면" 급커브 안내를 말한다
)

const val COACH_LEVEL_CHATTY = 1   // 수다쟁이
const val COACH_LEVEL_QUIET = 2    // 위험할 때만
const val COACH_LEVEL_SILENT = 3   // 아주 위험할 때만

val LIVE_RULES: Map<Int, LiveRule> = mapOf(
    // 수준 1: 기본 기준 그대로 (DriveCoach.kt 맨 위 값과 같다)
    COACH_LEVEL_CHATTY to LiveRule(speedOverKmh = 60f, curveMinSpeedKmh = 30f, curveAngleDeg = 90f),
    // 수준 2: 75km/h 초과, 50km/h 이상에서 급커브(각도는 기본과 같은 90도 초과)
    COACH_LEVEL_QUIET to LiveRule(speedOverKmh = 75f, curveMinSpeedKmh = 50f, curveAngleDeg = 90f),
    // 수준 3(졸업): 90km/h 초과, 70km/h 이상에서 150도 초과
    COACH_LEVEL_SILENT to LiveRule(speedOverKmh = 90f, curveMinSpeedKmh = 70f, curveAngleDeg = 150f),
)

/** 단계에서 코치 수준을 구한다: 1단계=1, 2~3단계=2, 졸업=3 */
fun coachLevelOf(stage: Int): Int = when {
    stage >= STAGE_GRADUATED -> COACH_LEVEL_SILENT
    stage >= 2 -> COACH_LEVEL_QUIET
    else -> COACH_LEVEL_CHATTY
}

// 코치 수준이 오를 때 요약 음성 뒤에 붙는 안내
const val COACH_QUIET_MESSAGE = "이제 위험할 때만 말할게요."
const val COACH_SILENT_MESSAGE = "이제부터는 아주 위험할 때만 말할게요."
// ====================================================================================
