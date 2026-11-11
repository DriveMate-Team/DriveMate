package com.example.drivemate.shared

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 차량에서 읽은 "원본 값"과 코치 상태를 모아 두는 곳. 운전자에게는 보이지 않고, 정차 중에 여는 "개발자 정보" 화면에서만 보인다.
 */
object LiveVehicleState {
    @Volatile var speed = "대기 중..."          // 예: "원본 12.5 m/s = 45.0 km/h"
    @Volatile var gear = "대기 중..."           // 예: "8 (D)"
    @Volatile var steering = "대기 중..."       // 예: "원본 -180.0 (단위: 도, 부호는 반대로 나옴)"
    @Volatile var coach = "TTS 대기 중..."      // 예: "TTS 준비됨 (발화 3441ms) / 마지막 안내: ..."
    @Volatile var log = "기록 불러오는 중..."    // 예: "지난 주행 · 과속 1 / 급커브 1 · 0.79 km"
}

/**
 * DriveMate의 "두뇌": 차량 연결(속도·기어·조향각·벨트·문), 주행 기록, 음성 코치를 한곳에서 관리한다.
 * 화면(Activity)과 분리되어 있어서 화면이 어떻게 생겼든(직접 만든 UI든 템플릿이든) 같은 두뇌를 쓴다.
 *
 * 사용법: 생성 -> start() -> (권한이 허용되면) startCarProperty() -> 화면은 uiSnapshot()/cautionNow()를 읽어서 그린다 -> release()
 *
 * @param onChanged 화면을 다시 그려야 할 때 메인 스레드에서 불린다 (너무 자주 불리지 않게 0.25초 이내 중복은 합친다)
 * @param onTripSummary 주행이 끝나서(기어 P) 요약을 보여줄 때 불린다 (0.1km 미만의 짧은 주행은 불리지 않음)
 */
class DriveEngine(
    context: Context,
    private val onChanged: () -> Unit,
    private val onTripSummary: (TripResult) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    // 화면 갱신 요청을 0.25초 단위로 합친다 (센서 값이 초당 여러 번 와도 화면은 부드럽게 4번까지만)
    private var notifyPending = false
    private var lastNotify = 0L

    // ---------- "주의" 표시 상태 ----------
    // 코치가 경고(과속/급커브)를 말하면 잠시(CAUTION_SECONDS초) 화면을 노랑 "주의"로 바꿨다가 돌아온다.
    private var cautionText: String? = null
    private var cautionUntil = 0L

    // ---------- 차량과 연결하는 객체들 ----------
    private val vehicle = Vehicle(appContext)               // Pleos Vehicle SDK: 조향각, 안전벨트, 문
    private var car: Car? = null                            // AAOS: 속도/기어
    private var propertyManager: CarPropertyManager? = null
    private var released = false

    private val store = DriveEventStore(
        appContext,
        onChanged = { text -> LiveVehicleState.log = text; notifyUi() },
        onTripEnded = { result -> handleTripEnded(result) },
        onTripStarted = { announceDeparture() },
    )

    private val coach = DriveCoach(
        appContext, store,
        onStatus = { text -> LiveVehicleState.coach = text },
        onAlert = { eventType -> showCaution(eventType) },
    )

    // 속도/기어 값이 바뀔 때마다 호출되는 "수신기(콜백)"
    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    // 속도 원본값의 단위는 m/s (에뮬레이터로 확인). km/h로 바꾸려면 3.6을 곱한다.
                    val raw = value.value as? Float
                    val kmh = (raw ?: 0f) * 3.6f
                    LiveVehicleState.speed = "원본 $raw m/s = ${"%.1f".format(kmh)} km/h"
                    if (raw != null) coach.onSpeed(kmh)
                }
                VehiclePropertyIds.GEAR_SELECTION -> {
                    val gear = value.value as? Int
                    LiveVehicleState.gear = "${value.value} (${gearLetter(gear)})"
                    if (gear != null) store.onGear(gear)   // 기어 변화로 주행 시작/끝을 판단
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            val msg = "오류 (areaId=$areaId)"
            if (propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) LiveVehicleState.speed = msg else LiveVehicleState.gear = msg
        }
    }

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            LiveVehicleState.steering = "원본 $angle (단위: 도, 부호는 반대로 나옴)"
            if (angle != null) coach.onSteering(angle)   // 코치는 절댓값만 사용
        }

        override fun onFailed(e: Exception) {
            LiveVehicleState.steering = "실패 (${e.message})"
        }
    }

    // 기어 숫자를 글자로: 에뮬레이터 테스트로 확인된 값 P=4, R=2, N=1, D=8
    private fun gearLetter(gear: Int?): String = when (gear) {
        4 -> "P"; 2 -> "R"; 1 -> "N"; 8 -> "D"; else -> "?"
    }

    /** 저장된 기록을 불러오고, 음성 코치와 조향각 읽기를 시작한다. (속도/기어는 권한이 허용된 뒤 startCarProperty()) */
    fun start() {
        store.start()
        coach.start()
        vehicle.initialize()
        vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
    }

    /** 속도/기어 구독을 시작한다 (CAR_SPEED 등 권한이 허용된 뒤에 호출) */
    fun startCarProperty() {
        if (released || propertyManager != null) return
        try {
            val newCar = Car.createCar(appContext)
            car = newCar
            val manager = newCar.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = manager
            // 한쪽이 권한/지원 문제로 실패해도 다른 쪽은 계속 동작하도록 따로 처리한다.
            subscribe(manager, VehiclePropertyIds.PERF_VEHICLE_SPEED)
            subscribe(manager, VehiclePropertyIds.GEAR_SELECTION)
        } catch (e: Exception) {
            val msg = "연결 오류 ${e.message}"
            LiveVehicleState.speed = msg; LiveVehicleState.gear = msg
        }
    }

    private fun subscribe(manager: CarPropertyManager, propertyId: Int) {
        val error: String? = try {
            val ok = manager.registerCallback(propertyCallback, propertyId, CarPropertyManager.SENSOR_RATE_UI)
            if (ok) null else "구독 실패(지원 안 함?)"
        } catch (e: SecurityException) {
            "권한 없음 (${e.message})"
        } catch (e: Exception) {
            "오류 (${e.message})"
        }
        if (error != null) {
            if (propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) LiveVehicleState.speed = error else LiveVehicleState.gear = error
        }
    }

    /** 화면이 닫힐 때 모든 연결을 정리한다 */
    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        try { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) } catch (_: Exception) {}
        store.release()
        coach.release()
        try { vehicle.release() } catch (_: Exception) {}
        propertyManager?.unregisterCallback(propertyCallback)
        car?.disconnect()
    }

    // ---------- 화면이 읽는 값 ----------

    /** 지금 화면을 그리는 데 필요한 값 묶음 (주행 중인지, 최근 주행, 졸업 진행) */
    fun uiSnapshot(): DriveEventStore.UiSnapshot = store.uiSnapshot()

    /** 지금 "주의" 표시 중이면 그 문구, 아니면 null */
    fun cautionNow(): String? = if (SystemClock.elapsedRealtime() < cautionUntil) cautionText else null

    /** 지금 코치 수준 (1 수다쟁이 / 2 위험할 때만 / 3 아주 위험할 때만) */
    fun coachLevel(): Int = store.coachLevel()

    // ---------- 내부 처리 ----------

    // 출발 순간: 운전석 안전벨트와 문 상태를 확인하고 인사와 함께 말한다 (문구는 DepartureCheck.kt)
    private fun announceDeparture() {
        DepartureCheck(vehicle).check { status -> coach.speakAnswer(departureMessage(status)) }
    }

    // 주행이 끝났을 때: 너무 짧은 주행은 건너뛰고, 아니면 음성으로 요약을 말하고 화면에 요약을 띄우라고 알린다.
    private fun handleTripEnded(result: TripResult) {
        if (result.distanceKm < MIN_TRIP_KM_FOR_SUMMARY) { notifyUi(); return }   // 0.1km 미만이면 요약하지 않음 (기록은 남음)
        val graduationSentence = result.graduation?.let { graduationSpoken(it) }
        coach.speakSummary(listOfNotNull(buildSpokenSummary(result), graduationSentence).joinToString(" "))
        onTripSummary(result)
        notifyUi()
    }

    // 코치가 경고를 말한 순간: 화면을 CAUTION_SECONDS초 동안 노랑 "주의"로 바꾼다
    private fun showCaution(eventType: String) {
        cautionText = if (eventType == EVENT_CURVE) CAUTION_CURVE else CAUTION_SPEED
        cautionUntil = SystemClock.elapsedRealtime() + CAUTION_SECONDS * 1000L
        notifyUi()
        handler.postDelayed({ notifyUi() }, CAUTION_SECONDS * 1000L + 300L)   // 표시 시간이 끝나면 다시 그린다
    }

    // 화면 다시 그리기 요청 (0.25초 안에 여러 번 와도 한 번만)
    private fun notifyUi() {
        handler.post {
            if (released || notifyPending) return@post
            notifyPending = true
            val wait = maxOf(0L, 250L - (SystemClock.elapsedRealtime() - lastNotify))
            handler.postDelayed({
                notifyPending = false
                lastNotify = SystemClock.elapsedRealtime()
                if (!released) onChanged()
            }, wait)
        }
    }
}
