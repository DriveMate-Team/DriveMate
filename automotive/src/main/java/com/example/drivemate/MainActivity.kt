package com.example.drivemate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.drivemate.drive.DriveSession
import com.example.drivemate.ui.AppNav
import com.example.drivemate.ui.theme.DriveMateTheme
import com.example.drivemate.vehicle.DemoRoute
import com.example.drivemate.vehicle.SwitchingNaviSource
import com.example.drivemate.vehicle.VehicleSource
import com.example.drivemate.voice.SpeechOutput
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * DriveMate의 유일한 Activity. 화면 5개는 AppNav(Compose) 안에서 바뀐다.
 * 여기서는 ① 권한을 묻고 ② 차량·내비·음성 연결을 시작하는 일만 한다.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // @Inject: Hilt가 알맞은 객체를 자동으로 넣어 준다 (di/AppModule.kt 참고)
    @Inject lateinit var vehicle: VehicleSource
    @Inject lateinit var navi: SwitchingNaviSource
    @Inject lateinit var speechOut: SpeechOutput
    @Inject lateinit var session: DriveSession

    // 권한 팝업의 결과를 받는 곳. 허용이든 거부든 연결을 시작한다 (거부된 값만 못 읽고 나머지는 동작한다)
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startSources() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DriveMateTheme { AppNav() }
        }

        val missing = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startSources() else permissionLauncher.launch(missing.toTypedArray())
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    private fun startSources() {
        vehicle.start()
        navi.start()
        speechOut.start()
    }

    /**
     * 테스트 자동화용 (디버그 빌드에서만 동작). 에뮬레이터에서 마이크 없이 질문 답변을 확인할 때 쓴다.
     *   adb shell am start -n com.example.drivemate/.MainActivity --ez demo true      데모 경로 켜기
     *   adb shell am start -n com.example.drivemate/.MainActivity --es ask "비보호"     질문 문장 넣기
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        // --es demoRoute AIRPORT : 고속도로 진입 데모 경로 (기본 EMART)
        val route = intent.getStringExtra("demoRoute")?.let { name -> DemoRoute.entries.find { it.name == name } }
        // --ei demoStartM 81 : 경로의 81m 지점부터 출발한 것으로 친다 (시연 영상 장면용, 기본 0)
        val startM = if (intent.hasExtra("demoStartM")) intent.getIntExtra("demoStartM", 0) else 0
        if (intent.hasExtra("demo")) navi.setDemo(intent.getBooleanExtra("demo", false), route, startM)
        intent.getStringExtra("ask")?.let { session.onQuestionText(it) }
    }

    private companion object {
        val RUNTIME_PERMISSIONS = listOf(
            "android.car.permission.CAR_SPEED",        // 속도
            "android.car.permission.CAR_POWERTRAIN",   // 기어
            Manifest.permission.RECORD_AUDIO,          // 음성 질문
            Manifest.permission.ACCESS_FINE_LOCATION,  // 이벤트 위치 기록
        )
    }
}
