package com.example.drivemate.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.example.drivemate.report.Roadmap
import com.example.drivemate.report.RoadmapBuilder
import com.example.drivemate.report.Stage
import com.example.drivemate.ui.components.CheckIcon
import com.example.drivemate.ui.components.DmIcon
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
    val roadmap: Roadmap = Roadmap(session = 0, done = emptySet(), current = Stage.LEFT_TURN),
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
                roadmap = RoadmapBuilder.build(trips, events),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUi())
}

/**
 * 홈 화면 (정차 중에만 보인다).
 * 위에서부터: "초보 연수 N회차" 배지 + 인사말 -> 연수 로드맵 -> 이번 연습 목표 카드 · 최근 주행 1건 요약 -> [주행 시작] [기록 보기]
 * 연수 로드맵으로 "연수를 이어 가는 코치"라는 DriveMate의 정체성을 보여준다.
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                SessionBadge(ui.roadmap.session)
                Text(ui.greeting, style = DmType.title, color = colors.text)
            }
            RoadmapCard(ui.roadmap, Modifier.fillMaxWidth())

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

/** "초보 연수 N회차" 배지 (파란 바탕). N = 500m 이상 달린 주행 수 */
@Composable
private fun SessionBadge(session: Int) {
    val colors = DmTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(colors.accentFill).padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Text("초보 연수 ${session}회차", style = DmType.button, color = colors.onAccentFill, maxLines = 1)
    }
}

/**
 * 연수 로드맵: 회색 도로(가운데 흰 점선) 위에 4단계를 놓는다.
 *  - 끝낸 단계: 초록 동그라미 + ✓ / 지금 단계: 두꺼운 파란 테두리 + "지금 연습 중" / 남은 단계: 회색 테두리
 *  - 색만으로 구분하지 않게 단계 아래에 글자(완료 / 지금 연습 중 / 다음)를 함께 둔다
 *  - 움직이는 효과는 없다 (홈은 정차 중에만 보이지만 앱 전체를 같은 원칙으로 둔다)
 */
@Composable
private fun RoadmapCard(roadmap: Roadmap, modifier: Modifier) {
    val colors = DmTheme.colors
    val node = 80.dp
    DmCard(modifier) {
        Text("연수 로드맵", style = DmType.sub, color = colors.textSub)
        Box(Modifier.fillMaxWidth()) {
            // 도로: 동그라미들의 가운데 높이에 맞춰 그린다
            Canvas(Modifier.fillMaxWidth().height(node)) {
                val roadH = 44.dp.toPx()
                val top = (size.height - roadH) / 2
                drawRoundRect(colors.outline, topLeft = Offset(0f, top), size = Size(size.width, roadH), cornerRadius = CornerRadius(roadH / 2))
                drawLine(
                    colors.card, Offset(roadH / 2, size.height / 2), Offset(size.width - roadH / 2, size.height / 2),
                    strokeWidth = 4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 20f)),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Stage.entries.forEachIndexed { index, stage ->
                    val done = stage in roadmap.done
                    val current = stage == roadmap.current
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .size(node)
                                .clip(CircleShape)
                                .background(if (done) colors.good else colors.card)
                                .then(
                                    when {
                                        done -> Modifier
                                        current -> Modifier.border(BorderStroke(6.dp, colors.accent), CircleShape)
                                        else -> Modifier.border(BorderStroke(3.dp, colors.outline), CircleShape)
                                    }
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (done) DmIcon(CheckIcon, colors.card, 48.dp)
                            else Text("${index + 1}", style = DmType.title, color = if (current) colors.accentText else colors.textSub)
                        }
                        Text(stage.label, style = DmType.body, color = colors.text, textAlign = TextAlign.Center, maxLines = 1)
                        Text(
                            text = when { done -> "완료"; current -> "지금 연습 중"; else -> "다음" },
                            style = if (current) DmType.sub.copy(fontWeight = FontWeight.Bold) else DmType.sub,
                            color = when { done -> colors.goodText; current -> colors.accentText; else -> colors.textSub },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
