package com.example.drivemate.shared

// ====================================================================================
// [5단계] 졸업 기준 -- 숫자나 문구를 바꾸고 싶으면 "이 부분만" 고치면 된다.
//
// 규칙 요약
//  - 단계는 "누적 주행 거리"로 자동으로 오른다:  1 새싹(0km~) -> 2 적응 중(30km~) -> 3 거의 졸업(100km~)
//  - 졸업 = 누적 200km 이상  그리고  "깨끗한 주행" 5번 연속
//  - 깨끗한 주행 = 0.5km 이상 달렸고, 실수(과속/급커브 안내)가 0번인 주행. 실수가 있는 주행이 끼면 연속이 0으로 돌아간다.
//    (0.5km보다 짧은 주행은 연속을 끊지도, 늘리지도 않는다)
//  - 졸업은 한 번 하면 유지된다 (이후 실수가 있어도 졸업은 그대로)
// ====================================================================================
// ★ 데모 모드: true로 바꾸면 졸업 기준이 짧아져서 영상에서 졸업 장면을 금방 보여줄 수 있다.
//   (졸업 = 누적 1km + 깨끗한 주행 2번 연속). 실제 배포 때는 반드시 false!
const val DEMO_MODE = false

val STAGE2_MIN_KM = if (DEMO_MODE) 0.5 else 30.0            // 2단계(적응 중)가 되는 누적 거리
val STAGE3_MIN_KM = if (DEMO_MODE) 0.8 else 100.0           // 3단계(거의 졸업)가 되는 누적 거리
val GRADUATE_MIN_KM = if (DEMO_MODE) 1.0 else 200.0         // 졸업에 필요한 누적 거리
val GRADUATE_CLEAN_STREAK = if (DEMO_MODE) 2 else 5         // 졸업에 필요한 "깨끗한 주행" 연속 횟수
val MIN_TRIP_KM_FOR_GRADUATION = if (DEMO_MODE) 0.3 else 0.5   // 이 거리(km) 이상 달린 주행만 "깨끗한 주행" 판단에 쓴다
val STREAK_PRAISE_FROM = if (DEMO_MODE) 1 else 3            // 깨끗한 주행이 이 횟수 이상 연속이면 칭찬 문장을 붙인다

const val STAGE_GRADUATED = 4                // 졸업한 상태를 나타내는 단계 번호
const val GRADUATION_MESSAGE = "졸업을 축하해요! 이제 혼자서도 충분히 안전하게 운전할 수 있어요."

// 단계 이름은 세 개로 통일: 1단계 = 새싹, 2~3단계 = 지켜보기, 졸업 (음성과 화면이 같은 이름을 쓴다)
fun stageName(stage: Int): String = when (stage) {
    1 -> "새싹"
    2, 3 -> "지켜보기"
    else -> "졸업"
}
const val ALMOST_GRADUATED_MESSAGE = "졸업이 얼마 남지 않았어요."   // 지켜보기 안에서 2단계 -> 3단계로 오를 때
// ====================================================================================

/** 지금까지 기록으로 계산한 졸업 상태 */
data class GraduationStatus(
    val stage: Int,          // 1~3 = 진행 중, 4 = 졸업
    val totalKm: Double,     // 누적 주행 거리
    val cleanStreak: Int,    // 깨끗한 주행 연속 횟수
    val graduated: Boolean,
)

/** 주행이 끝난 순간의 변화: 단계가 올랐는지, 방금 졸업했는지 (요약 음성/화면에 쓴다) */
data class GraduationUpdate(
    val status: GraduationStatus,
    val leveledUpTo: Int?,       // 이번 주행으로 오른 단계 (오르지 않았으면 null)
    val newlyGraduated: Boolean, // 이번 주행으로 처음 졸업했는지
    val lastTripClean: Boolean,  // 방금 끝난 주행이 "깨끗한 주행"이었는지
    val coachLevelUp: Int? = null, // 이번 주행으로 오른 코치 수준 (2 또는 3, 오르지 않았으면 null) -> "이제 위험할 때만 말할게요" 안내
)

/**
 * 끝난 주행들(오래된 것부터)로 졸업 상태를 계산한다. 화면/저장소와 상관없는 "순수 계산"이라 따로 테스트하기 쉽다.
 * @param alreadyGraduated 이미 졸업했었다면 true (졸업은 유지되므로)
 */
fun evaluateGraduation(closedTrips: List<TripResult>, alreadyGraduated: Boolean): GraduationStatus {
    val totalKm = closedTrips.sumOf { it.distanceKm }

    // 최근 주행부터 거꾸로 보면서 "깨끗한 주행"이 몇 번 연속인지 센다
    var streak = 0
    for (r in closedTrips.asReversed()) {
        if (r.mistakeCount > 0) break   // 실수가 있는 주행을 만나면 거기서 끝 (짧은 주행이어도 실수는 연속을 끊는다)
        if (r.distanceKm < MIN_TRIP_KM_FOR_GRADUATION) continue   // 실수 없는 짧은 주행은 건너뜀 (늘리지도 끊지도 않음)
        streak++
    }

    val graduated = alreadyGraduated || (totalKm >= GRADUATE_MIN_KM && streak >= GRADUATE_CLEAN_STREAK)
    val stage = when {
        graduated -> STAGE_GRADUATED
        totalKm >= STAGE3_MIN_KM -> 3
        totalKm >= STAGE2_MIN_KM -> 2
        else -> 1
    }
    return GraduationStatus(stage, totalKm, streak, graduated)
}

/**
 * 요약 음성 뒤에 붙일 졸업 문장. 말할 게 없으면 null.
 *  - 졸업 직후:     "졸업을 축하해요! ..."
 *  - 단계가 오름:   "축하해요! 적응 중 단계가 됐어요."
 *  - 깨끗한 주행 연속 3번 이상: "깨끗한 주행 3번 연속이에요. 졸업까지 2번 더 필요해요."
 */
fun graduationSpoken(u: GraduationUpdate): String? {
    // 코치 수준이 오르면 말수가 줄어든다는 안내를 단계 상승 문장 뒤에 붙인다
    val quiet = when (u.coachLevelUp) {
        COACH_LEVEL_QUIET -> COACH_QUIET_MESSAGE
        COACH_LEVEL_SILENT -> COACH_SILENT_MESSAGE
        else -> null
    }
    val main = when {
        u.newlyGraduated -> GRADUATION_MESSAGE
        u.leveledUpTo == 3 -> ALMOST_GRADUATED_MESSAGE   // 2 -> 3단계: 둘 다 "지켜보기"라서 단계 이름 대신 이 말만 한다
        u.leveledUpTo != null -> "축하해요! ${stageName(u.leveledUpTo)} 단계가 됐어요."
        else -> streakPraise(u)
    }
    return listOfNotNull(main, quiet).joinToString(" ").ifEmpty { null }
}

// 깨끗한 주행 연속 칭찬 (이미 졸업한 뒤에는 없음)
private fun streakPraise(u: GraduationUpdate): String? {
    if (u.status.graduated) return null   // 이미 졸업한 뒤에는 졸업 문장을 붙이지 않는다

    if (u.lastTripClean && u.status.cleanStreak >= STREAK_PRAISE_FROM) {
        val streak = u.status.cleanStreak
        val needStreak = GRADUATE_CLEAN_STREAK - streak
        val needKm = GRADUATE_MIN_KM - u.status.totalKm
        return when {
            needStreak > 0 -> "깨끗한 주행 ${streak}번 연속이에요. 졸업까지 ${needStreak}번 더 필요해요."
            needKm > 0 -> "깨끗한 주행 ${streak}번 연속이에요. 졸업까지 ${km1(needKm)}킬로미터 남았어요."
            else -> null
        }
    }
    return null
}

/** 졸업 진행 화면 윗줄. 예: "1단계 새싹" / 졸업 후: "졸업했어요" */
fun graduationHeadline(s: GraduationStatus): String =
    if (s.graduated) "졸업했어요" else stageName(s.stage)

/** 졸업 진행 화면 아랫줄. 예: "깨끗한 주행 2/5 · 12.4/30.0km" / 졸업 후: "누적 205.3km" */
fun graduationDetail(s: GraduationStatus): String {
    if (s.graduated) return "누적 ${km1(s.totalKm)}km"
    val goal = when (s.stage) {
        1 -> STAGE2_MIN_KM
        2 -> STAGE3_MIN_KM
        else -> GRADUATE_MIN_KM
    }
    return "깨끗한 주행 ${s.cleanStreak}/$GRADUATE_CLEAN_STREAK · ${km1(s.totalKm)}/${km1(goal)}km"
}

/** 격자 칸처럼 좁은 곳에 쓰는 짧은 아랫줄. 예: "깨끗한 주행 2/5" / 졸업 후: "누적 205.3km" */
fun graduationDetailShort(s: GraduationStatus): String =
    if (s.graduated) "누적 ${km1(s.totalKm)}km" else "깨끗한 주행 ${s.cleanStreak}/$GRADUATE_CLEAN_STREAK"

/** 한 줄로 합친 졸업 진행. 예: "1단계 새싹 · 깨끗한 주행 2/5 · 12.4/30.0km" */
fun graduationLine(s: GraduationStatus): String = graduationHeadline(s) + " · " + graduationDetail(s)
