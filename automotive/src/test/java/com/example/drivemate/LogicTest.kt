package com.example.drivemate

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.drivemate.coaching.CoachingRules
import com.example.drivemate.coaching.KeywordAnswerEngine
import com.example.drivemate.coaching.RecordAnswers
import com.example.drivemate.coaching.TripContext
import com.example.drivemate.data.EventEntity
import com.example.drivemate.data.QuestionEntity
import com.example.drivemate.event.DetectedEvent
import com.example.drivemate.event.EventDetector
import com.example.drivemate.model.DriveMode
import com.example.drivemate.model.EventType
import com.example.drivemate.model.Maneuver
import com.example.drivemate.model.ManeuverType
import com.example.drivemate.model.Topic
import com.example.drivemate.model.TurnSignal
import com.example.drivemate.report.CardKind
import com.example.drivemate.report.ReportBuilder
import com.example.drivemate.report.TripComparison
import com.example.drivemate.report.Trend
import com.example.drivemate.ui.theme.DarkColors
import com.example.drivemate.ui.theme.DmColors
import com.example.drivemate.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * PC에서 바로 돌리는 단위 테스트 (에뮬레이터 없이): gradlew :automotive:testDebugUnitTest
 * 설계문서의 표(코칭 규칙, 이벤트 기준, 리포트 처방 규칙, 질문 목록)와 Pleos 가이드의 대비 기준을 확인한다.
 */

/** 4단계 코칭 규칙표 */
class CoachingRulesTest {
    private val right = ManeuverType.RIGHT
    private val left = ManeuverType.LEFT
    private val cross = ManeuverType.INTERSECTION

    @Test fun `우회전은 300m 안으로 들어올 때 한 번만 안내한다`() {
        val rules = CoachingRules()
        assertNull(rules.onNavi(Maneuver(1, right, 400), DriveMode.BEGINNER, emptySet(), 0))
        val c = rules.onNavi(Maneuver(1, right, 300), DriveMode.BEGINNER, emptySet(), 1000)
        assertEquals("곧 우회전이에요. 오른쪽 깜빡이를 켜고, 횡단보도의 보행자를 확인해 주세요.", c?.speech)
        assertNull(rules.onNavi(Maneuver(1, right, 250), DriveMode.BEGINNER, emptySet(), 2000))
    }

    @Test fun `좌회전 문구는 신청서 예시 그대로`() {
        val c = CoachingRules().onNavi(Maneuver(1, left, 290), DriveMode.BEGINNER, emptySet(), 0)
        assertEquals("곧 좌회전 구간이에요. 진행할 차로와 신호를 미리 확인해 주세요.", c?.speech)
    }

    @Test fun `교차로는 연습 주제로 골랐을 때만 200m 전에 안내한다`() {
        assertNull(CoachingRules().onNavi(Maneuver(1, cross, 190), DriveMode.BEGINNER, emptySet(), 0))
        val rules = CoachingRules()
        assertNull(rules.onNavi(Maneuver(1, cross, 250), DriveMode.BEGINNER, setOf(Topic.INTERSECTION), 0))
        assertNotNull(rules.onNavi(Maneuver(1, cross, 200), DriveMode.BEGINNER, setOf(Topic.INTERSECTION), 0))
    }

    @Test fun `일반 모드는 고른 주제만 안내한다`() {
        assertNull(CoachingRules().onNavi(Maneuver(1, right, 300), DriveMode.NORMAL, setOf(Topic.LEFT_TURN), 0))
        assertNotNull(CoachingRules().onNavi(Maneuver(1, right, 300), DriveMode.NORMAL, setOf(Topic.RIGHT_TURN), 0))
    }

    @Test fun `같은 회전 지점은 다시 안내하지 않는다`() {
        val rules = CoachingRules()
        assertNotNull(rules.onNavi(Maneuver(1, right, 300), DriveMode.BEGINNER, emptySet(), 0))
        assertNull(rules.onNavi(Maneuver(1, right, 200), DriveMode.BEGINNER, emptySet(), 60_000))
    }

    @Test fun `다른 회전 지점은 방금 같은 안내를 했어도 안내한다`() {
        val rules = CoachingRules()
        assertNotNull(rules.onNavi(Maneuver(1, left, 80), DriveMode.BEGINNER, emptySet(), 0))
        assertNotNull(rules.onNavi(Maneuver(2, left, 300), DriveMode.BEGINNER, emptySet(), 5_000))  // 5초 뒤 다른 좌회전
    }

    @Test fun `고속도로 합류는 500m 전에 두 모드 모두 안내한다`() {
        val merge = ManeuverType.MERGE
        assertNull(CoachingRules().onNavi(Maneuver(1, merge, 600), DriveMode.NORMAL, emptySet(), 0))
        assertEquals("곧 합류 구간이에요. 뒤차 확인해 주세요.", CoachingRules().onNavi(Maneuver(1, merge, 480), DriveMode.NORMAL, emptySet(), 0)?.speech)
        assertEquals(
            "곧 고속도로 합류 구간이에요. 사이드미러로 뒤차를 확인하고 속도를 맞춰 주세요.",
            CoachingRules().onNavi(Maneuver(1, merge, 200), DriveMode.BEGINNER, emptySet(), 0)?.speech,
        )
    }

    @Test fun `일반 모드는 간단한 문구로 말한다`() {
        val c = CoachingRules().onNavi(Maneuver(1, right, 300), DriveMode.NORMAL, setOf(Topic.RIGHT_TURN), 0)
        assertEquals("곧 우회전이에요. 보행자 확인해 주세요.", c?.speech)
    }

    @Test fun `너무 가까우면 안내하지 않는다`() {
        assertNull(CoachingRules().onNavi(Maneuver(1, right, 40), DriveMode.BEGINNER, emptySet(), 0))
    }
}

/** 4단계 감지 조건: 급가속·급감속(eTAS 기준), 회전 구간의 방향지시등 */
class EventDetectorTest {
    @Test fun `1초에 시속 7_5km 이상 줄면 급감속`() {
        val d = EventDetector()
        assertTrue(d.onSample(50f, null, emptySet(), 0).isEmpty())
        assertTrue(d.onSample(43f, null, emptySet(), 1000).isEmpty())            // 7km 감소: 아님
        val events = d.onSample(35f, null, emptySet(), 2000)                     // 8km 감소: 급감속
        assertEquals(listOf(DetectedEvent.HardBrake(43f, nearTurn = false)), events)
        assertTrue(d.onSample(26f, null, emptySet(), 3000).isEmpty())            // 이어지는 감속은 다시 기록하지 않음
    }

    @Test fun `1초에 시속 11km 이상 늘면 급가속이고 정차 직후면 출발 때로 표시`() {
        val d = EventDetector()
        d.onSample(0f, null, emptySet(), 0)
        val events = d.onSample(12f, null, emptySet(), 1000)
        assertEquals(listOf(DetectedEvent.HardAccel(12f, fromStop = true)), events)

        val d2 = EventDetector()
        d2.onSample(40f, null, emptySet(), 0)
        assertTrue(d2.onSample(50f, null, emptySet(), 1000).isEmpty())           // 10km 증가: 아님
        d2.onSample(50f, null, emptySet(), 2000)
        assertEquals(listOf(DetectedEvent.HardAccel(62f, fromStop = false)), d2.onSample(62f, null, emptySet(), 3000))
    }

    @Test fun `교차로 100m 안의 급감속은 nearTurn`() {
        val d = EventDetector()
        d.onSample(50f, Maneuver(1, ManeuverType.INTERSECTION, 90), emptySet(), 0)
        val events = d.onSample(40f, Maneuver(1, ManeuverType.INTERSECTION, 78), emptySet(), 1000)
        assertEquals(listOf(DetectedEvent.HardBrake(50f, nearTurn = true)), events)
    }

    @Test fun `회전 100m 안에서 깜빡이를 켰는지 기록한다`() {
        val d = EventDetector()
        d.onSample(40f, Maneuver(1, ManeuverType.RIGHT, 150), setOf(TurnSignal.RIGHT), 0)      // 100m 밖에서 켠 것은 세지 않음
        d.onSample(40f, Maneuver(1, ManeuverType.RIGHT, 90), emptySet(), 1000)
        d.onSample(38f, Maneuver(1, ManeuverType.RIGHT, 10), emptySet(), 2000)
        val passed = d.onSample(36f, Maneuver(2, ManeuverType.LEFT, 500), emptySet(), 3000)
        assertEquals(listOf(DetectedEvent.TurnPassed(ManeuverType.RIGHT, signalUsed = false, speedKmh = 36f)), passed)

        d.onSample(36f, Maneuver(2, ManeuverType.LEFT, 80), setOf(TurnSignal.LEFT), 4000)     // 100m 안에서 켬
        val passed2 = d.onSample(36f, null, emptySet(), 5000)
        assertEquals(listOf(DetectedEvent.TurnPassed(ManeuverType.LEFT, signalUsed = true, speedKmh = 36f, signalsSeen = setOf(TurnSignal.LEFT))), passed2)
    }

    @Test fun `10초 안에 시속 5km 이상 변화가 방향을 바꿔 3번이면 불규칙한 속도 변화`() {
        val d = EventDetector()
        d.onSample(30f, null, emptySet(), 0)
        assertTrue(d.onSample(36f, null, emptySet(), 1000).isEmpty())   // +6
        assertTrue(d.onSample(30f, null, emptySet(), 2000).isEmpty())   // -6
        assertEquals(listOf(DetectedEvent.IrregularSpeed(36f)), d.onSample(36f, null, emptySet(), 3000))   // +6 -> 3번째
    }

    @Test fun `같은 방향으로 천천히 가속하는 것은 불규칙이 아니다`() {
        val d = EventDetector()
        var speed = 0f
        for (t in 0..12) {
            assertTrue(d.onSample(speed, null, emptySet(), t * 1000L).none { it is DetectedEvent.IrregularSpeed })
            speed += 5f   // 1초에 5km씩 (데모 출발 방식)
        }
    }

    @Test fun `방향이 바뀌어도 10초가 넘게 벌어지면 세지 않는다`() {
        val d = EventDetector()
        d.onSample(30f, null, emptySet(), 0)
        d.onSample(36f, null, emptySet(), 1000)                              // +6
        for (t in 2..11) d.onSample(36f, null, emptySet(), t * 1000L)        // 10초 유지
        d.onSample(30f, null, emptySet(), 12_000)                            // -6
        assertTrue(d.onSample(36f, null, emptySet(), 13_000).none { it is DetectedEvent.IrregularSpeed })
    }

    @Test fun `반대쪽 깜빡이만 켰으면 작동 기록으로 세지 않는다`() {
        val d = EventDetector()
        d.onSample(40f, Maneuver(1, ManeuverType.RIGHT, 80), setOf(TurnSignal.LEFT), 0)   // 우회전인데 왼쪽 깜빡이
        val passed = d.onSample(40f, null, emptySet(), 1000)
        assertEquals(
            listOf(DetectedEvent.TurnPassed(ManeuverType.RIGHT, signalUsed = false, speedKmh = 40f, signalsSeen = setOf(TurnSignal.LEFT))),
            passed,
        )
    }

    @Test fun `교차로 직진은 회전 이벤트로 기록하지 않는다`() {
        val d = EventDetector()
        d.onSample(40f, Maneuver(1, ManeuverType.INTERSECTION, 50), emptySet(), 0)
        assertTrue(d.onSample(40f, null, emptySet(), 1000).isEmpty())
    }
}

/** 리포트 처방 규칙 */
class ReportBuilderTest {
    private fun turn(direction: String, signal: Boolean) =
        EventEntity(tripId = 1, time = 0, type = EventType.TURN.name, speedKmh = 30f, turnSignal = "OFF", brake = false, direction = direction, signalUsed = signal)
    private fun brake(near: Boolean) =
        EventEntity(tripId = 1, time = 0, type = EventType.HARD_BRAKE.name, speedKmh = 50f, turnSignal = "OFF", brake = true, nearTurn = near)
    private fun accel(fromStop: Boolean) =
        EventEntity(tripId = 1, time = 0, type = EventType.HARD_ACCEL.name, speedKmh = 15f, turnSignal = "OFF", brake = false, fromStop = fromStop)
    private fun question(topic: String?) =
        QuestionEntity(tripId = 1, time = 0, question = "q", answer = "a", topic = topic)

    @Test fun `깜빡이 기록 없음 2번이면 카드와 다음 목표가 된다`() {
        val report = ReportBuilder.build(listOf(turn("RIGHT", false), turn("RIGHT", false), turn("RIGHT", true)), emptyList())
        assertEquals("우회전 3번 중 2번은 깜빡이 기록이 없었어요", report.cards[0].fact)
        assertEquals("내비가 300m를 알려주면 바로 켜 보세요", report.cards[0].tip)
        assertEquals("우회전 전 깜빡이 미리 켜기", report.goal.title)
        assertEquals("오늘 3번 중 2번 기록 없음", report.goal.detail)
    }

    @Test fun `여러 개가 해당하면 횟수가 가장 많은 것이 목표`() {
        val events = listOf(turn("RIGHT", false), turn("RIGHT", false), brake(true), brake(true), brake(true), accel(true))
        val report = ReportBuilder.build(events, emptyList())
        assertEquals(listOf(CardKind.SIGNAL, CardKind.BRAKE, CardKind.ACCEL), report.cards.map { it.kind })
        assertEquals("회전·교차로 앞에서 미리 속도 줄이기", report.goal.title)
        assertEquals("회전·교차로 앞에서 급감속이 3번 있었어요", report.cards[1].fact)
    }

    @Test fun `횟수가 같으면 표의 위쪽 줄이 목표`() {
        val events = listOf(turn("LEFT", false), turn("LEFT", false), brake(true), brake(true))
        assertEquals("좌회전 전 깜빡이 미리 켜기", ReportBuilder.build(events, emptyList()).goal.title)
    }

    @Test fun `위 세 가지가 없고 질문이 있으면 질문 주제가 목표`() {
        val report = ReportBuilder.build(emptyList(), listOf(question("비보호 좌회전")))
        assertEquals("오늘 비보호 좌회전을 물어보셨어요", report.cards[0].fact)
        assertEquals("다음엔 비보호 구간이 있는 길로 연습해 봐요", report.cards[0].tip)
        assertEquals("비보호 좌회전 연습하기", report.goal.title)
    }

    @Test fun `아무것도 없으면 코칭 줄이기를 제안한다`() {
        val report = ReportBuilder.build(listOf(turn("RIGHT", true)), listOf(question(null)))
        assertEquals("오늘은 기록된 급가속·급감속이 없어요", report.cards.single().fact)
        assertEquals("다음엔 코칭을 조금 줄여 볼까요?", report.cards.single().tip)
    }

    @Test fun `규칙에 없는 급감속이 있으면 없다고 말하지 않는다`() {
        val report = ReportBuilder.build(listOf(brake(false)), emptyList())
        assertEquals(CardKind.OTHER_EVENTS, report.cards.single().kind)
        assertEquals("주행 중 급감속 1번이 기록됐어요", report.cards.single().fact)
    }

    @Test fun `이전 주행과 비교 - 줄면 칭찬, 늘면 사실만, 둘 다 0이면 뺀다`() {
        val prev = listOf(turn("RIGHT", false), turn("RIGHT", false), turn("RIGHT", false), brake(true))
        val now = listOf(turn("RIGHT", false), brake(true), accel(true))
        val rows = TripComparison.compare(prev, now)
        assertEquals(listOf("깜빡이 기록 없음", "급감속", "급가속"), rows.map { it.label })   // 불규칙 속도는 둘 다 0이라 빠짐
        assertEquals("지난번보다 2번 줄었어요", rows[0].text)
        assertEquals(Trend.FEWER, rows[0].trend)
        assertEquals("지난번과 같아요", rows[1].text)
        assertEquals("지난번보다 1번 많았어요", rows[2].text)
    }

    @Test fun `조사는 받침에 따라 고른다`() {
        assertEquals("을", ReportBuilder.josa("차선 변경", "을", "를"))
        assertEquals("를", ReportBuilder.josa("황색 신호", "을", "를"))
    }
}

/** 1차 질문 목록 */
class AnswerEngineTest {
    private val engine = KeywordAnswerEngine()

    @Test fun `질문 예시 5개가 모두 맞는 답을 찾는다`() {
        assertEquals("비보호 좌회전", engine.answer("비보호 좌회전은 언제 해", emptySet()).topic)
        assertEquals("우회전", engine.answer("우회전 할 때 멈춰야 해", emptySet()).topic)
        assertEquals("차선 변경", engine.answer("차선 변경은 어떻게 해", emptySet()).topic)
        assertEquals("황색 신호", engine.answer("노란 불에서는 어떻게 해", emptySet()).topic)
        assertEquals(
            "오늘 연습 주제는 좌회전, 교차로예요.",
            engine.answer("오늘 뭐 연습해", setOf(Topic.INTERSECTION, Topic.LEFT_TURN)).text,
        )
    }

    @Test fun `비보호를 잘못 알아들은 실제 인식 결과도 비보호 답을 준다`() {
        assertEquals("비보호 좌회전", engine.answer("그 정도 좌회전은 언제 해", emptySet()).topic)
    }

    @Test fun `그 정도만 있고 좌회전이 없으면 비보호 답을 주지 않는다`() {
        assertEquals(null, engine.answer("그 정도면 괜찮아", emptySet()).topic)
    }

    // ---------- 주행 기록 기반 질문 (신청서 3-③, 4-⑤) ----------
    private fun turn(direction: String, used: Boolean, side: String, time: Long) = EventEntity(
        tripId = 1, time = time, type = EventType.TURN.name, speedKmh = 30f, turnSignal = "OFF", brake = false,
        direction = direction, signalUsed = used, signalSide = side,
    )
    private fun ctx(vararg events: EventEntity, questions: List<QuestionEntity> = emptyList()) =
        TripContext(events.toList(), questions, live = false)

    @Test fun `우회전 깜빡이 질문 - 작동 기록이 있으면 사실과 판단 불가를 함께 말한다`() {
        val a = engine.answer("아까 우회전할 때 깜빡이 켰어", emptySet(), ctx(turn("RIGHT", true, "RIGHT", 10)))
        assertEquals(null, a.topic)   // 기록 질문은 "질문 주제"로 세지 않는다
        assertEquals("가장 최근 우회전 구간에서 오른쪽 방향지시등이 작동한 기록이 있어요. ${RecordAnswers.CANNOT_JUDGE}", a.text)
    }

    @Test fun `우회전 깜빡이 질문 - 가장 최근 우회전을 고르고 반대쪽이면 그렇게 말한다`() {
        val a = engine.answer("우회전 깜빡이 켰어?", emptySet(), ctx(
            turn("RIGHT", true, "RIGHT", 10), turn("LEFT", false, "", 20), turn("RIGHT", false, "LEFT", 30),
        ))
        assertTrue(a.text.startsWith("가장 최근 우회전 구간에서는 반대쪽 방향지시등 기록만 있어요."))
    }

    @Test fun `깜빡이 질문 - 기록이 없으면 없다고 말한다`() {
        assertEquals("이번 주행에는 우회전 기록이 없어요.", engine.answer("우회전할 때 깜빡이 켰어", emptySet(), ctx()).text)
        assertTrue(engine.answer("깜빡이 켰어", emptySet(), null).text.startsWith("아직 주행 기록이 없어요"))
    }

    @Test fun `부족했던 점 질문 - 리포트 첫 카드의 사실 + 판단 불가`() {
        val a = engine.answer("오늘 부족했던 점은 뭐야", emptySet(), ctx(turn("RIGHT", false, "", 10), turn("RIGHT", false, "", 20)))
        assertEquals("이번 주행에서 우회전 2번 중 2번은 깜빡이 기록이 없었어요. ${RecordAnswers.CANNOT_JUDGE}", a.text)
    }

    @Test fun `더 연습할 부분 질문 - 다음 목표와 질문 주제, 어려웠던 곳을 함께 말한다`() {
        val difficult = EventEntity(tripId = 1, time = 5, type = EventType.DIFFICULT.name, speedKmh = 20f, turnSignal = "OFF", brake = false, difficult = true)
        val q = QuestionEntity(tripId = 1, time = 6, question = "비보호", answer = "", topic = "비보호 좌회전")
        val a = engine.answer("오늘 내가 어떤 부분을 더 연습하면 좋을까", emptySet(), ctx(
            turn("RIGHT", false, "", 10), turn("RIGHT", false, "", 20), difficult, questions = listOf(q),
        ))
        assertEquals("다음엔 우회전 전 깜빡이 미리 켜기를 연습해 봐요. 오늘 물어본 비보호 좌회전도 함께 연습하면 좋아요. 어려웠다고 표시한 곳도 1곳 있어요.", a.text)
    }

    @Test fun `기록 질문이 아니면 기존 답변을 그대로 쓴다`() {
        assertEquals("우회전", engine.answer("우회전 할 때 멈춰야 해", emptySet(), ctx()).topic)
    }

    @Test fun `목록에 없는 질문은 정차 후 다시 물어봐 달라고 한다`() {
        assertEquals("그 질문은 정차 후 다시 물어봐 주세요.", engine.answer("오늘 날씨 어때", emptySet()).text)
    }
}

/** Pleos 디자인 가이드: 글자 대비 4.5:1 이상 (주간·야간 색 묶음 모두) */
class ThemeContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = if (a.luminance() > b.luminance()) a to b else b to a
        return (hi.luminance() + 0.05f) / (lo.luminance() + 0.05f)
    }

    private fun check(name: String, c: DmColors) {
        val pairs = mapOf(
            "본문/배경" to (c.text to c.background), "본문/카드" to (c.text to c.card),
            "보조/배경" to (c.textSub to c.background), "보조/카드" to (c.textSub to c.card),
            "파랑 글자/카드" to (c.accentText to c.card), "파랑 글자/배경" to (c.accentText to c.background),
            "초록 글자/카드" to (c.goodText to c.card), "주황 글자/카드" to (c.cautionText to c.card),
            "버튼 글자/버튼" to (c.onAccentFill to c.accentFill),
        )
        pairs.forEach { (label, pair) ->
            val ratio = contrast(pair.first, pair.second)
            println("$name $label = ${"%.2f".format(ratio)}:1")
            assertTrue("$name $label 대비 $ratio", ratio >= 4.5f)
        }
    }

    @Test fun `주간 색 대비`() = check("주간", LightColors)
    @Test fun `야간 색 대비`() = check("야간", DarkColors)
}
