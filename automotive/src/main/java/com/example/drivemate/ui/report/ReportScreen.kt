package com.example.drivemate.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.drivemate.data.DriveRepository
import com.example.drivemate.data.EventEntity
import com.example.drivemate.model.EventType
import com.example.drivemate.report.CardKind
import com.example.drivemate.report.Goal
import com.example.drivemate.report.ReportBuilder
import com.example.drivemate.report.ReportCard
import com.example.drivemate.ui.components.DmButton
import com.example.drivemate.ui.components.DmCard
import com.example.drivemate.ui.components.DmChoice
import com.example.drivemate.ui.components.DmDot
import com.example.drivemate.ui.formatDistance
import com.example.drivemate.ui.formatDuration
import com.example.drivemate.ui.formatTime
import com.example.drivemate.ui.theme.DmDimens
import com.example.drivemate.ui.theme.DmTheme
import com.example.drivemate.ui.theme.DmType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 이벤트 목록의 한 줄 */
data class EventRow(val id: Long, val time: String, val label: String, val speed: String, val difficult: Boolean)

/** 리포트 화면이 그리는 데 필요한 값 */
data class ReportUi(
    val loaded: Boolean = false,
    val header: String = "",                 // "동네 마트 · 12분 · 2.4km"
    val cards: List<ReportCard> = emptyList(),
    val goal: Goal? = null,
    val events: List<EventRow> = emptyList(),
)

/** 리포트 화면의 ViewModel: 주행 하나의 기록을 읽어 처방 카드·다음 목표·이벤트 목록으로 바꾼다 */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val repo: DriveRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val tripId: Long = savedStateHandle["tripId"] ?: 0L

    val ui: StateFlow<ReportUi> =
        combine(repo.observeTrip(tripId), repo.observeEvents(tripId), repo.observeQuestions(tripId)) { trip, events, questions ->
            if (trip == null) return@combine ReportUi()
            val report = ReportBuilder.build(events, questions)
            ReportUi(
                loaded = true,
                header = listOf(
                    trip.destination ?: "목적지 없음",
                    formatDuration(trip.startTime, trip.endTime),
                    formatDistance(trip.distanceM),
                ).joinToString(" · "),
                cards = report.cards,
                goal = report.goal,
                events = events.map { it.toRow() },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReportUi())

    /** 이벤트를 "어려웠던 구간"으로 표시하거나 표시를 지운다 */
    fun toggleDifficult(row: EventRow) {
        viewModelScope.launch { repo.setDifficult(row.id, !row.difficult) }
    }

    // 이벤트를 사실 그대로 한 줄로 (평가하는 말 없이)
    private fun EventEntity.toRow(): EventRow {
        val label = when (type) {
            EventType.TURN.name -> {
                val direction = if (direction == "LEFT") "좌회전" else "우회전"
                when {
                    signalUsed == true -> "$direction · 방향지시등 작동 기록 있음"
                    !signalSide.isNullOrBlank() -> "$direction · 반대쪽 방향지시등 기록"   // 좌우 구분 (사실만 표시)
                    else -> "$direction · 방향지시등 기록 없음"
                }
            }
            EventType.HARD_BRAKE.name -> if (nearTurn == true) "급감속 · 회전·교차로 앞" else "급감속"
            EventType.HARD_ACCEL.name -> if (fromStop == true) "급가속 · 출발할 때" else "급가속"
            else -> "어려웠던 곳으로 표시"
        }
        return EventRow(id, formatTime(time), label, "${speedKmh.toInt()}km/h", difficult)
    }
}

/**
 * 주행 리포트 화면 (정차 후에만 보인다).
 *
 * 점수를 매기지 않는다. "무엇이 있었고 -> 다음엔 어떻게 해 볼지"를 한 줄씩 보여주고,
 * 다음 연습 목표는 하나만 제안한다. 실제 도로 상황을 모르므로 기록된 사실만 말한다.
 */
@Composable
fun ReportScreen(onHome: () -> Unit, viewModel: ReportViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = DmTheme.colors

    BoxWithConstraints(Modifier.fillMaxSize().padding(DmDimens.screenPadding)) {
        val wide = maxWidth >= 900.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DmDimens.gap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("오늘의 주행", style = DmType.title, color = colors.text)
                Text(ui.header, style = DmType.body, color = colors.textSub)
            }

            if (wide) {
                // 넓은 창: 왼쪽에 처방 카드와 이벤트 목록, 오른쪽에 다음 목표
                Row(horizontalArrangement = Arrangement.spacedBy(DmDimens.gap)) {
                    Column(Modifier.weight(1.7f), verticalArrangement = Arrangement.spacedBy(DmDimens.gap)) {
                        ui.cards.forEach { PrescriptionCard(it) }
                        EventList(ui.events, viewModel::toggleDifficult)
                    }
                    GoalCard(ui.goal, onHome, Modifier.weight(1f))
                }
            } else {
                // 좁은 창(세로): 위에서 아래로 쌓는다
                ui.cards.forEach { PrescriptionCard(it) }
                GoalCard(ui.goal, onHome, Modifier.fillMaxWidth())
                EventList(ui.events, viewModel::toggleDifficult)
            }
        }
    }
}

/** 처방 카드: ● 있었던 일 / → 다음에 해 볼 것 */
@Composable
private fun PrescriptionCard(card: ReportCard) {
    val colors = DmTheme.colors
    // 복습할 것은 주황, 기록이 깨끗하면 초록, 질문은 파랑. 빨간색은 쓰지 않는다.
    val dot = when (card.kind) {
        CardKind.CLEAN -> colors.good
        CardKind.QUESTION -> colors.accent
        else -> colors.caution
    }
    DmCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DmDot(dot)
            Text(card.fact, style = DmType.body, color = colors.text)
        }
        Text("→ ${card.tip}", style = DmType.body, color = colors.accentText)
    }
}

/** 다음 연습 목표 (하나만) + [홈으로] */
@Composable
private fun GoalCard(goal: Goal?, onHome: () -> Unit, modifier: Modifier) {
    val colors = DmTheme.colors
    DmCard(modifier, border = colors.accent) {
        Text("다음 연습 목표", style = DmType.sub, color = colors.textSub)
        Text(goal?.title ?: "", style = DmType.title, color = colors.accentText)
        Text(goal?.detail ?: "", style = DmType.body, color = colors.text)
        DmButton("홈으로", onHome, Modifier.fillMaxWidth().padding(top = 16.dp).height(120.dp))
    }
}

/** 이벤트 목록: 시간 · 종류 · 속도, 그리고 "어려웠어요" 체크 */
@Composable
private fun EventList(events: List<EventRow>, onToggle: (EventRow) -> Unit) {
    val colors = DmTheme.colors
    DmCard(Modifier.fillMaxWidth()) {
        Text("이벤트 목록", style = DmType.sub, color = colors.textSub)
        if (events.isEmpty()) {
            Text("기록된 이벤트가 없어요", style = DmType.body, color = colors.text)
        }
        events.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.label, style = DmType.body, color = colors.text)
                    Text("${row.time} · ${row.speed}", style = DmType.sub, color = colors.textSub)
                }
                DmChoice("어려웠어요", selected = row.difficult, onClick = { onToggle(row) })
            }
        }
    }
}
