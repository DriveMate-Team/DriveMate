package com.example.drivemate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 설계문서 3단계 디자인 표를 코드로 옮긴 곳. 화면은 색·글자 크기를 직접 쓰지 않고 여기 값만 쓴다.
 *
 * 디자인 방향: "Pleos 화면에 녹아드는 옆자리 코치" — 밝은 화면에 파란 포인트 하나, 빨간색 경고는 쓰지 않는다.
 *
 * Pleos 디자인 가이드와 맞춘 점:
 *  - 글자·아이콘 대비 4.5:1 이상: 설계 색 가운데 글자로 쓰면 대비가 모자란 색(파랑·초록·주황·보조 글자)은
 *    "모양용 색"과 "글자용 색(조금 더 진함)"을 나눴다. 대비는 단위 테스트(ThemeContrastTest)로 계산해 확인한다.
 *  - 주간/야간: 차량의 주간/야간 설정을 따라 자동으로 바뀐다.
 *  - 색 HEX는 Pleos 토큰이 아니라 설계문서의 팀 기준값이다 (가이드가 값을 이미지로만 제공).
 */

/** DriveMate가 쓰는 색 묶음 */
data class DmColors(
    val background: Color,     // 모든 화면 배경
    val card: Color,           // 패널, 리포트 카드, 코칭 말풍선
    val accent: Color,         // 포인트(모양용): 말풍선 테두리, 선택 표시
    val accentFill: Color,     // 주요 버튼 바탕
    val onAccentFill: Color,   // 주요 버튼 위의 글자
    val accentText: Color,     // 파란 글자
    val good: Color,           // 칭찬(모양용)
    val goodText: Color,       // 칭찬 글자
    val caution: Color,        // 주의(모양용): 리포트의 복습 대상. 빨간색은 쓰지 않는다
    val cautionText: Color,    // 주의 글자
    val text: Color,           // 본문 글자
    val textSub: Color,        // 보조 설명 글자
    val outline: Color,        // 옅은 테두리
)

val LightColors = DmColors(
    background = Color(0xFFF5F6F8),
    card = Color(0xFFFFFFFF),
    accent = Color(0xFF2F6BFF),
    accentFill = Color(0xFF2563EB),      // 설계 색 #2F6BFF는 흰 글자 대비가 4.5에 조금 못 미쳐 살짝 진하게
    onAccentFill = Color(0xFFFFFFFF),
    accentText = Color(0xFF2563EB),
    good = Color(0xFF1FAF75),
    goodText = Color(0xFF17794D),
    caution = Color(0xFFF2A33A),
    cautionText = Color(0xFFB45309),
    text = Color(0xFF111418),
    textSub = Color(0xFF5F6A77),         // 설계 색 #6B7280은 회색 배경 위 대비가 모자라 살짝 진하게
    outline = Color(0xFFD5D9E0),
)

val DarkColors = DmColors(
    background = Color(0xFF12151A),
    card = Color(0xFF1E2128),
    accent = Color(0xFF5B8CFF),
    accentFill = Color(0xFF5B8CFF),
    onAccentFill = Color(0xFF12151A),    // 밝은 파랑 버튼 위에는 어두운 글자
    accentText = Color(0xFF8FB0FF),
    good = Color(0xFF3DCC8E),
    goodText = Color(0xFF3DCC8E),
    caution = Color(0xFFFFB547),
    cautionText = Color(0xFFFFB547),
    text = Color(0xFFFFFFFF),
    textSub = Color(0xFF9AA0AA),
    outline = Color(0xFF3A3F4A),
)

/** 글자 크기 (설계문서: 코칭 문구 40sp, 제목 32sp, 본문 24sp, 보조 20sp. 20sp보다 작은 글자는 쓰지 않는다) */
object DmType {
    val coaching = TextStyle(fontSize = 40.sp, lineHeight = 54.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
    val sub = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal)
}

/** 크기 (설계문서: 버튼 최소 96×96dp, 마이크 140dp, 모서리 24dp) */
object DmDimens {
    val buttonMin = 96.dp
    val mic = 140.dp
    val corner = 24.dp
    val gap = 24.dp          // 카드 사이 간격
    val screenPadding = 32.dp
}

val LocalDmColors = staticCompositionLocalOf { LightColors }

/** 화면에서 색을 꺼내 쓰는 지름길: DmTheme.colors.accent */
object DmTheme {
    val colors: DmColors
        @Composable get() = LocalDmColors.current
}

/**
 * 앱 전체를 감싸는 테마. 야간이면 어두운 색 묶음을 쓴다.
 * 글꼴은 시스템 기본 글꼴을 쓴다 (Pretendard/Asta Sans 중 무엇을 넣을지는 팀 결정 후 반영).
 */
@Composable
fun DriveMateTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    // Material 기본 부품(대화상자 등)도 같은 색을 쓰도록 맞춰 준다
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accentFill, onPrimary = colors.onAccentFill,
            background = colors.background, onBackground = colors.text,
            surface = colors.card, onSurface = colors.text,
            surfaceContainerHigh = colors.card, onSurfaceVariant = colors.textSub,
        )
    } else {
        lightColorScheme(
            primary = colors.accentFill, onPrimary = colors.onAccentFill,
            background = colors.background, onBackground = colors.text,
            surface = colors.card, onSurface = colors.text,
            surfaceContainerHigh = colors.card, onSurfaceVariant = colors.textSub,
        )
    }
    CompositionLocalProvider(LocalDmColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
