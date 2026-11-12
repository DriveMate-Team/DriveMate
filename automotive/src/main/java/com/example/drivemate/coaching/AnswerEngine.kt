package com.example.drivemate.coaching

import com.example.drivemate.model.Topic
import com.example.drivemate.model.toLabel
import com.example.drivemate.report.ReportBuilder

/**
 * 질문에 대한 답 한 건.
 * @param topic 어떤 주제의 질문이었는지 (리포트의 "오늘 ○○을 물어보셨어요"에 쓴다). 모르는 질문이면 null
 * @param text 음성으로 읽을 답변
 */
data class Answer(val topic: String?, val text: String)

/**
 * "질문 -> 답변"을 만드는 통로.
 * 1차 버전은 아래 KeywordAnswerEngine(미리 쓴 답변)만 쓰고,
 * 2차에 Gleo AI나 LLM SDK로 바꿀 때는 이 통로를 구현한 새 클래스로 갈아 끼우면 된다. (설계문서 1단계 결정 사항)
 */
interface AnswerEngine {
    fun answer(question: String, topics: Set<Topic>): Answer
}

/**
 * 1차 질문 목록: 질문에서 키워드를 찾아 미리 쓴 답변을 돌려준다. (설계문서 4단계 "1차 질문 목록")
 *
 * 주의: 답변 문구는 팀에서 도로교통법과 대조해 한 번 검토한 뒤 확정해야 한다.
 */
class KeywordAnswerEngine : AnswerEngine {

    private data class Entry(val topic: String, val keywords: List<String>, val answer: String)

    // 위에 있는 줄이 먼저 검사된다. "비보호 좌회전"처럼 겹치는 말은 더 구체적인 것을 위에 둔다.
    private val entries = listOf(
        Entry(
            // 뒤의 낱말들은 음성 인식이 "비보호"를 잘못 알아들은 실제 결과 (띄어쓰기 없앤 형태).
            // 에뮬레이터 확인: "비보호 좌회전은 언제 해?" -> "그 정도 좌회전은 언제 해" (2026-11-13)
            // 오인식 표현은 반드시 "좌회전"과 붙은 형태로만 넣는다 ("그 정도"만으로는 다른 질문과 헷갈린다).
            // 새 오인식이 나오면 로그의 HEARD 문장을 보고 여기에 추가한다.
            "비보호 좌회전", listOf("비보호", "그정도좌회전", "비보좌회전", "비오호좌회전", "비보우좌회전"),
            "차량 신호가 녹색일 때, 마주 오는 차와 보행자를 방해하지 않을 때만 할 수 있어요. 안전하지 않다면 기다리세요.",
        ),
        Entry(
            "우회전", listOf("우회전"),
            "우회전 전에는 일단 멈춰 보행자를 확인하세요. 횡단보도에 사람이 있으면 기다려요.",
        ),
        Entry(
            "차선 변경", listOf("차선", "차로변경", "차로를바꾸", "끼어들"),
            "깜빡이를 켜고, 사이드미러와 어깨 너머로 뒤를 확인한 뒤 천천히 들어가세요.",
        ),
        Entry(
            "황색 신호", listOf("황색", "노란불", "노란신호"),
            "정지선 전이면 멈추고, 이미 교차로에 들어갔다면 빠르게 빠져나가세요.",
        ),
    )

    override fun answer(question: String, topics: Set<Topic>): Answer {
        val q = question.replace(" ", "")   // 띄어쓰기는 음성 인식마다 달라서 없애고 비교한다
        entries.firstOrNull { e -> e.keywords.any { it in q } }?.let { return Answer(it.topic, it.answer) }

        // "오늘 뭐 연습해?" -> 주행 준비에서 고른 주제를 넣어 답한다 (리포트의 질문 주제로는 세지 않는다)
        if ("연습" in q || "목표" in q) {
            val label = topics.toLabel()
            // 받침이 있으면 "이에요", 없으면 "예요" (예: "차선 변경이에요" / "교차로예요")
            return Answer(null, "오늘 연습 주제는 $label${ReportBuilder.josa(label, "이에요", "예요")}.")
        }
        return Answer(null, UNKNOWN_ANSWER)
    }

    companion object {
        /** 목록에 없는 질문일 때의 답 (설계문서 4단계) */
        const val UNKNOWN_ANSWER = "그 질문은 정차 후 다시 물어봐 주세요."
    }
}
