package com.example.drivemate.vehicle

import ai.pleos.playground.navi.constants.NaviMode
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
            Log.i(TAG, "NAVI status=$status")   // 내비가 보내는 원본 값 (자주 오지 않아 그대로 남긴다)
            val routing = status == NavigationStatus.ROUTE_DRIVE
            _state.update { if (routing) it.copy(hasRoute = true) else NaviState() }
        }

        override fun onRouteStateInfo(info: RouteStateInfo) {
            // [버그 수정 2026-11-12] 경로 안내 중인지는 isRouting이 아니라 naviMode로 판단한다.
            // 에뮬레이터 로그 확인: 내비가 안내 중인데도 isRouting=false, naviMode=ROUTE_GUIDING 으로 온다.
            // (isRouting은 "경로를 계산하는 중"이라는 뜻으로 보인다)
            val guiding = info.naviMode == NaviMode.ROUTE_GUIDING || info.naviMode == NaviMode.SIMULATION_GUIDING
            logIfChanged("routeState", "NAVI routeState guiding=$guiding isRouting=${info.isRouting} mode=${info.naviMode}")
            if (guiding) _state.update { it.copy(hasRoute = true) } else _state.value = NaviState()
        }

        override fun onRouteStarted(info: RouteStartInfo) {
            Log.i(TAG, "NAVI routeStarted $info")
            _state.update { it.copy(hasRoute = true) }
            helper.getDestinationInfo()
        }

        override fun onRouteEnded() { _state.value = NaviState() }
        override fun onRouteCancelled() { _state.value = NaviState() }

        override fun onDestinationInfo(info: DestinationInfo) {
            logIfChanged("destination", "NAVI destination name=${info.name} remain=${info.remainDistance}m")
            val name = info.name.ifBlank { info.address }
            _state.update { it.copy(destination = name.ifBlank { null }) }
        }

        override fun onTBTInfo(list: List<TBTInfo>) {
            // 확인된 형식: distance 단위는 m, 첫 번째가 "바로 다음 안내"(현재 위치부터의 거리),
            // 두 번째부터는 "앞 안내 지점부터의 거리"다 (모두 더하면 남은 총 거리와 비슷하다).
            logIfChanged("tbt", "NAVI tbt " + list.take(3).joinToString(" | ") { "${it.type}/${it.distance.toInt()}m/${it.description}" } + " (총 ${list.size}개)")
            // 전체 경로의 회전 종류만 한 줄로 (고속도로 진입·합류 같은 종류가 오는지 확인용)
            logIfChanged("tbtTypes", "NAVI tbtTypes " + list.joinToString(",") { "${it.type.name.removePrefix("TURN_")}:${it.distance.toInt()}" })
            val first = list.firstOrNull()
            if (first == null) {
                _state.update { it.copy(next = null) }
                return
            }
            val type = first.type.toManeuverType()
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

    // 같은 내용이면 다시 찍지 않는 로그 (1초마다 오는 값이 로그를 가득 채우지 않게)
    private val lastLogs = mutableMapOf<String, String>()
    private fun logIfChanged(key: String, text: String) {
        if (lastLogs[key] == text) return
        lastLogs[key] = text
        Log.i(TAG, text)
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
 * 내비의 회전 종류를 DriveMate의 상황 종류로 바꾼다.
 * 좌·우회전, 직진(교차로), 고속도로 진입·합류만 코칭 대상이고 나머지는 OTHER.
 * 고속도로 진입(ENTER_IC)·분기 합류(DEVIDE_MERGE)는 김포공항 경로 로그로 실제로 오는 것을 확인 (2026-11-13)
 */
fun TurnType.toManeuverType(): ManeuverType = when (this) {
    TurnType.TURN_LEFT, TurnType.TURN_SHARP_L -> ManeuverType.LEFT
    TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> ManeuverType.RIGHT
    TurnType.TURN_STRAIGHT -> ManeuverType.INTERSECTION
    TurnType.TURN_ENTER_IC_L, TurnType.TURN_ENTER_IC_R, TurnType.TURN_ENTER_IC_C,
    TurnType.TURN_DEVIDE_MERGE -> ManeuverType.MERGE
    else -> ManeuverType.OTHER
}

/**
 * 테스트·시연용 데모 경로: 참가신청서 4번 시나리오 "A씨 마트 가는 길".
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

    /** 지금 쓰는 데모 경로 (기본: 이마트 성남점) */
    var route: DemoRoute = DemoRoute.EMART
        set(value) { field = value; traveledM = 0.0; publish() }

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
        val points = route.points
        val index = points.indexOfFirst { it.first > traveledM }
        val next = if (index < 0) null else {
            Maneuver(id = index + 1, type = points[index].second, distanceM = (points[index].first - traveledM).toInt())
        }
        _state.value = NaviState(hasRoute = true, destination = route.destination, next = next)
    }

    companion object {
        private const val STEP_MS = 500L

        /**
         * 에뮬레이터 내비의 실제 경로(성남 수정구 시흥동 -> 이마트 성남점, 추천 경로 7.2km)에서
         * NaviHelper가 보낸 다음 안내 목록을 그대로 옮긴 것 (2026-11-12 로그).
         * 각 줄 = (앞 안내 지점부터의 거리 m, 내비의 회전 종류). 에뮬레이터 내비는 움직이지 않아서
         * 이 거리를 차량 속도로 따라가며 남은 거리를 계산한다.
         */
        private val REAL_LEGS = listOf(
            80 to TurnType.TURN_LEFT,           // 기업지원허브 방면
            390 to TurnType.TURN_LEFT,          // 성남시청 방면
            1000 to TurnType.TURN_KEEP_R,       // 대왕판교로
            70 to TurnType.TURN_RIGHT,          // 성남시청 방면
            950 to TurnType.TURN_AVOID_OVER_R,  // 판교 방면
            360 to TurnType.TURN_LEFT,          // 송파 방면
            1100 to TurnType.TURN_RIGHT,        // 광주 방면
            330 to TurnType.TURN_ENTER_OVER_L,  // 성남중원경찰서·성남종합운동장 방면
            2100 to TurnType.TURN_LEFT,         // 성남시의료원 방면
            520 to TurnType.TURN_LEFT,          // 태평역 방면
            160 to TurnType.TURN_RIGHT,         // 이마트 방면
            10 to TurnType.TURN_ARRIVE,         // 도착
        )

        /**
         * 김포공항 경로(같은 출발점 -> 김포국제공항 국내선, 40km)의 앞부분 2.9km. 고속도로 진입(IC) 코칭 시연용.
         * NaviHelper 로그(2026-11-13) 그대로: 좌회전 80, 우회전 380, 고가 밑 우측 730, 우회전 1400, 고속도로 진입 200, 톨게이트 80
         */
        private val AIRPORT_LEGS = listOf(
            80 to TurnType.TURN_LEFT,
            380 to TurnType.TURN_RIGHT,
            730 to TurnType.TURN_AVOID_UNDER_R,
            1400 to TurnType.TURN_RIGHT,
            200 to TurnType.TURN_ENTER_IC_C,     // 고속도로 진입
            80 to TurnType.TURN_TOLL,
        )

        /** 앞 지점부터의 거리 목록을 "출발점부터의 누적 거리"로 바꾼다 */
        fun cumulative(legs: List<Pair<Int, TurnType>>): List<Pair<Int, ManeuverType>> = legs
            .runningFold(0 to ManeuverType.OTHER) { acc, (leg, turn) -> (acc.first + leg) to turn.toManeuverType() }
            .drop(1)

        /** 이마트 성남점 경로의 누적 거리 (총 7,070m) */
        val ROUTE: List<Pair<Int, ManeuverType>> = cumulative(REAL_LEGS)
        val AIRPORT_ROUTE: List<Pair<Int, ManeuverType>> = cumulative(AIRPORT_LEGS)
    }
}

/** 데모 경로 목록 */
enum class DemoRoute(val destination: String) {
    EMART("이마트 성남점 (데모 경로)"),
    AIRPORT("김포공항 고속도로 진입 (데모 경로)");

    val points: List<Pair<Int, ManeuverType>>
        get() = if (this == AIRPORT) ScenarioNaviSource.AIRPORT_ROUTE else ScenarioNaviSource.ROUTE
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

    fun setDemo(enabled: Boolean, route: DemoRoute? = null) {
        if (route != null) scenario.route = route
        if (enabled) scenario.start()
        demo.value = enabled
    }

    override fun onTripStart() {
        if (demo.value) scenario.onTripStart()
    }

    override fun openNavi() = pleos.openNavi()
}
