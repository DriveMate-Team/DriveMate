package com.example.drivemate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import android.util.Log
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavController
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.drivemate.drive.DriveSession
import com.example.drivemate.ui.drive.DriveScreen
import com.example.drivemate.ui.history.HistoryScreen
import com.example.drivemate.ui.home.HomeScreen
import com.example.drivemate.ui.prep.PrepScreen
import com.example.drivemate.ui.report.ReportScreen
import com.example.drivemate.ui.theme.DmTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** 화면 주소 이름 */
private object Routes {
    const val HOME = "home"
    const val PREP = "prep"
    const val DRIVE = "drive"
    const val HISTORY = "history"
    const val REPORT = "report/{tripId}"
    fun report(tripId: Long) = "report/$tripId"
}

/** 화면 이동을 정하려고 주행 상태를 지켜보는 ViewModel */
@HiltViewModel
class AppViewModel @Inject constructor(val session: DriveSession) : ViewModel()

/**
 * 5개 화면과 그 사이의 이동 (설계문서 2단계 흐름도).
 *
 *   홈 -시작-> 주행 준비 -출발-> 주행 중 -주차 후-> 주행 리포트 -> 홈
 *   홈 -기록 보기-> 기록 목록 -> (지난) 리포트
 *
 * 자동 이동 2가지:
 *   - 주행이 시작되면(출발하기를 눌렀거나 차가 움직임) 어느 화면에 있든 "주행 중" 화면으로 바뀐다
 *   - 주행이 끝나면(기어 P 또는 주행 끝내기) "주행 리포트" 화면으로 바뀐다
 */
@Composable
fun AppNav(viewModel: AppViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val drive by viewModel.session.state.collectAsStateWithLifecycle()

    // [조사용 로그] 화면이 바뀔 때마다 "지금 화면"과 "쌓인 화면 목록"을 남긴다 (빈 화면 버그 추적)
    DisposableEffect(nav) {
        val listener = NavController.OnDestinationChangedListener { controller, destination, _ ->
            val stack = controller.currentBackStack.value.mapNotNull { it.destination.route }
            Log.i("DriveMate", "SCREEN ${destination.route} stack=$stack")
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }
    // [조사용 로그] 창 크기가 바뀔 때 (화면 분할 <-> 전체 화면)
    val config = LocalConfiguration.current
    LaunchedEffect(config.screenWidthDp, config.screenHeightDp) {
        Log.i("DriveMate", "WINDOW ${config.screenWidthDp}x${config.screenHeightDp}dp route=${nav.currentDestination?.route}")
    }

    // 주행 시작 -> 주행 중 화면으로
    LaunchedEffect(drive.active) {
        if (drive.active) nav.goTo(Routes.DRIVE)
    }
    // 주행 끝 -> 리포트 화면으로 (남길 기록이 없는 주행이면 홈으로)
    LaunchedEffect(Unit) {
        viewModel.session.finished.collect { tripId ->
            nav.goTo(if (tripId != null) Routes.report(tripId) else Routes.HOME)
        }
    }

    // safeDrawingPadding: 상태바 같은 시스템 UI에 가려지는 자리를 피한다 (Pleos 가이드: 중요 UI를 가려질 위치에 두지 않기)
    Box(Modifier.fillMaxSize().background(DmTheme.colors.background).safeDrawingPadding()) {
        NavHost(nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(onStart = { nav.navigate(Routes.PREP) }, onHistory = { nav.navigate(Routes.HISTORY) })
            }
            composable(Routes.PREP) {
                PrepScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.DRIVE) {
                DriveScreen()
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.report(it)) })
            }
            composable(Routes.REPORT, arguments = listOf(navArgument("tripId") { type = NavType.LongType })) {
                ReportScreen(onHome = { nav.goTo(Routes.HOME) })
            }
        }
    }
}

// 지금까지 쌓인 화면을 모두 치우고 그 화면 하나만 남긴다 (뒤로가기로 주행 중 화면에 돌아가지 않게).
// 홈이 아닌 화면으로 갈 때는 아래에 홈을 깔아 두어서, 뒤로가기를 누르면 홈이 나온다.
private fun NavHostController.goTo(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { inclusive = route == Routes.HOME }
        launchSingleTop = true
    }
}
