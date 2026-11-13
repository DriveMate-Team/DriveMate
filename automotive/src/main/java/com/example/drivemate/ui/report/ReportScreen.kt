package com.example.drivemate.ui.report

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
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
import com.example.drivemate.drive.DriveSession
import com.example.drivemate.drive.DriveState
import com.example.drivemate.drive.ListenState
import com.example.drivemate.ui.components.MicIcon
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import com.example.drivemate.model.EventType
import com.example.drivemate.report.CardKind
import com.example.drivemate.report.Goal
import com.example.drivemate.report.ReportBuilder
import com.example.drivemate.report.ReportCard
import com.example.drivemate.report.CompareRow
import com.example.drivemate.report.Trend
import com.example.drivemate.report.TripComparison
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
    val compare: List<CompareRow> = emptyList(),   // 이전 주행 대비 변화 (이전 주행이 없으면 빈 목록)
    val hasPrevious: Boolean = false,
)

/** 리포트 화면의 ViewModel: 주행 하나의 기록을 읽어 처방 카드·다음 목표·이벤트 목록으로 바꾼다 */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val repo: DriveRepository,
    private val session: DriveSession,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val tripId: Long = savedStateHandle["tripId"] ?: 0L

    /** 마이크·답변 상태 (주행 중 화면과 같은 두뇌를 쓴다) */
    val drive: StateFlow<DriveState> = session.state

    /** 주차 후 질문: 이 리포트의 주행 기록으로 답한다 (신청서 4-⑤) */
    fun onMic() = session.onMicPressed(reportTripId = tripId)

    val ui: StateFlow<ReportUi> =
        combine(repo.observeTrip(tripId), repo.observeEvents(tripId), repo.observeQuestions(tripId)) { trip, events, questions ->
            if (trip == null) return@combine ReportUi()
            val report = ReportBuilder.build(events, questions)
            // 이전 주행 대비 변화 (신청서 3-④)
            val previous = repo.getPreviousTrip(trip.startTime)
            val compare = previous?.let { TripComparison.compare(repo.getEvents(it.id), events) }.orEmpty()
            ReportUi(
                compare = compare,
                hasPrevious = previous != null,
                loaded = true,
                header = listOf(
                    trip.destination ?: "목적지 없음",
                    formatDuration(trip.startTime, trip.endTime),
                    formatDistance(trip.distanceM),
                ).joinToString(" · "),
                cards = report.cards,
                goal = report.goal,
                events = events.filter { it.type != EventType.POINT_PASSED.name }.map { it.toRow() },   // "지점 지남"은 로드맵용이라 목록에 안 보인다
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
            EventType.IRREGULAR_SPEED.name -> "불규칙한 속도 변화"
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
    val drive by viewModel.drive.collectAsStateWithLifecycle()
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
                        if (ui.hasPrevious) CompareCard(ui.compare)
                        EventList(ui.events, viewModel::toggleDifficult)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DmDimens.gap)) {
                        GoalCard(ui.goal, onHome, Modifier.fillMaxWidth())
                        AskCard(drive, viewModel::onMic, Modifier.fillMaxWidth())
                    }
                }
            } else {
                // 좁은 창(세로): 위에서 아래로 쌓는다
                ui.cards.forEach { PrescriptionCard(it) }
                if (ui.hasPrevious) CompareCard(ui.compare)
                GoalCard(ui.goal, onHome, Modifier.fillMaxWidth())
                AskCard(drive, viewModel::onMic, Modifier.fillMaxWidth())
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
        Text(goal?.detail?.let { "이번 주행에서 $it" } ?: "", style = DmType.body, color = colors.text)
        DmButton("홈으로", onHome, Modifier.fillMaxWidth().padding(top = 16.dp).height(120.dp))
    }
}

/** 이전 주행 대비 변화: 줄었으면 초록 칭찬, 그 밖에는 사실만 (빨간색 없음) */
@Composable
private fun CompareCard(rows: List<CompareRow>) {
    val colors = DmTheme.colors
    DmCard(Modifier.fillMaxWidth()) {
        Text("지난 주행과 비교", style = DmType.sub, color = colors.textSub)
        if (rows.isEmpty()) {
            Text("두 주행 모두 기록된 급가속·급감속·깜빡이 누락이 없어요", style = DmType.body, color = colors.text)
        }
        // 같은 줄끼리 막대 길이를 비교할 수 있게, 카드 안 모든 막대는 같은 최댓값을 기준으로 그린다
        val max = rows.maxOfOrNull { maxOf(it.before, it.now) }?.coerceAtLeast(1) ?: 1
        rows.forEach { row ->
            val fewer = row.trend == Trend.FEWER
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, style = DmType.body, color = colors.text, modifier = Modifier.weight(1f))
                    Text(row.text, style = DmType.body, color = if (fewer) colors.goodText else colors.textSub)
                }
                CompareBar("지난", row.before, max, colors.outline)
                // 줄었으면 초록, 같거나 늘었으면 회색 (빨간색은 쓰지 않는다). 색만으로 구분하지 않게 숫자와 글자도 함께 둔다
                CompareBar("이번", row.now, max, if (fewer) colors.good else colors.textSub)
            }
        }
    }
}

/** 비교 막대 한 줄: [지난] ■■■■■ 3 */
@Composable
private fun CompareBar(label: String, value: Int, max: Int, color: Color) {
    val colors = DmTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = DmType.sub, color = colors.textSub, modifier = Modifier.width(56.dp))
        Box(Modifier.weight(1f).height(24.dp)) {
            // 0번이어도 아주 짧은 막대를 남겨 "0"이 보이게 한다
            val fraction = (value.toFloat() / max).coerceIn(0.02f, 1f)
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(color))
        }
        Text("${value}번", style = DmType.sub, color = colors.text, modifier = Modifier.width(56.dp))
    }
}

/**
 * 주차 후 질문 카드: 마이크 버튼 + 상태 + 답변 글자.
 * 예: "아까 우회전할 때 깜빡이 켰어?", "오늘 부족했던 점은?", "더 연습하면 좋을 부분은?" (신청서 4-⑤)
 * 정차 중 화면이라 답을 글자로도 보여준다.
 */
@Composable
private fun AskCard(drive: DriveState, onMic: () -> Unit, modifier: Modifier) {
    val colors = DmTheme.colors
    val listening = drive.listen == ListenState.LISTENING
    DmCard(modifier) {
        Text("이번 주행에 대해 물어보기", style = DmType.sub, color = colors.textSub)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(
                Modifier
                    .size(DmDimens.buttonMin)
                    .clip(CircleShape)
                    .background(if (listening) colors.card else colors.accentFill)
                    .then(if (listening) Modifier.border(BorderStroke(5.dp, colors.accent), CircleShape) else Modifier)
                    .clickable(role = Role.Button, onClickLabel = "질문하기") { onMic() },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    rememberVectorPainter(MicIcon), contentDescription = "질문하기", modifier = Modifier.size(48.dp),
                    colorFilter = ColorFilter.tint(if (listening) colors.accentText else colors.onAccentFill),
                )
            }
            Text(
                when (drive.listen) {
                    ListenState.LISTENING -> "듣고 있어요"
                    ListenState.ANSWERING -> "답하는 중"
                    ListenState.IDLE -> "예: 아까 우회전할 때 깜빡이 켰어?"
                },
                style = DmType.body, color = if (listening) colors.accentText else colors.text,
            )
        }
        // 마지막 답변 (기본 안내 문구는 보여주지 않는다)
        if (drive.bubble.isNotBlank() && !drive.active && drive.listen != ListenState.LISTENING) {
            Text(drive.bubble, style = DmType.body, color = colors.text)
        }
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
