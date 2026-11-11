package com.example.drivemate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.drivemate.ui.theme.DmDimens
import com.example.drivemate.ui.theme.DmTheme
import com.example.drivemate.ui.theme.DmType

/*
 * 여러 화면이 함께 쓰는 부품 모음 (카드, 버튼, 선택 칩, 꼬리표, 확인 대화상자).
 * 모양을 한곳에서 정해 두어 모든 화면이 같은 느낌을 갖게 한다 (Pleos 가이드: 일관성).
 */

/** 흰(야간은 어두운) 카드. border를 주면 테두리 색이 생긴다 (코칭 말풍선의 파란 테두리 등) */
@Composable
fun DmCard(
    modifier: Modifier = Modifier,
    border: Color? = null,
    padding: Dp = 28.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(DmDimens.corner)
    Column(
        modifier = modifier
            .clip(shape)
            .background(DmTheme.colors.card)
            .then(if (border != null) Modifier.border(BorderStroke(3.dp, border), shape) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * 버튼. primary = 주요 버튼(파란 바탕), 아니면 보조 버튼(테두리만).
 * 최소 96dp 크기(설계 기준값), 누르면 진동 피드백(Pleos 가이드: 터치 시 햅틱 또는 음성 피드백).
 */
@Composable
fun DmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = DmTheme.colors
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(DmDimens.corner)
    val background = when {
        !enabled -> colors.outline
        primary -> colors.accentFill
        else -> colors.card
    }
    val textColor = when {
        !enabled -> colors.textSub
        primary -> colors.onAccentFill
        else -> colors.text
    }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = DmDimens.buttonMin, minHeight = DmDimens.buttonMin)
            .clip(shape)
            .background(background)
            .then(if (!primary) Modifier.border(BorderStroke(2.dp, colors.outline), shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 36.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = DmType.body, color = textColor, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/**
 * 고를 수 있는 칩 (모드, 연습 주제).
 * 골랐는지는 색만으로 구분하지 않고 "✓" 표시도 함께 보여준다 (Pleos 가이드: 색만으로 정보를 전달하지 않기).
 */
@Composable
fun DmChoice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DmTheme.colors
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(DmDimens.corner)
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = DmDimens.buttonMin, minHeight = DmDimens.buttonMin)
            .clip(shape)
            .background(colors.card)
            .border(BorderStroke(if (selected) 3.dp else 2.dp, if (selected) colors.accent else colors.outline), shape)
            .clickable(role = Role.Checkbox) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 32.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (selected) "✓ $text" else text,
            style = DmType.body,
            color = if (selected) colors.accentText else colors.text,
            maxLines = 1,
        )
    }
}

/** 누를 수 없는 작은 꼬리표 (예: "연습: 교차로") */
@Composable
fun DmTag(text: String, modifier: Modifier = Modifier, color: Color = DmTheme.colors.accentText) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(DmTheme.colors.card)
            .border(BorderStroke(2.dp, DmTheme.colors.outline), RoundedCornerShape(50))
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Text(text, style = DmType.sub, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 작은 색 동그라미 (리포트 카드 앞의 표시) */
@Composable
fun DmDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(18.dp).clip(CircleShape).background(color))
}

/** "정말 지울까요?" 같은 확인 대화상자 */
@Composable
fun DmConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = DmType.title, color = DmTheme.colors.text) },
        text = { Text(message, style = DmType.body, color = DmTheme.colors.textSub) },
        confirmButton = { DmButton(confirmText, onConfirm) },
        dismissButton = { DmButton("취소", onDismiss, primary = false) },
        containerColor = DmTheme.colors.card,
        shape = RoundedCornerShape(DmDimens.corner),
    )
}

/** 마이크 아이콘 (국제적으로 통용되는 모양). 아이콘 라이브러리를 넣지 않으려고 선으로 직접 그렸다 */
val MicIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Mic", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(
            "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                "M17,11c0,2.76 -2.24,5 -5,5s-5,-2.24 -5,-5H5c0,3.53 2.61,6.43 6,6.92V21h2v-3.08c3.39,-0.49 6,-3.39 6,-6.92h-2z"
        ).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}
