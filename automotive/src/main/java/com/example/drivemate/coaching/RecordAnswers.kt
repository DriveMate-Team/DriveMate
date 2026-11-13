package com.example.drivemate.coaching

import com.example.drivemate.report.Stage
import com.example.drivemate.data.EventEntity
import com.example.drivemate.data.QuestionEntity
import com.example.drivemate.model.EventType
import com.example.drivemate.report.CardKind
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
 * 원칙 (신청서 4-④, 4-⑤): 기록된 "사실"만 말하고, 잘했는지·적절했는지는 판단하지 않는다.
 * 판단이 필요한 부분은 반드시 "기록만으로는 판단할 수 없어요"라고 덧붙인다.
 *
 * 안드로이드와 상관없는 순수한 계산이라 PC에서 단위 테스트로 확인할 수 있다.
 */
object RecordAnswers {

    /** 판단할 수 없을 때 덧붙이는 문장 (신청서 4-⑤ 문구를 따름) */
    const val CANNOT_JUDGE = "다만 실제 도로 상황에 비춰 적절했는지는 기록만으로는 판단할 수 없어요."

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
            ?: return "${if (ctx.live) "이번 주행에는 아직" else "이번 주행에는"} $name 기록이 없어요."

        val turnName = if (turn.direction == "LEFT") "좌회전" else "우회전"
        val sameSide = if (turn.direction == "LEFT") "왼쪽" else "오른쪽"
        val seen = turn.signalSide.orEmpty()
        val fact = when {
            turn.signalUsed == true -> "가장 최근 $turnName 구간에서 $sameSide 방향지시등이 작동한 기록이 있어요."
            seen.isNotBlank() -> "가장 최근 $turnName 구간에서는 반대쪽 방향지시등 기록만 있어요."
            else -> "가장 최근 $turnName 구간 100미터 전부터는 방향지시등 기록이 없어요."
        }
        return "$fact $CANNOT_JUDGE"
    }

    private fun weaknessAnswer(ctx: TripContext?): String {
        noRecord(ctx)?.let { return it }
        val report = ReportBuilder.build(ctx!!.events, ctx.questions, ctx.nextStage)
        val card = report.cards.first()
        val prefix = if (ctx.live) "지금까지 " else "이번 주행에서 "
        return if (card.kind == CardKind.CLEAN) {
            "${prefix}기록된 급가속이나 급감속, 깜빡이 누락은 없어요. $CANNOT_JUDGE"
        } else {
            "$prefix${card.fact}. $CANNOT_JUDGE"
        }
    }

    private fun nextPracticeAnswer(ctx: TripContext?): String {
        noRecord(ctx)?.let { return it }
        val report = ReportBuilder.build(ctx!!.events, ctx.questions, ctx.nextStage)
        val parts = mutableListOf("다음엔 ${report.goal.title}${ReportBuilder.josa(report.goal.title, "을", "를")} 연습해 봐요.")
        // 이번 주행에서 질문했던 주제와 "어려웠다"고 표시한 곳도 함께 알려준다 (신청서 4-⑤)
        val asked = ctx.questions.mapNotNull { it.topic }.distinct().filter { it !in report.goal.title }
        if (asked.isNotEmpty()) parts += "오늘 물어본 ${asked.joinToString(", ")}도 함께 연습하면 좋아요."
        val difficult = ctx.events.count { it.difficult }
        if (difficult > 0) parts += "어려웠다고 표시한 곳도 ${difficult}곳 있어요."
        return parts.joinToString(" ")
    }
}
