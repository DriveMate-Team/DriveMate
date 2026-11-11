package com.example.drivemate.shared


// ====================================================================================
// [화면 디자인] 화면에 보이는 문구와 색 규칙 -- 바꾸고 싶으면 "이 부분만" 고치면 된다.
//
// 디자인 원칙 (Pleos 디자인 가이드):
//  - 운전 중에는 1~2초 안에 파악되는 핵심만: 3줄 이하, 짧은 문장. 속도/기어는 계기판(Driving view)에 있으니 중복하지 않는다.
//  - 색만으로 구분하지 않는다: 항상 "아이콘 + 글자"를 같이 쓴다.
//  - 색 규칙: 안전 = 초록, 주의 = 노랑, 졸업 관련 = 파랑. 색상 값 자체는 Pleos 호스트의 테마(주간/야간)가 정한다.
// ====================================================================================
const val APP_TITLE = "DriveMate"

// 주행 화면(운전 중)
const val STATUS_SAFE = "안전 운전 중"
const val CAUTION_SPEED = "속도를 줄여 주세요"
const val CAUTION_CURVE = "핸들은 천천히 돌려요"
const val CAUTION_SECONDS = 10L          // 경고 직후 "주의" 표시를 유지하는 시간(초)
const val MISTAKES_NONE = "없음"

// 정차 화면(P)
const val NO_TRIP_YET = "아직 주행 기록이 없어요"
const val DEV_INFO_BUTTON = "개발자 정보"

// 행 제목 (Row 제목은 항상 같게 둬야 호스트가 "내용만 갱신"으로 보고 템플릿 횟수 제한에 걸리지 않는다)
const val ROW_STATUS = "운전 상태"
const val ROW_TRIP = "이번 주행"
const val ROW_MISTAKES = "실수"
const val ROW_GRADUATION = "졸업 진행"
const val ROW_LAST_TRIP = "지난 주행"
// ====================================================================================

/** 실수 요약 글자. 예: "과속 1번 · 급커브 0번" / 없으면 "없음" */
fun mistakesText(r: TripResult): String =
    if (r.mistakeCount == 0) MISTAKES_NONE else "과속 ${r.speedCount}번 · 급커브 ${r.curveCount}번"

/** 격자 칸처럼 좁은 곳에 쓰는 짧은 실수 글자. 예: "과속 3 · 급커브 1" / 없으면 "없음" (긴 글자는 "…"로 잘림) */
fun mistakesShort(r: TripResult): String =
    if (r.mistakeCount == 0) MISTAKES_NONE else "과속 ${r.speedCount} · 급커브 ${r.curveCount}"

/** "지난 주행" 한 줄. 예: "0.8km · 과속 1번 · 급커브 1번" */
fun lastTripText(r: TripResult?): String =
    if (r == null) NO_TRIP_YET else "${km1(r.distanceKm)}km · " + (if (r.mistakeCount == 0) "실수 없음" else mistakesShort(r))

// ====================================================================================
// [메이트 화면 문구] 화면에 보이는 문장 모음 -- 바꾸고 싶으면 "이 부분만" 고치면 된다.
// (음성 문장은 DriveCoach.kt / TripSummary.kt / QuestionAnswerer.kt 쪽에 따로 있다)
// ====================================================================================
const val DRIVING_SAFE_TITLE = "잘하고 있어요!"
const val DRIVING_SAFE_SUB_CHATTY = "실수하면 바로 말로 알려드릴게요"        // 코치 수준 1 (실수마다 바로 말함)
const val DRIVING_SAFE_SUB_QUIET = "필요할 때만 말로 알려드릴게요"           // 코치 수준 2~3
const val DRIVING_SPEED_TITLE = "속도 줄여볼까요?"
const val DRIVING_SPEED_SUB = "지금 조금 빨라요"
const val DRIVING_CURVE_TITLE = "핸들은 천천히!"
const val DRIVING_CURVE_SUB = "커브에선 속도부터 줄여요"
const val BADGE_VOICE = "음성으로 안내 중"

/** 상단 배지에 쓰는 단계 이름: "새싹 단계" / "지켜보기 단계" / "졸업" */
fun stageBadgeText(s: GraduationStatus): String = if (s.graduated) "졸업" else "${stageName(s.stage)} 단계"

/** 정차 화면 큰 카드의 한 줄 팁 (지난 주행의 실수 종류에 맞춤) */
fun parkedTip(latest: TripResult?, graduated: Boolean): String = when {
    graduated -> "이제 혼자서도 충분해요. 늘 안전 운전!"
    latest == null -> "오늘도 같이 안전하게 달려봐요"
    latest.speedCount > 0 && latest.curveCount > 0 -> "속도와 커브만 조심하면 금방 다음 단계예요"
    latest.speedCount > 0 -> "출발할 때 속도만 조심하면 금방 다음 단계예요"
    latest.curveCount > 0 -> "커브 전에 속도를 먼저 줄이면 금방 다음 단계예요"
    else -> "지금처럼만 달리면 곧 다음 단계예요"
}

/** 요약 화면 "다음 주행 팁" 한 줄 */
fun summaryTip(r: TripResult): String = when {
    r.speedCount > 0 && r.curveCount > 0 -> "한 번에 하나씩, 속도부터 신경 써봐요"
    r.speedCount > 0 -> "속도계를 한 번 더 확인하면 과속을 줄일 수 있어요"
    r.curveCount > 0 -> "커브 앞에서는 속도부터 줄이고 핸들은 천천히 돌려요"
    else -> "이대로 한 번만 더 깨끗하게 달려봐요"
}

/** 요약 화면 메이트 옆 응원 문장 (큰 글자 / 작은 글자) */
fun summaryMateLine(r: TripResult): Pair<String, String> = when {
    r.mistakeCount == 0 -> "수고했어요!" to "오늘도 아주 잘했어요"
    r.speedCount > 0 && r.curveCount > 0 -> "수고했어요!" to "다음엔 하나씩 천천히 신경 써봐요"
    r.speedCount > 0 -> "수고했어요!" to "다음엔 속도만 신경 써봐요"
    else -> "수고했어요!" to "다음엔 커브만 신경 써봐요"
}

/** 졸업 화면 */
const val GRAD_BADGE = "졸업"
const val GRAD_TITLE = "이제 혼자서도 충분해요!"
const val GRAD_SUB = "앞으로는 정말 위험할 때만 말할게요. 그동안 함께 달려줘서 고마워요."

/** 거리 글자: 목표처럼 딱 떨어지는 값은 소수점 없이 ("30 km"), 아니면 한 자리 ("10.7 km") */
fun kmCompact(km: Double): String = if (km >= 100.0 || km % 1.0 == 0.0) String.format(java.util.Locale.US, "%.0f", km) else km1(km)
// ====================================================================================
