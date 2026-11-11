package com.example.drivemate.shared

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Random

// ====================================================================================
// [메이트 화면] 색 -- 바꾸고 싶으면 "이 부분만" 고치면 된다.
//
// 디자인 명세의 색을 그대로 쓰되, "글자"로 쓰는 색은 Pleos 가이드(대비 4.5:1 이상)에 맞게 조금 진하게 했다.
//   모양(막대, 점, 동그라미)에는 명세 색:  blue #3478F6 / green #2BC48A / orange #E8701A
//   글자에는 진한 색:                      blueText / greenText / orangeText / faint
// (모든 글자 색의 대비는 계산으로 확인했다. 아이콘에는 항상 글자를 함께 쓴다.)
// 주간 / 야간은 시스템 설정을 따라 자동으로 바뀐다.
// ====================================================================================
class Palette(val night: Boolean) {
    private fun c(day: String, nightColor: String) = Color.parseColor(if (night) nightColor else day)

    val bg = c("#F4F6FA", "#0F1217")              // 기본 배경
    val card = c("#FFFFFF", "#1A1F27")            // 카드
    val text = c("#191F28", "#F2F5F8")            // 본문 글자
    val sub = c("#4E5968", "#B4BDC9")             // 보조 글자
    val faint = c("#5F6A77", "#98A3B1")           // 흐린 글자 (명세 #8B95A1은 대비 3.0이라 글자로는 이 값을 쓴다)
    val track = c("#E8ECF2", "#2A323D")           // 진행 막대 빈 부분
    val blue = c("#3478F6", "#6B9BFF")            // 강조 파랑 (모양: 막대, 점)
    val blueText = c("#2563EB", "#8DB4FF")        // 파랑 글자
    val blueFill = c("#2563EB", "#2F5FD0")        // 흰 글자를 올리는 파랑 바탕 (배지, 버튼)
    val green = c("#2BC48A", "#3DD9A0")           // 안전 초록 (모양)
    val greenText = c("#17794D", "#6EE0AE")       // 초록 글자
    val orange = c("#E8701A", "#FF9A4D")          // 주의 주황 (모양)
    val orangeText = c("#B54708", "#FFB27A")      // 주황 글자
    val orangeBubble = c("#C2570C", "#A64B0B")    // 주의 말풍선 바탕 (흰 글자)
    val warnBg = c("#FFF4EC", "#2B1A0E")          // 주의 화면 배경
    val blueTint = c("#EAF2FF", "#172338")        // 연한 파랑 배경 (졸업 화면, 팁 카드)
    val greenTint = c("#E6F7F0", "#12281F")       // 연한 초록 칸
    val orangeTint = c("#FFE9D9", "#33200F")      // 연한 주황 칸
    val onFill = Color.WHITE                      // 색 바탕 위 글자
}

/** 화면이 그려야 할 상태 */
data class UiState(
    val snap: DriveEventStore.UiSnapshot,   // 주행 중 여부 / 최근 주행 / 졸업 진행
    val caution: String?,                   // 주의 표시 문구 (없으면 null)
    val coachLevel: Int,                    // 코치 수준 1~3
    val summary: TripResult?,               // 방금 끝난 주행(요약 화면용), 없으면 null
    val devOpen: Boolean,                   // 개발자 정보 화면을 열었는지
)

/**
 * DriveMate의 화면 전체. 템플릿 없이 Views로 직접 그린다. 코치를 "메이트" 캐릭터로 표현하고 말풍선으로 말한다.
 * 상태에 따라 다섯 모습 중 하나만 보인다: 운전 중(안전/주의) / 정차 / 주행 요약 / 졸업 축하 / 개발자 정보.
 * 운전 중 화면에는 터치할 곳이 없고 애니메이션도 없다. 아이콘은 모두 벡터이고 항상 글자와 함께 쓴다.
 */
class DriveMateUi(
    private val context: Context,
    private val onDismiss: () -> Unit,
    private val onOpenDev: () -> Unit,
    private val onCloseDev: () -> Unit,
) {
    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val p = Palette(night)
    val root = FrameLayout(context).apply { setBackgroundColor(p.bg) }

    private val driving = DrivingView()
    private val parked = ParkedView()
    private val summary = SummaryView()
    private val celebration = CelebrationView()
    private val dev = DevView()
    private val all get() = listOf(driving.view, parked.view, summary.view, celebration.view, dev.view)

    init {
        all.forEach { root.addView(it, FrameLayout.LayoutParams(-1, -1)); it.visibility = View.GONE }
    }

    /** 상태에 맞는 화면 하나만 보여주고 값을 채운다 */
    fun render(s: UiState) {
        val g = s.summary?.graduation
        val mode: View = when {
            s.snap.driving -> driving.view                                   // 운전 중에는 항상 운전 화면 (버튼 없음)
            s.devOpen -> dev.view
            s.summary != null && g != null && g.newlyGraduated -> celebration.view
            s.summary != null -> summary.view
            else -> parked.view
        }
        all.forEach { it.visibility = if (it === mode) View.VISIBLE else View.GONE }
        when (mode) {
            driving.view -> driving.bind(s)
            parked.view -> parked.bind(s)
            summary.view -> summary.bind(s.summary!!)
            celebration.view -> celebration.bind(s.summary!!)
            dev.view -> dev.bind()
        }
        root.setBackgroundColor(
            when {
                mode === driving.view && s.caution != null -> p.warnBg      // 주의: 화면 전체가 주황빛
                mode === celebration.view -> p.blueTint                     // 졸업: 연한 파랑
                else -> p.bg
            }
        )
    }

    // ------------------------------------------------------------------ 작은 도구들
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()
    private fun spacer(wDp: Float, hDp: Float) = View(context).apply { layoutParams = LinearLayout.LayoutParams(dpi(wDp), dpi(hDp)) }
    private fun space(hDp: Float) = spacer(1f, hDp)
    private fun hspace(wDp: Float) = spacer(wDp, 1f)

    private fun text(t: CharSequence, sp: Float, color: Int, bold: Boolean = false, gravity: Int = Gravity.START) = TextView(context).apply {
        this.text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT   // 글꼴: 시스템 기본 (Pleos 기본 글꼴)
        letterSpacing = -0.02f                                             // 자간 -0.02em
        this.gravity = gravity; includeFontPadding = false
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp) }
    private fun circleBg(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun icon(resId: Int, color: Int, sizeDp: Float) = ImageView(context).apply {
        setImageResource(resId); setColorFilter(color); layoutParams = LinearLayout.LayoutParams(dpi(sizeDp), dpi(sizeDp))
    }

    private fun mate(resId: Int, sizeDp: Float) = ImageView(context).apply {
        setImageResource(resId); scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dpi(sizeDp), dpi(sizeDp))
        contentDescription = "메이트"
    }

    /** 흰 카드 (큰 카드 36dp / 작은 카드 24~28dp, 그림자는 아주 약하게) */
    private fun card(radiusDp: Float, padDp: Float = 28f, color: Int = p.card): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color, radiusDp)
        setPadding(dpi(padDp), dpi(padDp * 0.85f), dpi(padDp), dpi(padDp * 0.85f))
        if (!p.night && color == p.card) elevation = dp(2f)
    }

    /** 완전히 둥근 배지: 아이콘 + 글자 */
    private class Pill(val view: LinearLayout, val icon: ImageView, val label: TextView)
    private fun pill(bg: Int): Pill {
        val ic = ImageView(context).apply { layoutParams = LinearLayout.LayoutParams(dpi(30f), dpi(30f)) }
        val tv = text("", 26f, p.text, bold = true)
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(bg, 100f); setPadding(dpi(22f), dpi(10f), dpi(26f), dpi(10f)); minimumHeight = dpi(52f)
            addView(ic); addView(hspace(10f)); addView(tv)
            if (!p.night && bg == p.card) elevation = dp(2f)
        }
        return Pill(v, ic, tv)
    }

    /** 최소 48dp 이상의 큰 버튼 */
    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        letterSpacing = -0.02f; setTextColor(p.onFill)
        val normal = rounded(p.blueFill, 100f)
        val pressed = rounded(p.blueFill, 100f).apply { alpha = 150 }
        background = StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_pressed), pressed); addState(intArrayOf(), normal) }
        setPadding(dpi(56f), 0, dpi(56f), 0); minHeight = dpi(64f); minimumHeight = dpi(64f)
        setOnClickListener { onClick() }
    }

    /** 눈에 덜 띄는 작은 글자 버튼 (글자는 작게, 누르는 영역은 48dp 이상) */
    private fun quietButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setTextColor(p.faint); gravity = Gravity.CENTER
        letterSpacing = -0.02f; setPadding(dpi(20f), dpi(8f), dpi(20f), dpi(8f)); minHeight = dpi(56f); minimumHeight = dpi(56f)
        setOnClickListener { onClick() }
    }

    /** 화면 너비가 좁으면(세로 배치) 줄을 위아래로 쌓는다 */
    private fun narrowAware(row: LinearLayout, onChange: (narrow: Boolean) -> Unit) {
        root.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val narrow = (r - l) < dpi(900f)
            row.orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            onChange(narrow)
        }
    }

    // ------------------------------------------------------------------ 1·2) 운전 중 (안전 / 주의)
    private inner class DrivingView {
        private val pillBadge = pill(p.card)
        private val mateView = mate(R.drawable.mate_default, 300f)
        private val title = text("", 64f, p.text, bold = true)
        private val sub = text("", 28f, p.sub)
        private val bubble = LinearLayout(context)
        private val middle = LinearLayout(context)
        private val distance = text("", 32f, p.text, bold = true)
        private val statusIcon = ImageView(context).apply { layoutParams = LinearLayout.LayoutParams(dpi(36f), dpi(36f)) }
        private val statusText = text("", 32f, p.greenText, bold = true)
        val view: View

        init {
            bubble.apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(28f + 52f), dpi(44f), dpi(52f), dpi(44f))
                addView(title); addView(space(14f)); addView(sub)
            }
            middle.apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                addView(mateView); addView(bubble, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpi(-6f) })
            }
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text(APP_TITLE, 36f, p.text, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(pillBadge.view)
            }
            val bottom = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                addView(text("이번 주행 ", 32f, p.sub)); addView(distance)
                addView(hspace(24f)); addView(text("|", 32f, p.faint)); addView(hspace(24f))
                addView(statusIcon); addView(hspace(10f)); addView(statusText)
            }
            view = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dpi(48f), dpi(36f), dpi(48f), dpi(36f))
                addView(top); addView(middle, LinearLayout.LayoutParams(-1, 0, 1f)); addView(bottom)
            }
            narrowAware(middle) { narrow ->
                val size = if (narrow) 200f else 300f
                mateView.layoutParams = LinearLayout.LayoutParams(dpi(size), dpi(size))
                (bubble.layoutParams as LinearLayout.LayoutParams).apply {
                    width = if (narrow) -1 else 0; weight = if (narrow) 0f else 1f; leftMargin = if (narrow) 0 else dpi(-6f)
                }
            }
        }

        fun bind(s: UiState) {
            val r = s.snap.latest
            val n = r?.mistakeCount ?: 0
            val g = s.snap.graduation
            val caution = s.caution
            distance.text = "${km1(r?.distanceKm ?: 0.0)} km"
            if (caution == null) {
                // ----- 안전 -----
                mateView.setImageResource(R.drawable.mate_default)
                bubble.background = BubbleDrawable(p.card, dp(36f), dp(28f), dp(40f))
                title.text = DRIVING_SAFE_TITLE; title.setTextColor(p.text)
                sub.text = if (s.coachLevel == COACH_LEVEL_CHATTY) DRIVING_SAFE_SUB_CHATTY else DRIVING_SAFE_SUB_QUIET
                sub.setTextColor(p.sub)
                // 상단 배지: 현재 단계 (새싹 / 지켜보기 / 졸업) - 아이콘 + 글자
                pillBadge.view.background = rounded(p.card, 100f)
                pillBadge.icon.setImageResource(if (g.graduated) R.drawable.ic_graduation else if (g.stage <= 1) R.drawable.ic_sprout else R.drawable.ic_eye)
                pillBadge.icon.setColorFilter(if (g.graduated) p.blueText else p.greenText)
                pillBadge.label.text = stageBadgeText(g); pillBadge.label.setTextColor(if (g.graduated) p.blueText else p.greenText)
                // 아래 한 줄: 실수 없으면 초록 체크, 있으면(조용한 코치라 말은 안 했지만) 주황 주의 + 횟수
                if (n == 0) {
                    statusIcon.setImageResource(R.drawable.ic_check); statusIcon.setColorFilter(p.greenText)
                    statusText.text = "실수 없음"; statusText.setTextColor(p.greenText)
                } else {
                    statusIcon.setImageResource(R.drawable.ic_status_caution); statusIcon.setColorFilter(p.orangeText)
                    statusText.text = mistakesText(r!!); statusText.setTextColor(p.orangeText)
                }
            } else {
                // ----- 주의 (경고 직후 10초) -----
                val curve = caution == CAUTION_CURVE
                mateView.setImageResource(R.drawable.mate_alert)
                bubble.background = BubbleDrawable(p.orangeBubble, dp(36f), dp(28f), dp(40f))
                title.text = if (curve) DRIVING_CURVE_TITLE else DRIVING_SPEED_TITLE; title.setTextColor(p.onFill)
                sub.text = if (curve) DRIVING_CURVE_SUB else DRIVING_SPEED_SUB; sub.setTextColor(p.onFill)
                pillBadge.view.background = rounded(p.card, 100f)
                pillBadge.icon.setImageResource(R.drawable.ic_volume); pillBadge.icon.setColorFilter(p.orangeText)
                pillBadge.label.text = BADGE_VOICE; pillBadge.label.setTextColor(p.orangeText)
                statusIcon.setImageResource(R.drawable.ic_status_caution); statusIcon.setColorFilter(p.orangeText)
                statusText.text = if (n == 0) (if (curve) "급커브 1번" else "과속 1번") else mistakesText(r!!)
                statusText.setTextColor(p.orangeText)
            }
        }
    }

    // ------------------------------------------------------------------ 3) 정차(P)
    private inner class ParkedView {
        private val tip = text("", 40f, p.text, bold = true)
        private val road = RoadView(context, p)
        private val dots = DotsView(context, p)
        private val streakValue = text("", 38f, p.text, bold = true)
        private val distValue = text("", 38f, p.text, bold = true)
        private val distBar = ProgressBarView(context, p)
        private val lastTitle = text("", 26f, p.sub)
        private val lastLine1 = text("", 36f, p.text, bold = true)
        private val lastLine2 = text("", 36f, p.text, bold = true)
        private val cards = LinearLayout(context)
        val view: View

        init {
            val big = card(36f, 36f).apply {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(mate(R.drawable.mate_default, 112f)); addView(hspace(24f)); addView(tip, LinearLayout.LayoutParams(0, -2, 1f))
                })
                addView(space(10f)); addView(road, LinearLayout.LayoutParams(-1, dpi(124f)))
            }
            val c1 = card(28f).apply {
                addView(text("실수 없는 주행", 26f, p.sub)); addView(space(18f)); addView(dots); addView(space(18f)); addView(streakValue)
            }
            val c2 = card(28f).apply {
                addView(text("함께 달린 거리", 26f, p.sub)); addView(space(14f)); addView(distValue); addView(space(20f))
                addView(distBar, LinearLayout.LayoutParams(-1, dpi(16f)))
            }
            val c3 = card(28f).apply {
                addView(lastTitle); addView(space(14f)); addView(lastLine1); addView(space(6f)); addView(lastLine2)
            }
            cards.apply { orientation = LinearLayout.HORIZONTAL }
            listOf(c1, c2, c3).forEachIndexed { i, c ->
                cards.addView(c, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i < 2) rightMargin = dpi(20f) })   // 높이는 가장 큰 카드에 맞춘다
            }
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text(APP_TITLE, 36f, p.text, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(quietButton(DEV_INFO_BUTTON, onOpenDev))   // 정차 중에만 보이는 눈에 덜 띄는 버튼
            }
            val bodyBlock = LinearLayout(context).apply {   // 큰 카드 + 카드 3개를 화면 세로 가운데에 모은다
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                addView(big); addView(space(24f)); addView(cards, LinearLayout.LayoutParams(-1, -2))
            }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dpi(48f), dpi(28f), dpi(48f), dpi(32f))
                addView(top); addView(bodyBlock, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            view = ScrollView(context).apply { isFillViewport = true; addView(column, ViewGroup.LayoutParams(-1, -1)) }
            narrowAware(cards) { narrow ->
                for (k in 0 until cards.childCount) {
                    val lp = cards.getChildAt(k).layoutParams as LinearLayout.LayoutParams
                    lp.width = if (narrow) -1 else 0; lp.height = -1; lp.weight = if (narrow) 0f else 1f
                    lp.rightMargin = if (!narrow && k < 2) dpi(20f) else 0; lp.topMargin = if (narrow && k > 0) dpi(16f) else 0
                    cards.getChildAt(k).layoutParams = lp
                }
            }
        }

        fun bind(s: UiState) {
            val g = s.snap.graduation
            val r = s.snap.latest
            tip.text = parkedTip(r, g.graduated)
            // 성장 길: 새싹 -> 지켜보기 -> 졸업 (현재 위치에 파란 점)
            road.setProgress(
                when { g.graduated -> 1f; g.stage >= 3 -> 0.75f; g.stage == 2 -> 0.5f; else -> 0f },
                when { g.graduated -> 2; g.stage >= 2 -> 1; else -> 0 },
            )
            // 카드 1: 실수 없는 주행 (동그라미 5개)
            val streak = g.cleanStreak
            dots.setFilled(minOf(streak, GRADUATE_CLEAN_STREAK), GRADUATE_CLEAN_STREAK)
            streakValue.text = "${streak} / ${GRADUATE_CLEAN_STREAK}번 연속"
            // 카드 2: 함께 달린 거리 (다음 목표까지)
            val goal = when { g.graduated -> GRADUATE_MIN_KM; g.stage == 1 -> STAGE2_MIN_KM; g.stage == 2 -> STAGE3_MIN_KM; else -> GRADUATE_MIN_KM }
            distValue.text = "${km1(g.totalKm)} / ${kmCompact(goal)} km"
            distBar.setFraction((g.totalKm / goal).toFloat().coerceIn(0f, 1f))
            // 카드 3: 지난 주행
            if (r == null) {
                lastTitle.text = "지난 주행"; lastLine1.text = "아직 기록이 없어요"; lastLine1.setTextColor(p.sub); lastLine2.text = ""
            } else {
                lastTitle.text = "지난 주행 · ${km1(r.distanceKm)} km"
                if (r.mistakeCount == 0) {
                    lastLine1.text = "실수 없음"; lastLine1.setTextColor(p.greenText); lastLine2.text = ""
                } else {
                    lastLine1.text = counted("과속", r.speedCount); lastLine2.text = counted("급커브", r.curveCount)
                    lastLine1.setTextColor(p.text); lastLine2.setTextColor(p.text)
                }
            }
        }

        // "과속 1번": 이름은 본문색, 횟수는 주황색(0번이면 보조색)
        private fun counted(name: String, n: Int): CharSequence {
            val sb = SpannableStringBuilder("$name ")
            val start = sb.length
            sb.append("${n}번")
            sb.setSpan(ForegroundColorSpan(if (n > 0) p.orangeText else p.sub), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
    }

    // ------------------------------------------------------------------ 4) 주행 요약 (정차 직후)
    private inner class SummaryView {
        private val bubbleText = text("", 44f, p.text, bold = true)
        private val mateView = mate(R.drawable.mate_cheer, 160f)
        private val cheerBig = text("", 32f, p.text, bold = true)
        private val cheerSub = text("", 26f, p.sub)
        private val list = card(36f, 28f)
        private val tipText = text("", 30f, p.text, bold = true)
        private val body = LinearLayout(context)
        val view: View

        init {
            val back = FrameLayout(context).apply {
                background = circleBg(p.card); if (!p.night) elevation = dp(2f)
                isClickable = true; contentDescription = "닫기"
                addView(icon(R.drawable.ic_arrow_back, p.text, 30f), FrameLayout.LayoutParams(dpi(30f), dpi(30f), Gravity.CENTER))
                layoutParams = LinearLayout.LayoutParams(dpi(60f), dpi(60f))
                setOnClickListener { onDismiss() }
            }
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(back); addView(hspace(20f)); addView(text("오늘의 주행", 36f, p.text, bold = true))
            }
            val bubble = card(36f, 36f).apply { addView(bubbleText) }
            val cheerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(mateView); addView(hspace(20f))
                // 글자 칸은 폭을 "내용만큼"으로 명시한다 (안 그러면 사이 여백 뷰의 폭(1px)을 따라가 글자가 사라진다)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(cheerBig, LinearLayout.LayoutParams(-2, -2)); addView(space(6f)); addView(cheerSub, LinearLayout.LayoutParams(-2, -2))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val left = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(bubble); addView(space(16f)); addView(cheerRow)
            }
            val tipCard = card(28f, 28f, p.blueTint).apply {
                addView(text("다음 주행 팁", 24f, p.blueText, bold = true)); addView(space(8f)); addView(tipText)
            }
            val right = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(list); addView(space(18f)); addView(tipCard)
            }
            body.apply { orientation = LinearLayout.HORIZONTAL }
            body.addView(left, LinearLayout.LayoutParams(0, -2, 1.1f).apply { rightMargin = dpi(24f) })
            body.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
            view = ScrollView(context).apply {
                isFillViewport = true
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dpi(48f), dpi(28f), dpi(48f), dpi(32f))
                    addView(top); addView(space(24f)); addView(body)
                }, ViewGroup.LayoutParams(-1, -1))
            }
            narrowAware(body) { narrow ->
                for (k in 0 until body.childCount) {
                    val lp = body.getChildAt(k).layoutParams as LinearLayout.LayoutParams
                    lp.width = if (narrow) -1 else 0; lp.weight = if (narrow) 0f else (if (k == 0) 1.1f else 1f)
                    lp.rightMargin = if (!narrow && k == 0) dpi(24f) else 0; lp.topMargin = if (narrow && k == 1) dpi(20f) else 0
                    body.getChildAt(k).layoutParams = lp
                }
            }
        }

        private fun row(iconRes: Int, tint: Int, fg: Int, name: String, value: String, valueColor: Int) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(14f), 0, dpi(14f))
            addView(FrameLayout(context).apply {
                background = rounded(tint, 20f)
                addView(icon(iconRes, fg, 34f), FrameLayout.LayoutParams(dpi(34f), dpi(34f), Gravity.CENTER))
                layoutParams = LinearLayout.LayoutParams(dpi(64f), dpi(64f))
            })
            addView(hspace(20f)); addView(text(name, 30f, p.text), LinearLayout.LayoutParams(0, -2, 1f)); addView(text(value, 32f, valueColor, bold = true))
        }

        private fun divider() = View(context).apply { setBackgroundColor(p.track); layoutParams = LinearLayout.LayoutParams(-1, dpi(1f)) }

        fun bind(r: TripResult) {
            val clean = r.mistakeCount == 0
            // 숫자와 "번" 사이에서 줄이 바뀌지 않게 붙임표(U+2060)를 넣는다: "급커브 1번"이 "1 / 번"으로 끊기는 것 방지
            bubbleText.text = buildScreenSummary(r).replace(Regex("(\\d)번"), "$1⁠번")
            val (big, small) = summaryMateLine(r)
            mateView.setImageResource(if (clean) R.drawable.mate_default else R.drawable.mate_cheer)   // 실수 0번이면 웃음, 아니면 격려
            cheerBig.text = big; cheerSub.text = small
            tipText.text = summaryTip(r)
            list.removeAllViews()
            list.addView(row(R.drawable.ic_trip, p.blueTint, p.blueText, "거리 · 시간", buildTripLine(r), p.text))
            list.addView(divider())
            list.addView(row(R.drawable.ic_speed, if (r.speedCount > 0) p.orangeTint else p.greenTint, if (r.speedCount > 0) p.orangeText else p.greenText,
                "과속", "${r.speedCount}번", if (r.speedCount > 0) p.orangeText else p.greenText))
            list.addView(divider())
            list.addView(row(R.drawable.ic_curve, if (r.curveCount > 0) p.orangeTint else p.greenTint, if (r.curveCount > 0) p.orangeText else p.greenText,
                "급커브", "${r.curveCount}번", if (r.curveCount > 0) p.orangeText else p.greenText))
        }
    }

    // ------------------------------------------------------------------ 5) 졸업 축하
    private inner class CelebrationView {
        private val chipKm = text("", 28f, p.text, bold = true)
        private val chipStreak = text("", 28f, p.text, bold = true)
        private val row = LinearLayout(context)
        private val mateView = mate(R.drawable.mate_graduate, 360f)
        val view: View

        init {
            val badge = pill(p.blueFill).apply {
                icon.setImageResource(R.drawable.ic_graduation); icon.setColorFilter(p.onFill)
                label.text = GRAD_BADGE; label.setTextColor(p.onFill)
            }
            fun chip(tv: TextView) = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                background = rounded(p.card, 100f); setPadding(dpi(28f), dpi(14f), dpi(28f), dpi(14f)); minimumHeight = dpi(56f)
                addView(tv); if (!p.night) elevation = dp(2f)
            }
            val chips = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(chip(chipKm)); addView(hspace(14f)); addView(chip(chipStreak))
            }
            val right = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(badge.view, LinearLayout.LayoutParams(-2, -2))
                addView(space(20f)); addView(text(GRAD_TITLE, 60f, p.text, bold = true))
                addView(space(18f)); addView(text(GRAD_SUB, 28f, p.sub))
                addView(space(28f)); addView(chips)
                addView(space(32f)); addView(button("확인", onDismiss), LinearLayout.LayoutParams(-2, -2))
            }
            row.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dpi(56f), dpi(32f), dpi(56f), dpi(32f)) }
            row.addView(mateView); row.addView(hspace(32f)); row.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
            view = FrameLayout(context).apply {
                addView(ConfettiView(context), FrameLayout.LayoutParams(-1, -1))   // 움직이지 않는 색종이 (노랑, 초록, 분홍, 파랑)
                addView(ScrollView(context).apply { isFillViewport = true; addView(row, ViewGroup.LayoutParams(-1, -1)) }, FrameLayout.LayoutParams(-1, -1))
            }
            narrowAware(row) { narrow ->
                val size = if (narrow) 240f else 360f
                mateView.layoutParams = LinearLayout.LayoutParams(dpi(size), dpi(size))
                val lp = row.getChildAt(2).layoutParams as LinearLayout.LayoutParams
                lp.width = if (narrow) -1 else 0; lp.weight = if (narrow) 0f else 1f; row.getChildAt(2).layoutParams = lp
            }
        }

        fun bind(r: TripResult) {
            val g = r.graduation!!.status
            chipKm.text = "함께 달린 거리 ${String.format(java.util.Locale.US, "%.0f", g.totalKm)} km"
            chipStreak.text = "실수 없는 주행 ${g.cleanStreak}번 연속"
        }
    }

    // ------------------------------------------------------------------ 개발자 정보 (정차 중에만, 구성은 그대로)
    private inner class DevView {
        private val rows = LinkedHashMap<String, TextView>()
        val view: View

        init {
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            listOf("속도", "기어", "조향각", "코칭", "기록").forEach { label ->
                val value = text("", 28f, p.text)
                rows[label] = value
                list.addView(card(28f, 32f).apply { addView(text(label, 24f, p.sub)); addView(space(6f)); addView(value) },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(14f) })
            }
            val backButton = TextView(context).apply {
                text = "뒤로"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(p.blueText); background = rounded(p.card, 20f); setPadding(dpi(48f), 0, dpi(48f), 0)
                minHeight = dpi(80f); minimumHeight = dpi(80f); setOnClickListener { onCloseDev() }
            }
            view = ScrollView(context).apply {
                isFillViewport = true
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dpi(40f), dpi(32f), dpi(40f), dpi(32f))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        addView(backButton); addView(hspace(24f)); addView(text(DEV_INFO_BUTTON, 40f, p.text, bold = true))
                    })
                    addView(space(24f)); addView(list)
                }, ViewGroup.LayoutParams(-1, -1))
            }
        }

        fun bind() {
            rows["속도"]?.text = LiveVehicleState.speed
            rows["기어"]?.text = LiveVehicleState.gear
            rows["조향각"]?.text = LiveVehicleState.steering
            rows["코칭"]?.text = LiveVehicleState.coach
            rows["기록"]?.text = LiveVehicleState.log
        }
    }
}

// ====================================================================================
// 직접 그리는 부품들
// ====================================================================================

/** 말풍선: 둥근 사각형 + 왼쪽(메이트 쪽)으로 튀어나온 꼬리 */
private class BubbleDrawable(private val color: Int, private val radius: Float, private val tailW: Float, private val tailH: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@BubbleDrawable.color }
    override fun draw(canvas: Canvas) {
        val b = bounds
        val body = RectF(b.left + tailW, b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        canvas.drawRoundRect(body, radius, radius, paint)
        val cy = (b.top + b.bottom) / 2f
        val tail = Path().apply {
            moveTo(body.left + 4f, cy - tailH / 2); lineTo(b.left.toFloat(), cy); lineTo(body.left + 4f, cy + tailH / 2); close()
        }
        canvas.drawPath(tail, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private fun View.dpf(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
private fun View.spf(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** 성장 길: 가로 막대 위에 정거장 3개(새싹 → 지켜보기 → 졸업), 현재 위치에 파란 점 */
private class RoadView(context: Context, private val p: Palette) : View(context) {
    private var pos = 0f          // 0~1: 파란 점의 위치
    private var stageIdx = 0      // 현재 정거장 번호 (0 새싹, 1 지켜보기, 2 졸업)
    private val labels = listOf("새싹", "지켜보기", "졸업")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun setProgress(position: Float, currentStop: Int) { pos = position; stageIdx = currentStop; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dpf(124f).toInt())

    override fun onDraw(c: Canvas) {
        val padX = dpf(72f); val x0 = padX; val x1 = width - padX
        val ty = dpf(34f); val half = dpf(7f)
        paint.color = p.track; c.drawRoundRect(x0, ty - half, x1, ty + half, half, half, paint)       // 빈 막대
        val dotX = x0 + (x1 - x0) * pos
        paint.color = p.blue; c.drawRoundRect(x0, ty - half, dotX, ty + half, half, half, paint)       // 지나온 길
        for (i in 0..2) {
            val frac = i / 2f
            val x = x0 + (x1 - x0) * frac
            val reached = frac <= pos + 0.001f
            paint.color = p.card; c.drawCircle(x, ty, dpf(21f), paint)                                  // 흰 테두리
            paint.color = if (reached) p.blue else p.track; c.drawCircle(x, ty, dpf(15f), paint)       // 정거장 (지난/현재는 색칠, 다음은 회색)
            textPaint.textSize = spf(26f)
            textPaint.typeface = if (i == stageIdx) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textPaint.color = if (i == stageIdx) p.text else if (reached) p.sub else p.faint
            c.drawText(labels[i], x, dpf(100f), textPaint)
        }
        paint.color = p.card; c.drawCircle(dotX, ty, dpf(19f), paint)                                   // 현재 위치: 파란 점(흰 테두리)
        paint.color = p.blue; c.drawCircle(dotX, ty, dpf(13f), paint)
    }
}

/** 동그라미 N개 중 채운 개수(초록) */
private class DotsView(context: Context, private val p: Palette) : View(context) {
    private var filled = 0
    private var total = 5
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun setFilled(f: Int, t: Int) { filled = f; total = t; requestLayout(); invalidate() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((total * dpf(34f) + (total - 1) * dpf(14f)).toInt(), dpf(40f).toInt())
    override fun onDraw(c: Canvas) {
        val r = dpf(17f)
        for (i in 0 until total) {
            paint.color = if (i < filled) p.green else p.track
            c.drawCircle(r + i * (2 * r + dpf(14f)), height / 2f, r, paint)
        }
    }
}

/** 파란 진행 막대 */
private class ProgressBarView(context: Context, private val p: Palette) : View(context) {
    private var fraction = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun setFraction(f: Float) { fraction = f; invalidate() }
    override fun onDraw(c: Canvas) {
        val r = height / 2f
        paint.color = p.track; c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        if (fraction > 0f) { paint.color = p.blue; c.drawRoundRect(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat(), r, r, paint) }
    }
}

/** 졸업 화면의 색종이: 움직이지 않는 작은 조각들 (노랑, 초록, 분홍, 파랑). 글자 영역은 피해서 흩뿌린다. */
private class ConfettiView(context: Context) : View(context) {
    private class Piece(val x: Float, val y: Float, val size: Float, val rot: Float, val color: Int, val round: Boolean)
    private var pieces: List<Piece> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = listOf("#FFB547", "#2BC48A", "#FF9DB0", "#3478F6").map { Color.parseColor(it) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val rnd = Random(11)
        val list = ArrayList<Piece>()
        while (list.size < 44) {
            val fx = rnd.nextFloat(); val fy = rnd.nextFloat()
            if (fx in 0.40f..0.96f && fy in 0.18f..0.90f) continue   // 오른쪽 글자 영역은 비워 둔다
            list.add(Piece(fx * w, fy * h, dpf(9f + rnd.nextFloat() * 12f), rnd.nextFloat() * 180f, colors[rnd.nextInt(colors.size)], rnd.nextInt(4) == 0))
        }
        pieces = list
    }

    override fun onDraw(c: Canvas) {
        for (pc in pieces) {
            paint.color = pc.color
            if (pc.round) c.drawCircle(pc.x, pc.y, pc.size / 2f, paint)
            else { c.save(); c.rotate(pc.rot, pc.x, pc.y); c.drawRoundRect(pc.x - pc.size / 2, pc.y - pc.size / 4, pc.x + pc.size / 2, pc.y + pc.size / 4, 4f, 4f, paint); c.restore() }
        }
    }
}
