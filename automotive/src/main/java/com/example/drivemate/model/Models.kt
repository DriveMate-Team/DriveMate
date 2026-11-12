package com.example.drivemate.model

/*
 * 앱 전체에서 함께 쓰는 "기본 낱말" 모음.
 * 화면·코칭·기록이 모두 같은 낱말(모드, 연습 주제, 기어 등)을 쓰도록 한곳에 정의한다.
 */

/**
 * 주행 모드 = 코칭 수준 (신청서 4-①, 4-⑥. 2026-11-13 팀 결정: 초보 = 자세히, 일반 = 간단히로 합침)
 */
enum class DriveMode(val label: String) {
    BEGINNER("초보 · 자세히"),   // 좌회전·우회전 코칭을 항상, 자세한 문장으로 말해 준다
    NORMAL("일반 · 간단히"),     // 고른 연습 주제만, 짧은 문장으로 말해 준다
}

/** 연습 주제 (설계문서 "주행 준비" 화면의 칩 4개) */
enum class Topic(val label: String) {
    LEFT_TURN("좌회전"),
    RIGHT_TURN("우회전"),
    INTERSECTION("교차로"),
    LANE_CHANGE("차선 변경"),
}

/** 기어 */
enum class Gear { P, R, N, D, UNKNOWN }

/** 방향지시등(깜빡이) 상태 */
enum class TurnSignal { OFF, LEFT, RIGHT }

/** 차량에서 읽은 현재 값 묶음 */
data class VehicleState(
    val speedKmh: Float = 0f,
    val gear: Gear = Gear.UNKNOWN,
    val turnSignal: TurnSignal = TurnSignal.OFF,
    val brake: Boolean = false,
)

/** 내비가 알려주는 "다음 상황"의 종류 */
enum class ManeuverType(val label: String) {
    LEFT("좌회전"),
    RIGHT("우회전"),
    INTERSECTION("교차로"),
    MERGE("합류"),   // 고속도로 진입(IC)·분기 합류 지점 (신청서 3-①)
    OTHER("안내"),
}

/**
 * 내비의 다음 상황 하나.
 * @param id 같은 회전인지 구분하는 번호 (회전을 지나가면 번호가 바뀐다)
 * @param distanceM 그 지점까지 남은 거리(m)
 */
data class Maneuver(val id: Int, val type: ManeuverType, val distanceM: Int)

/** 내비에서 읽은 현재 값 묶음 */
data class NaviState(
    val hasRoute: Boolean = false,      // 경로 안내 중인지
    val destination: String? = null,    // 목적지 이름
    val next: Maneuver? = null,         // 다음 상황 (없으면 null)
)

/** 기록하는 이벤트 종류 (설계문서 5단계) */
enum class EventType(val label: String) {
    HARD_BRAKE("급감속"),
    HARD_ACCEL("급가속"),
    TURN("회전"),
    DIFFICULT("어려웠던 곳"),
    IRREGULAR_SPEED("불규칙한 속도 변화"),   // D항목: 팀 기준값 (EventDetector 참고)
}

/** 쉼표로 이어 붙인 글자("LEFT_TURN,INTERSECTION")를 연습 주제 묶음으로 바꾼다 (DB 저장용) */
fun String.toTopics(): Set<Topic> =
    split(",").mapNotNull { name -> Topic.entries.find { it.name == name.trim() } }.toSet()

/** 연습 주제 묶음을 쉼표로 이어 붙인 글자로 바꾼다 (DB 저장용) */
fun Set<Topic>.toStorage(): String = Topic.entries.filter { it in this }.joinToString(",") { it.name }

/** 연습 주제를 화면·음성에 쓸 글자로: "좌회전, 교차로" */
fun Set<Topic>.toLabel(): String =
    if (isEmpty()) "자유 주행" else Topic.entries.filter { it in this }.joinToString(", ") { it.label }
