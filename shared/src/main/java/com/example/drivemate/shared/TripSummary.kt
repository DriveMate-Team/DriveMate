package com.example.drivemate.shared

import java.util.Locale

// ====================================================================================
// [4단계] 주행 요약 설정 -- 숫자나 문구를 바꾸고 싶으면 "이 부분만" 고치면 된다.
// ====================================================================================
const val MIN_TRIP_KM_FOR_SUMMARY = 0.1     // 이 거리(km)보다 짧은 주행은 요약을 말하지도, 화면을 띄우지도 않는다 (기록 파일에는 남음)

const val SUMMARY_INTRO = "이번에"            // 요약 첫 문장 앞말 ("이번에 2.9킬로미터 달렸어요.")
const val PRAISE_MESSAGE = "이번엔 제가 할 말이 없었어요. 아주 잘했어요!"   // 실수가 0번일 때 말하는 칭찬
// ====================================================================================

// 숫자를 소수점 첫째 자리 글자로 만든다. (Locale.US = 항상 "2.9" 형태. 나라 설정에 따라 "2,9"가 되는 것을 막음)
fun km1(km: Double): String = String.format(Locale.US, "%.1f", km)

// 실수 목록 글자를 만든다. 0번인 종류는 뺀다. 예: "과속 2번, 급커브 1번" / "과속 2번" / "" (둘 다 0이면 빈 글자)
fun mistakeParts(r: TripResult): String = listOfNotNull(
    if (r.speedCount > 0) "과속 ${r.speedCount}번" else null,
    if (r.curveCount > 0) "급커브 ${r.curveCount}번" else null,
).joinToString(", ")

/**
 * 음성으로 말할 요약 문장.
 *  - 실수 있음: "이번에 2.9킬로미터 달렸어요. 과속 2번, 급커브 1번 있었어요."
 *  - 실수 0번:  "이번엔 제가 할 말이 없었어요. 아주 잘했어요!"
 * 주의: 음성에서는 "km"가 어색하게 읽힐 수 있어서 "킬로미터"로 풀어 쓴다.
 */
fun buildSpokenSummary(r: TripResult): String {
    if (r.mistakeCount == 0) return PRAISE_MESSAGE
    if (isShortSummary(r)) return "$SUMMARY_INTRO ${km1(r.distanceKm)}킬로미터, ${mistakeParts(r)}이에요."   // 졸업 후 짧은 요약
    return "$SUMMARY_INTRO ${km1(r.distanceKm)}킬로미터 달렸어요. ${mistakeParts(r)} 있었어요."
}

/** 졸업한 뒤의 주행이면 짧은 요약을 쓴다. (이번 주행으로 막 졸업한 경우는 축하와 함께 평소 요약) */
fun isShortSummary(r: TripResult): Boolean = r.graduation?.let { it.status.graduated && !it.newlyGraduated } == true

/** 화면에 보여줄 한 줄 요약 (화면에서는 "km"를 그대로 쓴다) */
fun buildScreenSummary(r: TripResult): String {
    if (r.mistakeCount == 0) return PRAISE_MESSAGE
    if (isShortSummary(r)) return "$SUMMARY_INTRO ${km1(r.distanceKm)}km, ${mistakeParts(r)}이에요."
    return "$SUMMARY_INTRO ${km1(r.distanceKm)}km 달렸어요. ${mistakeParts(r)} 있었어요."
}

/** "2.9km · 12분" 처럼 주행 거리와 시간을 한 줄로 */
fun buildTripLine(r: TripResult): String {
    val min = if (r.durationMin < 1) "1분 미만" else "${r.durationMin}분"
    return "${km1(r.distanceKm)}km · $min"
}
