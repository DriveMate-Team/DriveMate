package com.example.drivemate.vehicle

import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.drivemate.model.Maneuver
import com.example.drivemate.model.ManeuverType
import com.example.drivemate.model.NaviState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 내비 정보(목적지, 다음 회전)를 주는 통로.
 * 원칙: 길 안내는 차량 내비가 한다. DriveMate는 내비 정보를 "읽기만" 한다.
 */
interface NaviSource {
    val state: StateFlow<NaviState>

    /** 내비 정보 읽기를 시작한다 (여러 번 불러도 한 번만 시작됨) */
    fun start()

    /** 주행이 시작될 때 불린다 (데모 경로는 여기서 처음 위치로 되돌아간다) */
    fun onTripStart()

    /** 차량 내비 화면을 연다 ("내비에서 목적지를 설정해 주세요" 버튼) */
    fun openNavi()
}

/**
 * Pleos NaviHelper SDK로 실제 차량 내비의 정보를 읽는 구현.
 *
 * 아직 실제 경로 안내로는 확인하지 못한 가정 (에뮬레이터에서 경로 안내를 띄워 확인 필요):
 *  - TBTInfo.distance 의 단위는 m
 *  - TBT 목록의 첫 번째가 "바로 다음 안내"
 *  - TURN_STRAIGHT(직진 안내)를 교차로로 본다 (NaviHelper에는 "교차로" 정보가 따로 없다)
 */
@Singleton
class PleosNaviSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope,
) : NaviSource {

    private val _state = MutableStateFlow(NaviState())
    override val state: StateFlow<NaviState> = _state

    private val helper = NaviHelper(context)
    private var started = false
    private var ready = false

    // 같은 회전인지 구분하는 번호를 만들기 위한 기억 (NaviHelper는 회전에 번호를 주지 않는다)
    private var maneuverId = 0
    private var lastType: ManeuverType? = null
    private var lastDistance = 0

    private val listener = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) {
            val routing = status == NavigationStatus.ROUTE_DRIVE
            _state.update { if (routing) it.copy(hasRoute = true) else NaviState() }
        }

        override fun onRouteStateInfo(info: RouteStateInfo) {
            if (info.isRouting) _state.update { it.copy(hasRoute = true) } else _state.value = NaviState()
        }

        override fun onRouteStarted(info: RouteStartInfo) {
            _state.update { it.copy(hasRoute = true) }
            helper.getDestinationInfo()
        }

        override fun onRouteEnded() { _state.value = NaviState() }
        override fun onRouteCancelled() { _state.value = NaviState() }

        override fun onDestinationInfo(info: DestinationInfo) {
            val name = info.name.ifBlank { info.address }
            _state.update { it.copy(destination = name.ifBlank { null }) }
        }

        override fun onTBTInfo(list: List<TBTInfo>) {
            val first = list.firstOrNull()
            if (first == null) {
                _state.update { it.copy(next = null) }
                return
            }
            val type = when (first.type) {
                TurnType.TURN_LEFT, TurnType.TURN_SHARP_L -> ManeuverType.LEFT
                TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> ManeuverType.RIGHT
                TurnType.TURN_STRAIGHT -> ManeuverType.INTERSECTION
                else -> ManeuverType.OTHER
            }
            val distance = first.distance.toInt()
            // 종류가 바뀌었거나 남은 거리가 갑자기 늘었으면 "다음 회전으로 넘어간 것"으로 본다
            if (type != lastType || distance > lastDistance + 50) maneuverId++
            lastType = type
            lastDistance = distance
            _state.update { it.copy(next = Maneuver(maneuverId, type, distance)) }
        }
    }

    override fun start() {
        if (started) return
        started = true
        try {
            helper.initialize(
                {
                    ready = true
                    helper.addListener(listener)
                    startPolling()
                },
                { error -> Log.w(TAG, "NaviHelper 초기화 실패: ${error.message}") },
            )
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper 오류: ${e.message}")
        }
    }

    // 내비에 "경로 상태 / 다음 안내"를 물어본다 (답은 위 listener로 온다).
    // 경로 안내 중에는 1초마다 다음 안내를 묻고, 경로가 없을 때는 5초에 한 번만 상태를 묻는다 (불필요한 호출 줄이기).
    private fun startPolling() {
        scope.launch {
            var tick = 0
            while (true) {
                try {
                    if (tick % 5 == 0) helper.getRouteStateInfo()
                    if (_state.value.hasRoute) {
                        helper.getTBTInfo()
                        if (tick % 10 == 0) helper.getDestinationInfo()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "NaviHelper 조회 오류: ${e.message}")
                }
                tick++
                delay(1000)
            }
        }
    }

    override fun onTripStart() {}

    override fun openNavi() {
        try {
            if (ready) {
                helper.requestNavigationScreen(NavigationScreenRequest.Foreground)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "내비 화면 요청 실패: ${e.message}")
        }
        // NaviHelper로 못 열면 "지도 앱 열기" 일반 요청으로 시도한다
        try {
            context.startActivity(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "지도 앱을 열 수 없음: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "DriveMate"
    }
}

/**
 * 테스트·시연용 가짜 경로: 참가신청서 4번 시나리오 "A씨 마트 가는 길".
 *
 * 에뮬레이터에는 실제 주행(위치 이동)이 없어서, 차량 속도를 적분해 "달린 거리"를 만들고
 * 미리 정해 둔 지점까지의 남은 거리를 계산한다. 속도는 Virtual Console(tools/vc.js)로 바꾼다.
 */
@Singleton
class ScenarioNaviSource @Inject constructor(
    private val vehicle: VehicleSource,
    private val scope: CoroutineScope,
) : NaviSource {

    private val _state = MutableStateFlow(NaviState())
    override val state: StateFlow<NaviState> = _state

    private var traveledM = 0.0
    private var job: Job? = null

    override fun start() {
        if (job != null) return
        publish()
        job = scope.launch {
            var last = System.currentTimeMillis()
            while (true) {
                delay(STEP_MS)
                val now = System.currentTimeMillis()
                // 거리 = 속도(m/s) × 시간(초)
                traveledM += vehicle.state.value.speedKmh / 3.6 * (now - last) / 1000.0
                last = now
                publish()
            }
        }
    }

    override fun onTripStart() {
        traveledM = 0.0
        publish()
    }

    override fun openNavi() {}

    private fun publish() {
        val index = ROUTE.indexOfFirst { it.first > traveledM }
        val next = if (index < 0) null else {
            Maneuver(id = index + 1, type = ROUTE[index].second, distanceM = (ROUTE[index].first - traveledM).toInt())
        }
        _state.value = NaviState(hasRoute = true, destination = DESTINATION, next = next)
    }

    companion object {
        const val DESTINATION = "동네 마트 (데모 경로)"
        private const val STEP_MS = 500L

        /** 출발점에서부터의 거리(m)와 그 지점의 상황. 총 2km */
        val ROUTE = listOf(
            400 to ManeuverType.RIGHT,          // 아파트 단지를 나와 우회전
            800 to ManeuverType.INTERSECTION,   // 큰 교차로 직진
            1300 to ManeuverType.LEFT,          // 좌회전
            1700 to ManeuverType.RIGHT,         // 마트 진입 우회전
        )
    }
}

/**
 * 앱이 실제로 쓰는 내비 통로: 평소에는 실제 내비(Pleos), "데모 경로"를 켜면 가짜 경로로 바꿔 준다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SwitchingNaviSource @Inject constructor(
    private val pleos: PleosNaviSource,
    private val scenario: ScenarioNaviSource,
    scope: CoroutineScope,
) : NaviSource {

    /** 데모 경로를 쓰는 중인지 */
    val demo = MutableStateFlow(false)

    override val state: StateFlow<NaviState> =
        demo.flatMapLatest { if (it) scenario.state else pleos.state }
            .stateIn(scope, SharingStarted.Eagerly, NaviState())

    override fun start() = pleos.start()

    fun setDemo(enabled: Boolean) {
        if (enabled) scenario.start()
        demo.value = enabled
    }

    override fun onTripStart() {
        if (demo.value) scenario.onTripStart()
    }

    override fun openNavi() = pleos.openNavi()
}
