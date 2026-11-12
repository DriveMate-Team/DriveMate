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
    /**
     * @param trip 지금 주행(또는 방금 끝난 주행)의 기록. 기록 기반 질문("아까 깜빡이 켰어?")에 쓴다. 없으면 null
     */
    fun answer(question: String, topics: Set<Topic>, trip: TripContext? = null): Answer
}

/**
 * 1차 질문 목록: 질문에서 키워드를 찾아 미리 쓴 답변을 돌려준다. (설계문서 4단계 "1차 질문 목록")
 *
 * 답변 문구는 팀에서 도로교통법 기준으로 검토해 확정했다 (2026-11-13).
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
            "비보호 좌회전 표지가 있는 곳에서, 차량 신호가 녹색일 때만 할 수 있어요. 마주 오는 차와 보행자를 방해하지 않을 때만 돌고, 빨간불에는 안 돼요.",
        ),
        Entry(
            "우회전", listOf("우회전"),
            "차량 신호가 빨간불이면 정지선 앞에서 꼭 멈춘 뒤 우회전하세요. 초록불이어도 횡단보도에 사람이 건너거나 건너려 하면 멈춰서 기다려요. 우회전 신호등이 있으면 그 신호를 따르세요.",
        ),
        Entry(
            "차선 변경", listOf("차선", "차로변경", "차로를바꾸", "끼어들"),
            "바꾸려는 곳 30미터 전부터 깜빡이를 켜고(고속도로는 100미터), 사이드미러와 어깨 너머로 뒤를 확인한 뒤 천천히 들어가세요.",
        ),
        Entry(
            "황색 신호", listOf("황색", "노란불", "노란신호"),
            "정지선 전이면 멈추고, 이미 교차로에 들어갔다면 빠르게 빠져나가세요.",
        ),
    )

    override fun answer(question: String, topics: Set<Topic>, trip: TripContext?): Answer {
        val q = question.replace(" ", "")   // 띄어쓰기는 음성 인식마다 달라서 없애고 비교한다

        // 1) 주행 기록에 대한 질문을 먼저 본다. ("우회전할 때 깜빡이 켰어?"는 "우회전" 일반 질문보다 먼저 걸러야 한다)
        //    기록 질문은 "질문한 주제"로 세지 않는다 (topic = null)
        RecordAnswers.answer(q, trip)?.let { return Answer(null, it) }

        // 2) 일반 운전 질문 (미리 쓴 답변)
        entries.firstOrNull { e -> e.keywords.any { it in q } }?.let { return Answer(it.topic, it.answer) }

        // "오늘 뭐 연습해?" -> 주행 준비에서 고른 주제를 넣어 답한다 (리포트의 질문 주제로는 세지 않는다)
        if ("연습" in q || "목표" in q) {
            val label = topics.toLabel()
            // 받침이 있으면 "이에요", 없으면 "예요" (예: "차선 변경이에요" / "교차로예요")
            return Answer(null, "오늘 연습 주제는 $label${ReportBuilder.josa(label, "이에요", "예요")}.")
        }
        // 주차 후(리포트 화면)에는 "정차 후 다시"가 맞지 않으므로 물어볼 수 있는 것을 안내한다
        return Answer(null, if (trip?.live == false) UNKNOWN_ANSWER_PARKED else UNKNOWN_ANSWER)
    }

    companion object {
        /** 목록에 없는 질문일 때의 답 (설계문서 4단계) */
        const val UNKNOWN_ANSWER = "그 질문은 정차 후 다시 물어봐 주세요."

        /** 주차 후 목록에 없는 질문일 때의 답 (2026-11-13 결정) */
        const val UNKNOWN_ANSWER_PARKED = "그 질문은 아직 답할 수 없어요. 오늘 주행 기록이나 운전 방법을 물어봐 주세요."
    }
}
