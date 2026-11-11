package com.example.drivemate.ui

import com.example.drivemate.data.EventEntity
import com.example.drivemate.data.TripEntity
import com.example.drivemate.model.EventType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 숫자·시간을 화면에 보여줄 글자로 바꾸는 도우미 모음.
 */

/** 시각: "오후 3:20" */
fun formatTime(ms: Long): String = SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(ms))

/** 날짜: "11월 12일 (목)" */
fun formatDate(ms: Long): String = SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(Date(ms))

/** 걸린 시간: "12분" (1분이 안 되면 "1분 미만") */
fun formatDuration(startMs: Long, endMs: Long?): String {
    val minutes = ((endMs ?: startMs) - startMs) / 60_000
    return if (minutes < 1) "1분 미만" else "${minutes}분"
}

/** 거리: 1km가 안 되면 "350m", 넘으면 "2.4km" */
fun formatDistance(meters: Int): String =
    if (meters < 1000) "${meters}m" else String.format(Locale.KOREA, "%.1fkm", meters / 1000f)

/** 남은 거리: 주행 중 화면용. 10m 단위로 맞춰서 숫자가 덜 자주 바뀌게 한다 */
fun formatRemaining(meters: Int): String =
    if (meters >= 1000) String.format(Locale.KOREA, "%.1fkm", meters / 1000f) else "${(meters / 10) * 10}m"

/** 기록 목록·홈 화면에 보여줄 주행 한 건의 요약 */
data class TripSummary(
    val id: Long,
    val date: String,          // "11월 12일 (목) 오후 3:20"
    val destination: String,   // 목적지 (없으면 "목적지 없음")
    val meta: String,          // "12분 · 2.4km"
    val numbers: String,       // "급감속 1 · 급가속 0 · 깜빡이 기록 없음 2"
)

/** 주행과 그 이벤트들로 요약을 만든다. 평가하는 말 없이 기록된 숫자만 보여준다 */
fun summarize(trip: TripEntity, events: List<EventEntity>): TripSummary {
    val mine = events.filter { it.tripId == trip.id }
    val brakes = mine.count { it.type == EventType.HARD_BRAKE.name }
    val accels = mine.count { it.type == EventType.HARD_ACCEL.name }
    val noSignal = mine.count { it.type == EventType.TURN.name && it.signalUsed == false }
    return TripSummary(
        id = trip.id,
        date = "${formatDate(trip.startTime)} ${formatTime(trip.startTime)}",
        destination = trip.destination ?: "목적지 없음",
        meta = "${formatDuration(trip.startTime, trip.endTime)} · ${formatDistance(trip.distanceM)}",
        numbers = "급감속 $brakes · 급가속 $accels · 깜빡이 기록 없음 $noSignal",
    )
}
