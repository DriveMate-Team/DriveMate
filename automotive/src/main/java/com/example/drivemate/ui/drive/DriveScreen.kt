package com.example.drivemate.ui.drive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.drivemate.drive.DriveSession
import com.example.drivemate.drive.DriveState
import com.example.drivemate.drive.ListenState
import com.example.drivemate.model.toLabel
import com.example.drivemate.ui.components.DmCard
import com.example.drivemate.ui.components.DmTag
import com.example.drivemate.ui.components.MicIcon
import com.example.drivemate.ui.formatRemaining
import com.example.drivemate.ui.theme.DmDimens
import com.example.drivemate.ui.theme.DmTheme
import com.example.drivemate.ui.theme.DmType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** 주행 중 화면의 ViewModel: DriveSession(두뇌)의 상태를 그대로 보여주고, 버튼 2개를 전달한다 */
@HiltViewModel
class DriveViewModel @Inject constructor(private val session: DriveSession) : ViewModel() {
    val state: StateFlow<DriveState> = session.state
    fun onMic() = session.onMicPressed()
    fun onEnd() = session.end(manual = true)
}

/**
 * 주행 중 화면: "보지 않아도 되는 화면"이 목표.
 *
 *  - 버튼은 2개뿐: 마이크(크게), 주행 끝내기(작게, 길게 눌러야 동작)
 *  - 글자는 크게, 말풍선은 짧은 한 문장만 (긴 안내는 음성으로)
 *  - 움직이는 그림·애니메이션 없음 (Pleos 가이드: 주행 중 시선을 끄는 요소 금지).
 *    "듣는 중"은 움직이지 않는 색·글자 변화로만 알린다
 *  - 지적하지 않는다: 급가속·급감속이 기록돼도 이 화면에는 아무 표시가 없다
 *
 * 창 크기는 사용자가 정한다(내비와 나눠 쓰는 좁은 창일 수도, 넓은 창일 수도 있다).
 * 좁으면 위아래로 쌓고, 넓으면 왼쪽에 안내·오른쪽에 버튼을 둔다.
 */
@Composable
fun DriveScreen(viewModel: DriveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler {}   // 주행 중에는 뒤로가기로 화면이 바뀌지 않게 한다 (기록이 끊기지 않도록)

    BoxWithConstraints(Modifier.fillMaxSize().padding(DmDimens.screenPadding)) {
        if (maxWidth >= 900.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(DmDimens.gap)) {
                InfoPanel(state, Modifier.weight(1.7f).fillMaxHeight())
                ControlPanel(state, viewModel::onMic, viewModel::onEnd, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(DmDimens.gap)) {
                InfoPanel(state, Modifier.weight(1f).fillMaxWidth())
                ControlPanel(state, viewModel::onMic, viewModel::onEnd, Modifier.fillMaxWidth())
            }
        }
    }
}

/** 안내 영역: 연습 주제 꼬리표 -> 다음 상황 카드 -> 코칭 말풍선 */
@Composable
private fun InfoPanel(state: DriveState, modifier: Modifier) {
    val colors = DmTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DmDimens.gap)) {
        DmTag("연습: ${state.topics.toLabel()}")

        // 다음 상황 카드: "다음: 좌회전 · 300m"
        DmCard(Modifier.fillMaxWidth()) {
            val next = state.next
            Text(
                text = when {
                    !state.hasRoute -> "내비 경로 없음"
                    next == null -> "다음 안내 없음"
                    else -> "다음: ${next.type.label} · ${formatRemaining(next.distanceM)}"
                },
                style = DmType.title, color = colors.text, maxLines = 1,
            )
        }

        // 코칭 말풍선 (Gleo가 말하는 모양과 비슷하게: 둥근 카드 + 파란 테두리)
        DmCard(Modifier.fillMaxWidth().weight(1f, fill = false), border = colors.accent, padding = 36.dp) {
            Text(state.bubble, style = DmType.coaching, color = colors.text, maxLines = 3)
        }
    }
}

/** 버튼 영역: 마이크(크게) + 상태 한 줄 + 주행 끝내기(작게, 길게 누르기) */
@Composable
private fun ControlPanel(state: DriveState, onMic: () -> Unit, onEnd: () -> Unit, modifier: Modifier) {
    val colors = DmTheme.colors
    val haptic = LocalHapticFeedback.current
    val listening = state.listen == ListenState.LISTENING
    var showHoldHint by remember { mutableStateOf(false) }

    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        // 마이크 버튼 140dp. 듣는 중에는 바탕색이 바뀌고 테두리가 생긴다 (움직임 없음)
        Box(
            Modifier
                .size(DmDimens.mic)
                .clip(CircleShape)
                .background(if (listening) colors.card else colors.accentFill)
                .then(if (listening) Modifier.border(BorderStroke(6.dp, colors.accent), CircleShape) else Modifier)
                .semantics { contentDescription = "질문하기"; role = Role.Button }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMic()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = rememberVectorPainter(MicIcon),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                colorFilter = ColorFilter.tint(if (listening) colors.accentText else colors.onAccentFill),
            )
        }
        // 아이콘에는 항상 글자 설명을 함께 둔다 (Pleos 가이드: 아이콘 + 텍스트 라벨 병행)
        Text(
            text = when (state.listen) {
                ListenState.LISTENING -> "듣는 중 · 다시 누르면 취소"
                ListenState.ANSWERING -> "답하는 중"
                ListenState.IDLE -> "눌러서 질문하기"
            },
            style = DmType.body,
            color = if (listening) colors.accentText else colors.text,
            textAlign = TextAlign.Center,
        )

        // 주행 끝내기: 실수로 눌리지 않게 길게 눌러야 동작한다. 짧게 누르면 안내 글자만 바뀐다.
        val shape = RoundedCornerShape(DmDimens.corner)
        Box(
            Modifier
                .defaultMinSize(minWidth = 280.dp, minHeight = DmDimens.buttonMin)
                .clip(shape)
                .background(colors.card)
                .border(BorderStroke(2.dp, colors.outline), shape)
                .semantics { contentDescription = "주행 끝내기"; role = Role.Button }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showHoldHint = true },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onEnd()
                        },
                    )
                }
                .padding(horizontal = 28.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (showHoldHint) "길게 눌러야 끝나요" else "길게 눌러 주행 끝내기",
                style = DmType.sub, color = colors.textSub, textAlign = TextAlign.Center,
            )
        }
    }
}
