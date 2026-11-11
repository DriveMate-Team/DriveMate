package com.example.drivemate.shared

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 저장된 주행 기록(JSON 파일)을 "읽기만" 하는 도구.
 * - DriveEventStore(화면이 켜져 있을 때 기록을 쌓는 쪽)와
 * - GleoActionReceiver(Gleo가 질문했을 때 답을 만드는 쪽, 화면이 없어도 동작)
 * 가 같은 읽기 코드를 쓰도록 한곳에 모았다.
 */
data class DriveLogSnapshot(
    val events: List<DriveEvent>,
    val trips: List<Trip>,
    val announcedStage: Int,
    val graduatedAtMs: Long,
) {
    /** 가장 최근 주행 (주행 중이면 그 주행). 기록이 없으면 null */
    fun latestTrip(): Trip? = trips.lastOrNull()

    /** 가장 최근 주행의 결과(실수 횟수 포함) */
    fun latestResult(): TripResult? = latestTrip()?.let { tripResultOf(it, events) }

    /** 끝난 주행들의 결과 (오래된 것부터) - 졸업 판단에 쓴다 */
    fun closedResults(): List<TripResult> = trips.filter { it.closed }.map { tripResultOf(it, events) }

    /** 졸업 상태 (졸업은 한 번 하면 유지) */
    fun graduationStatus(): GraduationStatus = evaluateGraduation(closedResults(), alreadyGraduated = graduatedAtMs > 0L)
}

/** 이 주행 시간대에 기록된 과속/급커브 이벤트를 세어서 결과로 만든다 */
fun tripResultOf(trip: Trip, events: List<DriveEvent>): TripResult {
    val inTrip = events.filter { it.timeMs >= trip.startMs && (!trip.closed || it.timeMs <= trip.endMs) }
    return TripResult(
        trip,
        speedCount = inTrip.count { it.type == EVENT_SPEED },
        curveCount = inTrip.count { it.type == EVENT_CURVE },
    )
}

/** 앱 전용 폴더의 기록 파일을 읽는다. 파일이 없거나 깨졌으면 빈 기록을 돌려준다. */
fun readDriveLog(context: Context): DriveLogSnapshot = readDriveLogFile(File(context.filesDir, LOG_FILE_NAME))

fun readDriveLogFile(file: File): DriveLogSnapshot {
    val empty = DriveLogSnapshot(emptyList(), emptyList(), 1, 0L)
    if (!file.exists()) return empty
    return try {
        val root = JSONObject(file.readText())
        val events = mutableListOf<DriveEvent>()
        val evArr = root.optJSONArray("events") ?: JSONArray()
        for (i in 0 until evArr.length()) {
            val o = evArr.getJSONObject(i)
            events.add(DriveEvent(
                o.getString("type"), o.getLong("timeMs"),
                o.getDouble("speedKmh").toFloat(), o.getDouble("angleDeg").toFloat(),
                o.optBoolean("live", true)))   // 예전 기록 파일에는 live가 없으므로 true로 읽는다
        }
        val trips = mutableListOf<Trip>()
        val tripArr = root.optJSONArray("trips") ?: JSONArray()
        for (i in 0 until tripArr.length()) {
            val o = tripArr.getJSONObject(i)
            trips.add(Trip(o.getLong("startMs"), o.getLong("endMs"), o.getDouble("distanceKm"), o.optBoolean("closed", true)))
        }
        val grad = root.optJSONObject("graduation")
        DriveLogSnapshot(
            events, trips,
            announcedStage = grad?.optInt("announcedStage", 1) ?: 1,
            graduatedAtMs = grad?.optLong("graduatedAtMs", 0L) ?: 0L,
        )
    } catch (e: Exception) {
        empty   // 파일이 깨졌으면 빈 기록으로 취급
    }
}
