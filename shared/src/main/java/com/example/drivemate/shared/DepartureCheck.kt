package com.example.drivemate.shared

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import android.os.Handler
import android.os.Looper
import android.util.Log

// ====================================================================================
// [출발 인사] 기어가 P에서 벗어나는 출발 순간에 하는 인사 + 안전벨트/문 확인 -- 문구는 "이 부분만" 고치면 된다.
// ====================================================================================
const val DEPARTURE_GREETING = "오늘도 같이 갈게요."                 // 항상 먼저 말하는 인사
const val DEPARTURE_BELT_WARNING = " 안전벨트부터 확인해요."           // 안전벨트를 안 맸을 때
const val DEPARTURE_BELT_OK = " 안전벨트도 잘 맸네요, 좋아요!"        // 안전벨트를 맸을 때 (칭찬)
const val DEPARTURE_DOOR_OPEN_SUFFIX = " 문이 열려 있어요. 확인해 주세요."   // 열린 문이 있을 때: "조수석" + 이 문구 = "조수석 문이 열려 있어요. 확인해 주세요."
const val DEPARTURE_CHECK_TIMEOUT_MS = 2000L                        // 이 시간(ms) 안에 못 받은 확인 결과는 "모름"으로 처리한다

// 확인할 문 목록: (영역 값, 말할 이름). 문이 없는 차종이면 그 문은 오류가 나서 그냥 건너뛴다
private val DOORS = listOf(
    DoorValue.ROW_1_LEFT to "운전석",
    DoorValue.ROW_1_RIGHT to "조수석",
    DoorValue.ROW_2_LEFT to "뒷좌석 왼쪽",
    DoorValue.ROW_2_RIGHT to "뒷좌석 오른쪽",
)
// ====================================================================================

/**
 * 출발 순간의 차량 상태.
 * @param beltNotBuckled true = 안전벨트 미착용, false = 착용, null = 확인 못 함
 * @param openDoors      열려 있는 문 이름 목록 (모두 닫혔으면 빈 목록, 확인 못 했으면 null이 아니라 확인된 문만 담음)
 */
data class DepartureStatus(val beltNotBuckled: Boolean?, val openDoors: List<String>)

/**
 * 출발할 때 말할 문장을 만든다.
 *  - 항상 인사로 시작
 *  - 벨트: 미착용이면 확인 요청, 착용이면 칭찬, 모르면 아무 말도 안 함 (틀린 말을 하지 않으려고)
 *  - 문: 열린 문이 있을 때만 이름과 함께 알려 준다. 모두 닫혔으면 말하지 않는다(말이 길어지지 않게)
 */
fun departureMessage(status: DepartureStatus): String {
    val belt = when (status.beltNotBuckled) {
        true -> DEPARTURE_BELT_WARNING
        false -> DEPARTURE_BELT_OK
        null -> ""
    }
    val door = if (status.openDoors.isNotEmpty()) " " + status.openDoors.joinToString(", ") + DEPARTURE_DOOR_OPEN_SUFFIX else ""
    return DEPARTURE_GREETING + belt + door
}

/**
 * 출발 순간에 운전석 안전벨트와 문 상태를 물어보는 도우미 (Vehicle SDK, 권한 CAR_SAFETY/CAR_SEATS/CAR_DOORS).
 * 모든 답이 오거나 시간이 지나면(2초) 메인 스레드에서 딱 한 번 결과를 알려 준다.
 */
class DepartureCheck(private val vehicle: Vehicle) {
    private val handler = Handler(Looper.getMainLooper())

    fun check(onResult: (DepartureStatus) -> Unit) {
        var done = false
        var beltNotBuckled: Boolean? = null
        val openDoors = mutableListOf<String>()
        var pending = 1 + DOORS.size   // 기다리는 답의 개수: 벨트 1개 + 문 4개

        // 결과를 한 번만 전달한다. (SDK 콜백은 다른 스레드에서 올 수 있어 모두 메인 스레드로 옮겨서 처리한다)
        fun deliver() {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            onResult(DepartureStatus(beltNotBuckled, openDoors.toList()))
        }
        // 답 하나가 도착했다(성공이든 실패든). 전부 도착하면 바로 전달한다.
        fun answered() {
            handler.post {
                pending--
                if (pending <= 0) deliver()
            }
        }
        handler.postDelayed({ deliver() }, DEPARTURE_CHECK_TIMEOUT_MS)   // 안전장치: 못 받은 답은 "모름"으로 두고 진행

        // ---------- 안전벨트 (운전석 = 1열 왼쪽, 좌핸들 기준) ----------
        val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

        // 2차 시도: 차종에 따라 "미착용 경고"를 지원하지 않을 수 있어서, 같은 정보를 주는 "벨트 착용 상태"로 다시 물어본다.
        fun askBuckledState() {
            try {
                vehicle.getSeat().getCurrentSeatBeltBuckledState(
                    area = driverSeat,
                    onComplete = { buckled: Boolean? ->
                        Log.i(TAG, "안전벨트 착용 상태=$buckled")
                        handler.post { beltNotBuckled = buckled?.let { !it } }
                        answered()
                    },
                    onFailed = { e: Exception ->
                        Log.w(TAG, "안전벨트 착용 상태 확인도 실패: $e")
                        answered()   // 둘 다 안 되면 모름(null)으로 둔다
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "안전벨트 착용 상태 확인 중 예외: $e")
                answered()
            }
        }
        // 1차 시도: 안전벨트 미착용 경고
        try {
            vehicle.getSafety().hasSeatBeltNotBuckledWarning(
                area = driverSeat,
                onComplete = { warning: Boolean? ->
                    Log.i(TAG, "안전벨트 미착용 경고=$warning")
                    handler.post { beltNotBuckled = warning }
                    answered()
                },
                onFailed = { e: Exception ->
                    Log.w(TAG, "안전벨트 미착용 경고 확인 실패 -> 착용 상태로 다시 시도: $e")
                    askBuckledState()
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "안전벨트 확인 중 예외 -> 착용 상태로 다시 시도: $e")
            askBuckledState()
        }

        // ---------- 문 (4개 각각 물어본다) ----------
        for ((value, name) in DOORS) {
            try {
                vehicle.getDoor().getCurrentDoorPosition(
                    area = DoorArea(value),
                    onComplete = { position: Int? ->
                        Log.i(TAG, "문 $name 위치 원본값=$position")   // 값의 의미를 확인하려고 원본값을 남긴다
                        handler.post { if (isDoorOpen(position)) openDoors.add(name) }
                        answered()
                    },
                    onFailed = { e: Exception ->
                        Log.w(TAG, "문 $name 확인 실패(없는 문일 수 있음): $e")
                        answered()
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "문 $name 확인 중 예외: $e")
                answered()
            }
        }
    }

    // 문 위치 값이 "열림"인지 판단한다. (에뮬레이터로 확인: 닫힘 = 0, 열림 = 1 -> 0보다 크면 열림)
    private fun isDoorOpen(position: Int?): Boolean = position != null && position > 0

    companion object {
        private const val TAG = "DMDepart"
    }
}
