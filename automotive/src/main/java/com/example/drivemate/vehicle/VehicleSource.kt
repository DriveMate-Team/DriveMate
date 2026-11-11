package com.example.drivemate.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.example.drivemate.model.Gear
import com.example.drivemate.model.TurnSignal
import com.example.drivemate.model.VehicleState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 차량 값(속도·기어·방향지시등·브레이크)을 주는 통로.
 * 앱의 다른 부분은 이 통로만 보고, 실제로 어떤 SDK로 읽는지는 몰라도 된다.
 */
interface VehicleSource {
    /** 현재 차량 값. 값이 바뀔 때마다 새 값이 흘러나온다 */
    val state: StateFlow<VehicleState>

    /** 차량 값 읽기를 시작한다 (권한 확인이 끝난 뒤에 부른다. 여러 번 불러도 한 번만 시작됨) */
    fun start()
}

/**
 * 실제 차량(에뮬레이터)에서 값을 읽는 구현.
 *  - 속도·기어: AAOS 표준 CarPropertyManager (Pleos 기획 가이드: 표준 데이터는 AAOS Car API 사용)
 *  - 방향지시등·브레이크: Pleos Vehicle SDK
 *
 * 에뮬레이터로 확인된 사실: 속도 단위는 m/s(×3.6 = km/h), 기어는 GEAR_SELECTION 값 P=4, R=2, N=1, D=8.
 */
@Singleton
class PleosVehicleSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : VehicleSource {

    private val _state = MutableStateFlow(VehicleState())
    override val state: StateFlow<VehicleState> = _state

    private var started = false
    private var car: Car? = null
    private val vehicle = Vehicle(context)

    // 속도/기어 값이 바뀔 때마다 불리는 수신기
    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    val metersPerSecond = value.value as? Float ?: return
                    // 후진이면 음수로 올 수 있어 절댓값을 쓰고, 소수 첫째 자리까지만 쓴다 (아주 작은 오차 제거)
                    val kmh = Math.round(kotlin.math.abs(metersPerSecond) * 3.6f * 10) / 10f
                    _state.update { it.copy(speedKmh = kmh) }
                }
                VehiclePropertyIds.GEAR_SELECTION -> {
                    val gear = when (value.value as? Int) {
                        4 -> Gear.P; 2 -> Gear.R; 1 -> Gear.N; 8 -> Gear.D; else -> Gear.UNKNOWN
                    }
                    _state.update { it.copy(gear = gear) }
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "차량 값 오류 propertyId=$propertyId")
        }
    }

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            val signal = when (state) {
                TurnSignalValue.LEFT -> TurnSignal.LEFT
                TurnSignalValue.RIGHT -> TurnSignal.RIGHT
                else -> TurnSignal.OFF
            }
            _state.update { it.copy(turnSignal = signal) }
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "방향지시등 읽기 실패: ${e.message}")
        }
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
            _state.update { it.copy(brake = pressed == true) }
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "브레이크 읽기 실패: ${e.message}")
        }
    }

    override fun start() {
        if (started) return
        started = true

        // 속도·기어 (한쪽이 권한/지원 문제로 실패해도 다른 쪽은 계속 동작하도록 따로 처리)
        try {
            val newCar = Car.createCar(context)
            car = newCar
            val manager = newCar.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            subscribe(manager, VehiclePropertyIds.PERF_VEHICLE_SPEED)
            subscribe(manager, VehiclePropertyIds.GEAR_SELECTION)
        } catch (e: Exception) {
            Log.w(TAG, "차량 연결 오류: ${e.message}")
        }

        // 방향지시등·브레이크
        try {
            vehicle.initialize()
            vehicle.getTurnSignal().registerTurnSignalState(turnSignalListener)
            vehicle.getBrake().registerBrakePedalStatus(brakeListener)
        } catch (e: Exception) {
            Log.w(TAG, "Vehicle SDK 오류: ${e.message}")
        }
    }

    private fun subscribe(manager: CarPropertyManager, propertyId: Int) {
        try {
            val ok = manager.registerCallback(propertyCallback, propertyId, CarPropertyManager.SENSOR_RATE_UI)
            if (!ok) Log.w(TAG, "구독 실패(지원 안 함?) propertyId=$propertyId")
        } catch (e: Exception) {
            Log.w(TAG, "구독 오류 propertyId=$propertyId: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "DriveMate"
    }
}
