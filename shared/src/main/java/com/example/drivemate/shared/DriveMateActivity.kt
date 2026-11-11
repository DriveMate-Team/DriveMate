package com.example.drivemate.shared

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * DriveMate의 유일한 화면(Activity). 템플릿(Car App Library) 없이 DriveMateUi가 Views로 직접 그린다.
 * 차량 연결·기록·코치는 DriveEngine이 맡고, 이 Activity는 "엔진 상태를 화면에 그리는 일"과 "버튼 처리"만 한다.
 *
 * 운전 중에는 터치할 곳이 없다 (버튼은 정차 화면에만). 매니페스트의 distractionOptimized 선언으로 운전 중에도 표시된다.
 */
class DriveMateActivity : Activity() {

    private var engine: DriveEngine? = null
    private var ui: DriveMateUi? = null

    // 화면이 들고 있는 상태 (엔진이 모르는 것들)
    private var summary: TripResult? = null     // 방금 끝난 주행 -> 확인 버튼을 누를 때까지 요약/졸업 화면
    private var devOpen = false                 // 개발자 정보 화면을 열었는지
    private val handler = Handler(Looper.getMainLooper())
    private val devRefresh = object : Runnable {   // 개발자 정보를 열어 둔 동안 1초마다 값을 새로 그린다
        override fun run() {
            if (devOpen) { render(); handler.postDelayed(this, 1000L) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        engine = DriveEngine(
            this,
            onChanged = { requestRender() },
            onTripSummary = { result -> summary = result; devOpen = false; requestRender() },
        ).also { it.start() }
        requestCarPermissions()
        render()
    }

    // 화면(Views)을 새로 만든다 (처음, 그리고 주야간 모드가 바뀔 때)
    private fun buildUi() {
        ui = DriveMateUi(
            this,
            onDismiss = { summary = null; render() },
            onOpenDev = { devOpen = true; render(); handler.postDelayed(devRefresh, 1000L) },
            onCloseDev = { devOpen = false; render() },
        )
        setContentView(ui!!.root)
    }

    // 화면 갱신은 최소 1초 간격으로 묶는다 (엔진이 센서 값마다 알려 와도 화면은 초당 한 번까지만 그린다).
    // 버튼을 눌렀을 때(확인/개발자 정보/뒤로)는 바로 반응해야 해서 render()를 직접 부른다.
    private var lastRender = 0L
    private var renderScheduled = false
    private fun requestRender() {
        val wait = RENDER_MIN_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastRender)
        if (wait <= 0L) render()
        else if (!renderScheduled) {
            renderScheduled = true
            handler.postDelayed({ renderScheduled = false; render() }, wait)
        }
    }

    // 엔진의 현재 상태를 화면에 그린다
    private fun render() {
        lastRender = SystemClock.elapsedRealtime()
        val e = engine ?: return
        val snap = e.uiSnapshot()
        if (snap.driving) { summary = null; devOpen = false }   // 출발하면 요약/개발자 화면을 닫고 운전 화면으로
        ui?.render(UiState(snap, e.cautionNow(), e.coachLevel(), summary, devOpen))
    }

    // ---------- 권한: 속도/기어는 AAOS 권한이 필요하다 (허용된 뒤에 구독 시작) ----------
    private fun requestCarPermissions() {
        val perms = arrayOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN")
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) engine?.startCarProperty()
        else requestPermissions(perms, REQUEST_CAR)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAR && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            engine?.startCarProperty()   // 하나라도 허용됐으면 읽기 시작 (거부된 값은 개발자 정보에 오류로 남는다)
        }
    }

    // 주야간 모드/화면 크기가 바뀌면 화면을 새로 만든다 (Activity를 다시 만들지 않으므로 엔진과 기록은 그대로)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
        render()
    }

    // 뒤로가기: 열린 화면을 먼저 닫고, 맨 처음 화면에서는 앱을 종료하지 않고 뒤로 보낸다 (종료하면 주행 기록이 멈춘다)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            devOpen -> { devOpen = false; render() }
            summary != null -> { summary = null; render() }
            else -> moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine?.release()
        engine = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CAR = 1
        private const val RENDER_MIN_INTERVAL_MS = 1000L   // 화면 갱신 최소 간격(1초)
    }
}
