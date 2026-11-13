package com.example.drivemate.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.drivemate.model.ManeuverType
import com.example.drivemate.ui.theme.DmTheme

/*
 * 직접 그린 아이콘들 (아이콘 라이브러리를 넣지 않으려고 24×24 칸에 선으로 그렸다).
 * Pleos 가이드: 아이콘 기본 칸 48×48dp, 국제적으로 통용되는 모양 + 글자 설명을 함께 둔다.
 * 그래서 아이콘을 쓰는 곳에는 항상 같은 뜻의 글자가 옆에 있다.
 */

private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()

val TurnLeftIcon by lazy {
    icon("TurnLeft", "M6.83,11l1.58,1.59L7,14 3,10 7,6l1.42,1.41L6.83,9L14,9c2.76,0 5,2.24 5,5v5h-2v-5c0,-1.65 -1.35,-3 -3,-3H6.83z")
}
val TurnRightIcon by lazy {
    icon("TurnRight", "M17.17,11l-1.58,1.59L17,14l4,-4 -4,-4 -1.42,1.41L17.17,9H10c-2.76,0 -5,2.24 -5,5v5h2v-5c0,-1.65 1.35,-3 3,-3H17.17z")
}
val StraightIcon by lazy {
    icon("Straight", "M11,20V7.83l-3.59,3.58L6,10l6,-6 6,6 -1.41,1.41L13,7.83V20H11z")
}
val MergeIcon by lazy {
    icon("Merge", "M6.41,21L5,19.59l4.83,-4.83c0.75,-0.75 1.17,-1.77 1.17,-2.83v-5.1L9.41,8.41L8,7l4,-4l4,4l-1.41,1.41L13,6.83v5.1c0,1.06 0.42,2.08 1.17,2.83L19,19.59L17.59,21L12,15.41L6.41,21z")
}
val CheckIcon by lazy { icon("Check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z") }
val CoachIcon by lazy {
    icon("Coach", "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z")
}

/** 내비의 다음 상황에 맞는 화살표 (교차로는 직진 화살표). 그 밖의 안내는 null */
fun ManeuverType.arrowIcon(): ImageVector? = when (this) {
    ManeuverType.LEFT -> TurnLeftIcon
    ManeuverType.RIGHT -> TurnRightIcon
    ManeuverType.INTERSECTION -> StraightIcon
    ManeuverType.MERGE -> MergeIcon
    ManeuverType.OTHER -> null
}

/** 아이콘 하나를 색을 입혀 그린다 */
@Composable
fun DmIcon(vector: ImageVector, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(vector), contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/** 코치 얼굴: 파란 동그라미 안의 사람 모양 (움직이지 않는다) */
@Composable
fun CoachAvatar(size: Dp = 64.dp, modifier: Modifier = Modifier) {
    val colors = DmTheme.colors
    Box(modifier.size(size).clip(CircleShape).background(colors.accentFill), contentAlignment = Alignment.Center) {
        DmIcon(CoachIcon, colors.onAccentFill, size * 0.62f)
    }
}

/**
 * 말풍선 꼬리: 왼쪽 아래 끝이 코치 얼굴을 가리키는 삼각형.
 * 말풍선 카드와 같은 바탕색으로 칠하고, 바깥 두 변에만 카드와 같은 파란 테두리를 그린다.
 */
@Composable
fun BubbleTail(modifier: Modifier = Modifier, width: Dp = 28.dp, height: Dp = 40.dp) {
    val colors = DmTheme.colors
    Canvas(modifier.size(width, height)) {
        val w = size.width
        val h = size.height
        val fill = Path().apply {
            moveTo(w, 0f); lineTo(0f, h); lineTo(w, h * 0.6f); close()
        }
        drawPath(fill, colors.card)
        val edge = Path().apply { moveTo(w, 0f); lineTo(0f, h); lineTo(w, h * 0.6f) }
        drawPath(edge, colors.accent, style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
    }
}
