package com.example.drivemate.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.example.drivemate.data.DriveRepository
import com.example.drivemate.model.toLabel
import com.example.drivemate.model.toTopics
import com.example.drivemate.report.ReportBuilder
import com.example.drivemate.ui.TripSummary
import com.example.drivemate.ui.components.DmButton
import com.example.drivemate.ui.components.DmCard
import com.example.drivemate.ui.summarize
import com.example.drivemate.ui.theme.DmDimens
import com.example.drivemate.ui.theme.DmTheme
import com.example.drivemate.ui.theme.DmType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 홈 화면이 그리는 데 필요한 값 */
data class HomeUi(
    val greeting: String = "오늘도 같이 연습해 볼까요?",
    val goalTitle: String = "",
    val goalDetail: String = "",
    val lastTrip: TripSummary? = null,
)

/** 홈 화면의 ViewModel: 저장된 학습 정보와 최근 주행을 읽어 화면용 값으로 바꾼다 */
@HiltViewModel
class HomeViewModel @Inject constructor(repo: DriveRepository) : ViewModel() {
    val ui: StateFlow<HomeUi> =
        combine(repo.observeProfile(), repo.observeFinishedTrips(), repo.observeAllEvents()) { profile, trips, events ->
            val topics = profile.topics.toTopics().toLabel()
            HomeUi(
                // 인사말: 지난 리포트의 목표가 있으면 그것을, 없으면 고른 연습 주제를 말한다
                greeting = if (profile.goalTitle != null) "오늘도 같이 연습해 볼까요?"
                else "오늘은 $topics${ReportBuilder.josa(topics, "을", "를")} 연습해 볼까요?",
                goalTitle = profile.goalTitle ?: "$topics 연습",
                goalDetail = profile.goalDetail ?: "첫 주행이에요. 편하게 달려 보세요.",
                lastTrip = trips.firstOrNull()?.let { summarize(it, events) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUi())
}

/**
 * 홈 화면 (정차 중에만 보인다).
 * 위에서부터: 인사말 -> 이번 연습 목표 카드 -> 최근 주행 1건 요약 -> [주행 시작] [기록 보기]
 */
@Composable
fun HomeScreen(onStart: () -> Unit, onHistory: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = DmTheme.colors

    BoxWithConstraints(Modifier.fillMaxSize().padding(DmDimens.screenPadding)) {
        val wide = maxWidth >= 900.dp   // 넓은 창이면 카드 2개를 옆으로, 좁으면(세로 창) 위아래로
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DmDimens.gap),
        ) {
            Text(ui.greeting, style = DmType.title, color = colors.text)

            val goalCard: @Composable (Modifier) -> Unit = { modifier ->
                DmCard(modifier, border = colors.accent) {
                    Text("이번 연습 목표", style = DmType.sub, color = colors.textSub)
                    Text(ui.goalTitle, style = DmType.title, color = colors.accentText)
                    Text(ui.goalDetail, style = DmType.body, color = colors.text)
                }
            }
            val lastCard: @Composable (Modifier) -> Unit = { modifier ->
                DmCard(modifier) {
                    Text("최근 주행", style = DmType.sub, color = colors.textSub)
                    val trip = ui.lastTrip
                    if (trip == null) {
                        Text("아직 주행 기록이 없어요", style = DmType.body, color = colors.text)
                    } else {
                        Text(trip.destination, style = DmType.body, color = colors.text)
                        Text("${trip.date} · ${trip.meta}", style = DmType.sub, color = colors.textSub)
                        Text(trip.numbers, style = DmType.sub, color = colors.textSub)
                    }
                }
            }

            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(DmDimens.gap)) {
                    goalCard(Modifier.weight(1f))
                    lastCard(Modifier.weight(1f))
                }
            } else {
                goalCard(Modifier.fillMaxWidth())
                lastCard(Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(8.dp))
            // 자주 쓰는 "주행 시작"을 가장 크게 (Pleos 기획 가이드: 자주 쓰는 기능은 첫 화면에)
            Row(horizontalArrangement = Arrangement.spacedBy(DmDimens.gap)) {
                DmButton("주행 시작", onStart, Modifier.weight(2f).height(140.dp))
                DmButton("기록 보기", onHistory, Modifier.weight(1f).height(140.dp), primary = false)
            }
        }
    }
}
