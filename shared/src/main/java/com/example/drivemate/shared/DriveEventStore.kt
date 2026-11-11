package com.example.drivemate.shared

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ====================================================================================
// [3단계] 주행 기록 저장 설정
// ====================================================================================
const val EVENT_SPEED = "SPEED"          // 이벤트 종류: 과속
const val EVENT_CURVE = "CURVE"          // 이벤트 종류: 급커브
const val LOG_FILE_NAME = "drive_log.json"   // 저장 파일 이름 (앱 전용 폴더 files/ 안에 만들어진다)
const val TICK_MS = 1000L                // 거리 누적 간격: 1초마다 "현재 속도 x 경과 시간"을 더한다
const val SAVE_EVERY_TICKS = 5           // 거리 기록은 이 횟수(=5초)마다 한 번 파일에 저장 (이벤트는 즉시 저장)
const val MAX_TICK_GAP_MS = 5000L        // 앱이 멈췄다 돌아온 경우 등, 간격이 이보다 길면 이만큼만 더한다

// [4단계] 기어 값 (에뮬레이터로 확인된 값): P=4, R=2, N=1, D=8
const val GEAR_P = 4
const val GEAR_R = 2
const val GEAR_N = 1
const val GEAR_D = 8
// ====================================================================================

/** 이벤트 한 건: 언제, 무슨 일이, 어떤 값이었는지 */
data class DriveEvent(
    val type: String,
    val timeMs: Long,
    val speedKmh: Float,
    val angleDeg: Float,
    val live: Boolean = true,   // 실시간으로 말했는지 (false = 조용한 코치 수준이라 기록만 하고 말하지 않음, 주차 후 요약에만 반영)
)

/**
 * 주행(trip) 한 건: 시작/끝 시각과 달린 거리.
 * [4단계] 기어가 P에서 벗어나면 시작, P로 돌아오면 끝. closed=false 이면 "지금 주행 중".
 */
data class Trip(val startMs: Long, var endMs: Long, var distanceKm: Double, var closed: Boolean = true)

/** 끝난 주행 한 건의 결과: 요약 말하기/요약 화면에 쓴다. */
data class TripResult(
    val trip: Trip,
    val speedCount: Int,
    val curveCount: Int,
    val graduation: GraduationUpdate? = null,   // [5단계] 이 주행으로 졸업 상태가 어떻게 바뀌었는지 (요약에 쓴다)
) {
    val distanceKm: Double get() = trip.distanceKm
    val durationMin: Long get() = (trip.endMs - trip.startMs) / 60_000L
    val mistakeCount: Int get() = speedCount + curveCount
}

/**
 * 과속/급커브 이벤트와 주행 거리를 JSON 파일로 저장하고 읽는 클래스.
 * 개인정보(차량 ID 등)는 저장하지 않는다. 앱 전용 폴더 안에만 저장된다.
 *
 * 모든 함수는 "메인 스레드"에서만 부른다 (DriveCoach/화면이 이미 그렇게 호출함).
 *
 * @param onChanged 기록이 바뀔 때마다 화면에 보여줄 요약 글자를 받는 함수
 * @param onTripEnded [4단계] 주행이 끝났을 때(기어가 P로 돌아옴) 그 주행의 결과를 받는 함수
 */
class DriveEventStore(
    context: Context,
    private val onChanged: (String) -> Unit,
    private val onTripEnded: (TripResult) -> Unit,
    private val onTripStarted: () -> Unit = {},   // [출발 인사] 기어가 P에서 벗어나 주행이 시작될 때(앱을 이미 주행 중에 켠 경우는 제외)
) {
    private val file = File(context.filesDir, LOG_FILE_NAME)
    private val handler = Handler(Looper.getMainLooper())

    private val events = mutableListOf<DriveEvent>()
    private val trips = mutableListOf<Trip>()

    private var latestSpeedKmh = 0f      // 가장 최근 속도 (1초마다 거리 계산에 사용)
    private var lastTickAt = 0L          // 마지막으로 거리를 더한 시각
    private var ticksSinceSave = 0
    private var running = false
    private var gearSeen = false         // [4단계] 앱을 켠 뒤 기어 값을 한 번이라도 받았는지
    private var stage = 1                // 현재 졸업 단계 (코치 수준을 정하는 값. 주행이 끝날 때마다 다시 계산해서 저장해 둔다)
    private var announcedStage = 1       // [5단계] 지금까지 "축하했던" 가장 높은 단계 (단계 상승을 한 번만 알리려고 저장)
    private var graduatedAtMs = 0L       // [5단계] 졸업한 시각. 0이면 아직 졸업 전 (졸업은 한 번 하면 유지)
    private var sessionStartMs = 0L      // [4단계] 이 앱이 켜진 시각. 이미 달리는 중에 앱을 켰을 때 주행 시작 시각으로 쓴다

    // 1초마다 실행되는 거리 누적 작업
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val dtMs = minOf(now - lastTickAt, MAX_TICK_GAP_MS)   // 경과 시간 (너무 길면 제한)
            lastTickAt = now

            val trip = openTrip()
            if (trip != null && latestSpeedKmh > 0f) {   // [4단계] "주행 중(기어가 P가 아님)"이고 달리고 있을 때만 누적
                // 거리(km) = 속도(km/h) x 시간(h).  시간(h) = 밀리초 / 3,600,000
                trip.distanceKm += latestSpeedKmh * dtMs / 3_600_000.0
                trip.endMs = System.currentTimeMillis()
                ticksSinceSave++
                if (ticksSinceSave >= SAVE_EVERY_TICKS) save()
                onChanged(summary())
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    // 시작: 저장된 기록을 불러오고 거리 누적을 시작한다
    fun start() {
        load()
        sessionStartMs = System.currentTimeMillis()
        running = true
        lastTickAt = SystemClock.elapsedRealtime()
        handler.postDelayed(tick, TICK_MS)
        onChanged(summary())
    }

    // 새 속도(km/h)를 알려준다. 거리 계산은 tick이 1초마다 이 값을 사용한다.
    fun setSpeed(kmh: Float) {
        latestSpeedKmh = kmh
    }

    // [4단계] 기어가 바뀔 때마다 알려준다. 주행의 시작/끝을 여기서 정한다.
    //  - P가 아닌 기어(R/N/D)가 되면: 열린 주행이 없을 때 새 주행 시작 (앱을 D 상태에서 켜도 이때 시작)
    //  - P가 되면: 열린 주행이 있을 때 주행 종료 -> 요약
    //  - 그 외 값(4/2/1/8이 아닌 것)은 무시
    // 차량 콜백은 다른 스레드에서 올 수 있어서 메인 스레드로 옮겨서 처리한다.
    fun onGear(gear: Int) {
        handler.post {
            val firstGear = !gearSeen
            gearSeen = true
            when (gear) {
                GEAR_P -> {
                    // 앱을 켜자마자 P인데 이전 실행에서 안 닫힌 주행이 있으면 "조용히" 닫는다(음성 요약 없음)
                    if (openTrip() != null) endTrip(silent = firstGear)
                }
                GEAR_R, GEAR_N, GEAR_D -> {
                    // 이미 열린 주행(이전 실행에서 이어짐)이 있으면 그대로 이어간다.
                    // 앱을 켜자마자 R/N/D(이미 달리는 중)였다면 시작 시각을 "앱을 켠 시각"으로 잡는다.
                    // (기어 값보다 속도 값이 먼저 와서 안내/이벤트가 주행 시작 시각보다 앞서 기록되는 경우를 막기 위해)
                    if (openTrip() == null) {
                        startTrip(if (firstGear) sessionStartMs else System.currentTimeMillis())
                        if (!firstGear) onTripStarted()   // 진짜 "출발" 순간에만 (앱을 이미 달리는 중에 켠 경우는 인사하지 않음)
                    }
                }
            }
        }
    }

    // 주행 시작: 새 주행을 열고 저장
    private fun startTrip(startMs: Long) {
        trips.add(Trip(startMs = startMs, endMs = startMs, distanceKm = 0.0, closed = false))
        save()
        onChanged(summary())
    }

    // 주행 종료: 닫고 저장한 뒤, silent가 아니면 결과를 알려준다
    private fun endTrip(silent: Boolean) {
        val trip = openTrip() ?: return
        trip.endMs = System.currentTimeMillis()
        trip.closed = true
        save()
        onChanged(summary())
        if (silent) { refreshStage(); return }   // 이전 실행에서 안 닫힌 주행을 조용히 닫는 경우: 요약도 졸업 판단 알림도 하지 않는다

        var result = resultOf(trip)
        // [5단계] 요약을 말할 만큼 긴 주행(0.1km 이상)일 때만 졸업 상태를 갱신하고 알린다
        if (trip.distanceKm >= MIN_TRIP_KM_FOR_SUMMARY) {
            result = result.copy(graduation = updateGraduation(result))
        } else {
            refreshStage()   // 요약하지 않는 짧은 주행도 단계는 다시 계산해 둔다 (짧은 주행의 실수가 연속을 끊을 수 있음)
        }
        onTripEnded(result)
    }

    // [5단계] 끝난 주행들로 졸업 상태를 다시 계산하고, 바뀐 점(단계 상승/졸업)을 저장한다
    private fun updateGraduation(justEnded: TripResult): GraduationUpdate {
        val prevLevel = coachLevelOf(stage)   // 이 주행 전의 코치 수준 (오른 경우만 안내하려고 저장)
        val closed = trips.filter { it.closed }.map { resultOf(it) }
        val status = evaluateGraduation(closed, alreadyGraduated = graduatedAtMs > 0L)
        stage = status.stage
        val newLevel = coachLevelOf(stage)
        val newlyGraduated = status.graduated && graduatedAtMs == 0L
        // 졸업 상태가 아니고, 이미 축하한 단계보다 높아졌을 때만 "단계 상승"
        val leveledUpTo = if (!status.graduated && status.stage > announcedStage) status.stage else null
        val lastTripClean = justEnded.mistakeCount == 0 && justEnded.distanceKm >= MIN_TRIP_KM_FOR_GRADUATION

        if (newlyGraduated) graduatedAtMs = System.currentTimeMillis()
        announcedStage = maxOf(announcedStage, minOf(status.stage, 3))
        save()
        return GraduationUpdate(status, leveledUpTo, newlyGraduated, lastTripClean,
            coachLevelUp = if (newLevel > prevLevel) newLevel else null)
    }

    // 이 주행 시간대에 기록된 이벤트를 세어서 결과로 만든다
    private fun resultOf(trip: Trip): TripResult = tripResultOf(trip, events)   // 공용 계산(DriveLogReader.kt)

    // 지금 열려 있는(주행 중인) 주행. 없으면 null
    private fun openTrip(): Trip? = trips.lastOrNull()?.takeIf { !it.closed }

    // 이벤트 기록 추가 (DriveCoach가 안내를 말하는 순간 호출). 이벤트는 바로 파일에 저장한다.
    // live = 실시간으로 말했는지. 조용한 코치 수준에서 말하지 않은 실수도 live=false로 기록한다.
    fun addEvent(type: String, speedKmh: Float, angleDeg: Float, live: Boolean = true) {
        events.add(DriveEvent(type, System.currentTimeMillis(), speedKmh, angleDeg, live))
        save()
        onChanged(summary())
    }

    // 같은 30초 안에 조용히 기록됐던 가장 최근 이벤트를 "말함"으로 바꾼다 (기록 후 위험 수준까지 올라가 말하게 된 경우, 이중 집계 방지)
    fun markLastEventLive(type: String) {
        val i = events.indexOfLast { it.type == type }
        if (i >= 0 && !events[i].live) {
            events[i] = events[i].copy(live = true)
            save()
        }
    }

    // 지금 코치 수준 (1 수다쟁이 / 2 위험할 때만 / 3 아주 위험할 때만). 단계는 주행이 끝날 때 바뀌므로 다음 주행부터 적용된다.
    fun coachLevel(): Int = coachLevelOf(stage)

    private fun refreshStage() {
        stage = evaluateGraduation(trips.filter { it.closed }.map { resultOf(it) }, alreadyGraduated = graduatedAtMs > 0L).stage
    }

    /** 화면(템플릿)을 그릴 때 필요한 값 묶음. 메인 스레드에서 호출한다. */
    data class UiSnapshot(
        val driving: Boolean,                 // 지금 주행 중인지 (열린 주행이 있음 = 기어가 P가 아님)
        val latest: TripResult?,              // 가장 최근 주행(주행 중이면 그 주행). 없으면 null
        val graduation: GraduationStatus,     // 졸업 진행 상태
    )

    fun uiSnapshot(): UiSnapshot {
        val closed = trips.filter { it.closed }.map { resultOf(it) }
        return UiSnapshot(
            driving = openTrip() != null,
            latest = trips.lastOrNull()?.let { resultOf(it) },
            graduation = evaluateGraduation(closed, alreadyGraduated = graduatedAtMs > 0L),
        )
    }

    // 화면에 보여줄 요약 글자 (가장 최근 주행 기준). 예: "주행 중 · 과속 2 / 급커브 1 · 3.42 km"
    fun summary(): String {
        val trip = trips.lastOrNull() ?: return "주행 기록 없음"
        val r = resultOf(trip)
        val state = if (trip.closed) "지난 주행" else "주행 중"
        return "$state · 과속 ${r.speedCount} / 급커브 ${r.curveCount} · ${"%.2f".format(trip.distanceKm)} km"
    }

    // 정리: 화면이 닫힐 때 호출. 마지막으로 저장한다.
    fun release() {
        running = false
        handler.removeCallbacksAndMessages(null)
        save()
    }

    // ---------- 파일 저장/불러오기 (JSON) ----------
    private fun save() {
        ticksSinceSave = 0
        try {
            val root = JSONObject()
            root.put("version", 1)
            root.put("events", JSONArray().also { arr ->
                events.forEach {
                    arr.put(JSONObject()
                        .put("type", it.type)
                        .put("timeMs", it.timeMs)
                        .put("speedKmh", it.speedKmh.toDouble())
                        .put("angleDeg", it.angleDeg.toDouble())
                        .put("live", it.live))
                }
            })
            root.put("graduation", JSONObject()
                .put("announcedStage", announcedStage)
                .put("graduatedAtMs", graduatedAtMs))
            root.put("trips", JSONArray().also { arr ->
                trips.forEach {
                    arr.put(JSONObject()
                        .put("startMs", it.startMs)
                        .put("endMs", it.endMs)
                        .put("distanceKm", it.distanceKm)
                        .put("closed", it.closed))
                }
            })
            // 저장 도중 앱이 꺼져도 파일이 깨지지 않게: 임시 파일에 먼저 쓰고 이름을 바꾼다
            val tmp = File(file.parentFile, "$LOG_FILE_NAME.tmp")
            tmp.writeText(root.toString(2))
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(2))
                tmp.delete()
            }
        } catch (e: Exception) {
            // 저장 실패는 앱을 죽이지 않고 무시한다 (다음 저장 때 다시 시도)
        }
    }

    private fun load() {
        events.clear()
        trips.clear()
        announcedStage = 1
        graduatedAtMs = 0L
        // 파일 읽기는 공용 함수(DriveLogReader.kt)를 쓴다. 파일이 없거나 깨졌으면 빈 기록으로 시작한다.
        val snap = readDriveLogFile(file)
        events.addAll(snap.events)
        trips.addAll(snap.trips)
        announcedStage = snap.announcedStage
        graduatedAtMs = snap.graduatedAtMs
        refreshStage()   // 저장된 기록으로 현재 단계(=코치 수준)를 정한다
    }
}
