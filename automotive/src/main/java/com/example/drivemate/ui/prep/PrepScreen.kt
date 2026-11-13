package com.example.drivemate.ui.prep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.drivemate.BuildConfig
import com.example.drivemate.data.DriveRepository
import com.example.drivemate.drive.DriveSession
import com.example.drivemate.model.DriveMode
import com.example.drivemate.model.NaviState
import com.example.drivemate.model.Topic
import com.example.drivemate.model.toTopics
import com.example.drivemate.ui.components.DmButton
import com.example.drivemate.ui.components.DmCard
import com.example.drivemate.ui.components.DmChoice
import com.example.drivemate.ui.theme.DmDimens
import com.example.drivemate.ui.theme.DmTheme
import com.example.drivemate.ui.theme.DmType
import com.example.drivemate.vehicle.SwitchingNaviSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 주행 준비 화면의 ViewModel: 목적지(내비에서 읽음), 모드, 연습 주제를 들고 있다 */
@HiltViewModel
class PrepViewModel @Inject constructor(
    private val repo: DriveRepository,
    private val session: DriveSession,
    private val navi: SwitchingNaviSource,
) : ViewModel() {

    val naviState: StateFlow<NaviState> = navi.state
    val demo: StateFlow<Boolean> = navi.demo

    private val _mode = MutableStateFlow(DriveMode.BEGINNER)
    val mode: StateFlow<DriveMode> = _mode

    private val _topics = MutableStateFlow(setOf(Topic.INTERSECTION))
    val topics: StateFlow<Set<Topic>> = _topics

    init {
        // 지난번에 고른 모드·주제를 미리 채워 준다 (운전 전 조작을 줄이기 위해)
        viewModelScope.launch {
            val profile = repo.getProfile()
            _mode.value = DriveMode.entries.find { it.name == profile.mode } ?: DriveMode.BEGINNER
            _topics.value = profile.topics.toTopics()
        }
    }

    fun selectMode(mode: DriveMode) { _mode.value = mode }

    /** 연습 주제는 여러 개를 고를 수 있다: 누르면 켜지고 다시 누르면 꺼진다 */
    fun toggleTopic(topic: Topic) = _topics.update { if (topic in it) it - topic else it + topic }

    fun openNavi() = navi.openNavi()
    fun setDemo(enabled: Boolean) = navi.setDemo(enabled)
    fun start() = session.start(_mode.value, _topics.value)
}

/**
 * 주행 준비 화면 (정차 중에만 보인다).
 * 위에서부터: 목적지(내비에서 불러옴) -> 모드 2개 중 선택 -> 연습 주제 칩 -> [출발하기]
 *
 * 목적지 검색 화면은 만들지 않는다. 길 안내는 차량 내비가 하고, 앱은 내비의 경로를 읽기만 한다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrepScreen(onBack: () -> Unit, viewModel: PrepViewModel = hiltViewModel()) {
    val navi by viewModel.naviState.collectAsStateWithLifecycle()
    val demo by viewModel.demo.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val topics by viewModel.topics.collectAsStateWithLifecycle()
    val colors = DmTheme.colors

    Column(
        Modifier.fillMaxSize().padding(DmDimens.screenPadding).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DmDimens.gap),
    ) {
        Text("주행 준비", style = DmType.title, color = colors.text)

        // ---------- 목적지 ----------
        DmCard(Modifier.fillMaxWidth()) {
            Text("목적지", style = DmType.sub, color = colors.textSub)
            if (navi.hasRoute) {
                Text(navi.destination ?: "내비에 설정된 경로", style = DmType.body, color = colors.text)
            } else {
                Text("내비에서 목적지를 설정해 주세요", style = DmType.body, color = colors.text)
                Text(
                    "목적지 없이 출발하면 상황 예고 코칭 없이 기록과 질문만 도와드려요.",
                    style = DmType.sub, color = colors.textSub,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!navi.hasRoute) DmButton("내비 열기", viewModel::openNavi, primary = false)
                // 시연·테스트용 가짜 경로 (디버그 빌드에서만 보인다)
                if (BuildConfig.DEBUG) {
                    DmButton(
                        if (demo) "데모 경로 끄기" else "데모 경로 쓰기",
                        { viewModel.setDemo(!demo) }, primary = false,
                    )
                }
            }
        }

        // ---------- 모드 ----------
        DmCard(Modifier.fillMaxWidth()) {
            Text("모드", style = DmType.sub, color = colors.textSub)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DriveMode.entries.forEach { m ->
                    DmChoice(m.label, selected = mode == m, onClick = { viewModel.selectMode(m) })
                }
            }
            Text(
                if (mode == DriveMode.BEGINNER) "좌회전·우회전이 다가오면 항상 자세히 알려드려요."
                else "아래에서 고른 연습 주제만 짧게 알려드려요.",
                style = DmType.sub, color = colors.textSub,
            )
        }

        // ---------- 연습 주제 ----------
        DmCard(Modifier.fillMaxWidth()) {
            Text("연습 주제 (여러 개 고를 수 있어요)", style = DmType.sub, color = colors.textSub)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Topic.entries.forEach { t ->
                    // 차선 정보를 읽을 수 없어 차선 변경은 코칭 없이 질문만 받는다 (DRIVEMATE_V2.md "차선 정보")
                    val label = if (t == Topic.LANE_CHANGE) "${t.label} (질문만)" else t.label
                    DmChoice(label, selected = t in topics, onClick = { viewModel.toggleTopic(t) })
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(DmDimens.gap)) {
            DmButton("뒤로", onBack, Modifier.weight(1f).height(140.dp), primary = false)
            DmButton("출발하기", viewModel::start, Modifier.weight(2f).height(140.dp))
        }
    }
}
