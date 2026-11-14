package com.example.drivemate.coaching

import com.example.drivemate.report.Stage
import com.example.drivemate.data.EventEntity
import com.example.drivemate.data.QuestionEntity
import com.example.drivemate.model.EventType
import com.example.drivemate.report.ReportBuilder

/**
 * 질문에 답할 때 함께 넘겨주는 "주행 기록". (신청서 3-③, 4-⑤: 자신의 주행 기록을 바탕으로 한 질문)
 * @param live 지금 주행 중이면 true, 주차 후 방금 끝난 주행이면 false (답의 말투만 조금 달라진다)
 */
data class TripContext(
    val events: List<EventEntity>,
    val questions: List<QuestionEntity>,
    val live: Boolean,
    val nextStage: Stage? = null, // 연수 로드맵의 지금 단계 (다음 목표 답에 쓴다)
)

/**
 * 주행 기록으로 답하는 질문들.
 *
 * 답의 규칙 (2026-11-14):
 *  1) "기록된 사실 -> 다음에 해볼 것" 순서로, 두 문장 안에서 말한다.
 *  2) "기록만으로는 판단할 수 없어요"는 운전 동작이 적절했는지를 물을 때만 붙인다 (CANNOT_JUDGE).
 *     질문 기록, 급가속·급감속 횟수, 깜빡이 기록에는 붙이지 않는다.
 *  3) "이번 주행에서 오늘"처럼 겹치는 말을 쓰지 않는다.
 *
 * 안드로이드와 상관없는 순수한 계산이라 PC에서 단위 테스트로 확인할 수 있다.
 */
object RecordAnswers {

    /** 운전 동작의 적절성을 물을 때만 덧붙이는 문장 */
    const val CANNOT_JUDGE = "다만 실제 도로 상황에 비춰 적절했는지는 기록만으로는 판단할 수 없어요."

    /** 깜빡이를 켜 두라고 권하는 거리 (리포트 카드 "회전 지점 30미터 전"과 같은 값) */
    const val SIGNAL_TIP_M = 30

    /** 이 질문이 주행 기록에 대한 질문이면 답을, 아니면 null을 돌려준다. q는 띄어쓰기를 없앤 질문 */
    fun answer(q: String, ctx: TripContext?): String? = when {
        isSignalQuestion(q) -> signalAnswer(q, ctx)
        isWeaknessQuestion(q) -> weaknessAnswer(ctx)
        isNextPracticeQuestion(q) -> nextPracticeAnswer(ctx)
        else -> null
    }

    // "아까 우회전할 때 깜빡이 켰어?" / "방향지시등 켰나?"
    private fun isSignalQuestion(q: String) =
        ("깜빡이" in q || "방향지시등" in q || "깜박이" in q) && ("켰" in q || "했" in q || "썼" in q)

    // "오늘 부족했던 점은 뭐야?" / "뭘 잘못했어?"
    private fun isWeaknessQuestion(q: String) =
        listOf("부족", "잘못했", "실수", "못했", "고칠", "아쉬운").any { it in q }

    // "오늘 내가 어떤 부분을 더 연습하면 좋을까?"
    private fun isNextPracticeQuestion(q: String) =
        listOf("더연습", "연습하면좋", "어떤부분", "다음에뭘", "다음엔뭘", "뭘더").any { it in q }

    private fun noRecord(ctx: TripContext?): String? = when {
        ctx == null -> "아직 주행 기록이 없어요. 주행을 시작하면 기록해 둘게요."
        else -> null
    }

    private fun signalAnswer(q: String, ctx: TripContext?): String {
        noRecord(ctx)?.let { return it }
        val want = when {
            "우회전" in q || "오른" in q -> "RIGHT"
            "좌회전" in q || "왼" in q -> "LEFT"
            else -> null   // 방향을 말하지 않으면 가장 최근 회전
        }
        val name = when (want) { "RIGHT" -> "우회전"; "LEFT" -> "좌회전"; else -> "회전" }
        val turn = ctx!!.events
            .filter { it.type == EventType.TURN.name && (want == null || it.direction == want) }
            .maxByOrNull { it.time }
            ?: return "${if (ctx.live) "아직" else "이번 주행에는"} $name 기록이 없어요."

        val turnName = if (turn.direction == "LEFT") "좌회전" else "우회전"
        val side = if (turn.direction == "LEFT") "왼쪽" else "오른쪽"
        val dist = turn.signalDistM
        return when {
            turn.signalUsed == true && dist != null && dist >= SIGNAL_TIP_M ->
                "네, $turnName ${dist}미터 전에 $side 깜빡이를 켜셨어요. ${SIGNAL_TIP_M}미터 전 기준보다 여유 있게 잘 켜셨어요."
            turn.signalUsed == true && dist != null ->
                "네, $turnName ${dist}미터 전에 $side 깜빡이를 켜셨어요. 조금 늦었어요. 다음엔 ${SIGNAL_TIP_M}미터 전에 켜 보세요."
            turn.signalUsed == true ->   // 거리를 저장하기 전의 기록
                "네, $turnName 전에 $side 깜빡이를 켜셨어요. 다음에도 회전 ${SIGNAL_TIP_M}미터 전에는 꼭 켜 두세요."
            !turn.signalSide.isNullOrBlank() ->
                "${turnName}할 때 반대쪽 깜빡이 기록이 있어요. 다음엔 $side 깜빡이를 켜 보세요."
            else ->
                "${turnName}할 때 깜빡이 기록이 없었어요. 다음엔 회전 ${SIGNAL_TIP_M}미터 전에 켜 보세요."
        }
    }

    /** 고칠 점 하나: 기록된 사실 + 다음에 해볼 것 + 리포트의 다음 목표 이름 */
    private data class Issue(val fact: String, val tip: String, val count: Int)

    // 고칠 점 후보 (횟수가 같으면 앞쪽 = 깜빡이 > 회전·교차로 앞 급감속 > 출발 급가속 > 그 밖의 급가속·급감속)
    private fun issues(events: List<EventEntity>): List<Issue> {
        val list = mutableListOf<Issue>()
        val turns = events.filter { it.type == EventType.TURN.name }
        val missed = turns.filter { it.signalUsed == false }
        if (missed.isNotEmpty()) {
            val dirs = missed.mapNotNull { it.direction }.toSet()
            val name = when (dirs.singleOrNull()) { "RIGHT" -> "우회전"; "LEFT" -> "좌회전"; else -> "회전" }
            val total = when (name) { "우회전" -> turns.count { it.direction == "RIGHT" }; "좌회전" -> turns.count { it.direction == "LEFT" }; else -> turns.size }
            val opposite = missed.count { !it.signalSide.isNullOrBlank() }
            list += if (opposite == missed.size) Issue(
                "$name ${total}번 중 ${missed.size}번은 반대쪽 깜빡이 기록이 있었어요.",
                "다음엔 회전하는 쪽 깜빡이를 ${SIGNAL_TIP_M}미터 전에 켜 보세요.", missed.size,
            ) else Issue(
                "$name ${total}번 중 ${missed.size - opposite}번은 깜빡이 기록이 없었어요.",
                "다음엔 회전 ${SIGNAL_TIP_M}미터 전에 켜 보세요.", missed.size,
            )
        }
        // 기록은 "회전·교차로 100m 안"까지만 알아서 어느 쪽인지 사실대로 둘 다 말한다 (2026-11-12 결정)
        val nearBrakes = events.count { it.type == EventType.HARD_BRAKE.name && it.nearTurn == true }
        if (nearBrakes > 0) list += Issue(
            "회전·교차로 앞에서 급감속이 ${nearBrakes}번 있었어요.",
            "다음엔 안내를 들으면 미리 발을 떼 보세요.", nearBrakes,
        )
        val startAccels = events.count { it.type == EventType.HARD_ACCEL.name && it.fromStop == true }
        if (startAccels > 0) list += Issue(
            "출발할 때 급가속이 ${startAccels}번 있었어요.",
            "다음엔 처음 3초는 가속 페달을 살짝만 눌러 보세요.", startAccels,
        )
        val brakes = events.count { it.type == EventType.HARD_BRAKE.name && it.nearTurn != true }
        val accels = events.count { it.type == EventType.HARD_ACCEL.name && it.fromStop != true }
        val irregular = events.count { it.type == EventType.IRREGULAR_SPEED.name }
        if (brakes + accels + irregular > 0) {
            val parts = listOfNotNull(
                if (brakes > 0) "급감속 ${brakes}번" else null,
                if (accels > 0) "급가속 ${accels}번" else null,
                if (irregular > 0) "불규칙한 속도 변화 ${irregular}번" else null,
            )
            list += Issue("${parts.joinToString(", ")}이 기록됐어요.", "다음엔 속도를 천천히 바꿔 보세요.", brakes + accels + irregular)
        }
        return list
    }

    // 고칠 점이 없을 때: 사실 + (질문한 주제 또는 로드맵 지금 단계)
    private fun cleanAnswer(ctx: TripContext): String {
        val topic = ctx.questions.firstNotNullOfOrNull { it.topic }
        val next = when {
            topic != null -> "$topic${ReportBuilder.josa(topic, "이", "가")} 궁금하셨으니, 다음엔 그 구간이 있는 길에서 연습해 봐요."
            ctx.nextStage != null -> "다음엔 ${ctx.nextStage.label}${ReportBuilder.josa(ctx.nextStage.label, "을", "를")} 연습해 봐요."
            else -> "다음엔 코칭을 조금 줄여 봐요."
        }
        return "오늘은 급가속·급감속 없이 부드럽게 달렸어요. $next"
    }

    private fun weaknessAnswer(ctx: TripContext?): String {
        noRecord(ctx)?.let { return it }
        val top = issues(ctx!!.events).maxByOrNull { it.count }   // 같으면 앞쪽
            ?: return cleanAnswer(ctx)
        return "${top.fact} ${top.tip}"
    }

    // "더 연습할 부분은?": 가장 많은 고칠 점의 사실 + 리포트의 다음 목표
    private fun nextPracticeAnswer(ctx: TripContext?): String {
        noRecord(ctx)?.let { return it }
        val top = issues(ctx!!.events).maxByOrNull { it.count }
            ?: return cleanAnswer(ctx)
        val goal = ReportBuilder.build(ctx.events, ctx.questions, ctx.nextStage).goal.title
        return "${top.fact} 다음엔 $goal${ReportBuilder.josa(goal, "을", "를")} 연습해 봐요."
    }
}
