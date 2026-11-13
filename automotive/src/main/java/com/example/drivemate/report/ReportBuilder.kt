package com.example.drivemate.report

import com.example.drivemate.data.EventEntity
import com.example.drivemate.data.QuestionEntity
import com.example.drivemate.model.EventType

/** 처방 카드의 종류 (화면에서 아이콘·색을 고를 때 쓴다) */
enum class CardKind { SIGNAL, BRAKE, ACCEL, OTHER_EVENTS, QUESTION, CLEAN }

/**
 * 처방 카드 한 장: "무엇이 있었고(fact) -> 다음엔 어떻게 해 볼지(tip)"
 */
data class ReportCard(val kind: CardKind, val fact: String, val tip: String)

/** 다음 연습 목표 (항상 하나만) */
/**
 * @param detail 시점 말("오늘", "지난 주행에서")이 없는 사실 한 문장. 리포트는 "이번 주행에서 ", 홈은 "지난 주행에서 "를 앞에 붙인다
 */
data class Goal(val title: String, val detail: String)

/** 리포트 화면에 필요한 계산 결과 */
data class Report(val cards: List<ReportCard>, val goal: Goal)

/**
 * 설계문서 "리포트 처방 규칙" 표를 그대로 옮긴 곳.
 *
 * 원칙: 실제 도로 상황을 모르므로 "잘못했다"고 말하지 않고 기록된 사실만 말한다.
 * 안드로이드와 상관없는 순수한 계산이라 PC에서 단위 테스트로 확인할 수 있다.
 */
object ReportBuilder {

    private const val GOAL_MIN_COUNT = 2   // 2번 이상이면 "다음 목표" 후보가 된다 (설계문서 표)
    private const val MAX_CARDS = 3        // 처방 카드는 1~3장

    // 목표 후보: 카드 + 횟수 + 그 카드가 목표가 됐을 때의 문구
    private data class Candidate(val card: ReportCard, val count: Int, val goal: Goal)

    fun build(events: List<EventEntity>, questions: List<QuestionEntity>): Report {
        val candidates = listOfNotNull(signalRule(events), brakeRule(events), accelRule(events))
        val otherCard = otherEventsRule(events)
        val questionTopic = questions.firstNotNullOfOrNull { it.topic }
        val questionCard = questionTopic?.let { questionRule(it) }

        val cards = (candidates.map { it.card } + listOfNotNull(otherCard, questionCard)).take(MAX_CARDS)

        // 아무것도 없는 주행: 코칭 줄이기를 제안한다
        if (cards.isEmpty()) {
            return Report(
                cards = listOf(
                    ReportCard(
                        CardKind.CLEAN,
                        "오늘은 기록된 급가속·급감속이 없어요",
                        "다음엔 코칭을 조금 줄여 볼까요?",
                    )
                ),
                goal = Goal("코칭을 조금 줄여 보기", "기록된 급가속·급감속이 없었어요"),
            )
        }

        // 다음 목표는 항상 하나만:
        //  1) 2번 이상인 후보 중 횟수가 가장 많은 것 (같으면 표의 위쪽 줄 = 목록의 앞쪽)
        //  2) 위 세 가지 카드가 하나도 없고 질문한 주제가 있으면 그 주제
        //  3) 1번뿐인 후보라도 있으면 그중 가장 많은 것
        //  4) 그 밖의 급가속·급감속만 있을 때
        val strong = candidates.filter { it.count >= GOAL_MIN_COUNT }.maxByOrNull { it.count }   // maxBy는 같으면 앞쪽을 고른다
        val goal = when {
            strong != null -> strong.goal
            candidates.isEmpty() && questionTopic != null ->
                Goal("$questionTopic 연습하기", "$questionTopic${josa(questionTopic, "을", "를")} 물어보셨어요")
            candidates.isNotEmpty() -> candidates.maxBy { it.count }.goal
            questionTopic != null -> Goal("$questionTopic 연습하기", "$questionTopic${josa(questionTopic, "을", "를")} 물어보셨어요")
            else -> Goal("속도를 천천히 바꾸기", "급가속·급감속 기록이 있었어요")
        }
        return Report(cards, goal)
    }

    // 규칙 1: 회전 전 100m 안에 방향지시등 기록 없음
    private fun signalRule(events: List<EventEntity>): Candidate? {
        val turns = events.filter { it.type == EventType.TURN.name }
        val missedRight = turns.count { it.direction == "RIGHT" && it.signalUsed == false }
        val missedLeft = turns.count { it.direction == "LEFT" && it.signalUsed == false }
        if (missedRight + missedLeft == 0) return null

        // 한쪽 방향에서만 기록이 없었으면 그 방향 이름으로, 양쪽 다면 "회전"으로 말한다
        val (name, total, missed) = when {
            missedLeft == 0 -> Triple("우회전", turns.count { it.direction == "RIGHT" }, missedRight)
            missedRight == 0 -> Triple("좌회전", turns.count { it.direction == "LEFT" }, missedLeft)
            else -> Triple("회전", turns.size, missedRight + missedLeft)
        }
        // 회전 방향과 같은 쪽 기록이 없던 회전을 둘로 나눠 사실대로 쓴다 (2026-11-13 팀 요청)
        //  - 아무 깜빡이 기록도 없음 / 반대쪽 깜빡이 기록만 있음
        val missedTurns = turns.filter { it.signalUsed == false && (name == "회전" || it.direction == if (name == "우회전") "RIGHT" else "LEFT") }
        val opposite = missedTurns.count { !it.signalSide.isNullOrBlank() }
        val none = missed - opposite
        val fact = when {
            opposite == 0 -> "$name ${total}번 중 ${missed}번은 깜빡이 기록이 없었어요"
            none == 0 -> "$name ${total}번 중 ${opposite}번은 반대쪽 방향지시등 기록이 있었어요"
            else -> "$name ${total}번 중 ${none}번은 깜빡이 기록이 없었고, ${opposite}번은 반대쪽 방향지시등 기록이 있었어요"
        }
        return Candidate(
            card = ReportCard(
                CardKind.SIGNAL,
                fact,
                "회전 지점 30미터 전에는 꼭 켜 두세요",
            ),
            count = missed,
            goal = Goal("$name 전 깜빡이 미리 켜기", fact),
        )
    }

    // 규칙 2: 급감속이 회전·교차로 100m 안에서 생김
    private fun brakeRule(events: List<EventEntity>): Candidate? {
        val count = events.count { it.type == EventType.HARD_BRAKE.name && it.nearTurn == true }
        if (count == 0) return null
        return Candidate(
            card = ReportCard(
                CardKind.BRAKE,
                // 기록은 "회전·교차로 100m 안"만 알고 어느 쪽인지는 모르므로, 사실대로 둘 다 적는다 (2026-11-12 결정)
                "회전·교차로 앞에서 급감속이 ${count}번 있었어요",
                "회전·교차로 안내를 들으면 발을 가속 페달에서 떼 보세요",
            ),
            count = count,
            goal = Goal("회전·교차로 앞에서 미리 속도 줄이기", "회전·교차로 앞 급감속이 ${count}번 있었어요"),
        )
    }

    // 규칙 3: 급가속이 정차 후 출발 때 생김
    private fun accelRule(events: List<EventEntity>): Candidate? {
        val count = events.count { it.type == EventType.HARD_ACCEL.name && it.fromStop == true }
        if (count == 0) return null
        return Candidate(
            card = ReportCard(
                CardKind.ACCEL,
                "출발할 때 속도가 빨리 올랐어요",
                "처음 3초는 가속 페달을 살짝만 눌러 보세요",
            ),
            count = count,
            goal = Goal("출발할 때 천천히 가속하기", "출발할 때 급가속이 ${count}번 있었어요"),
        )
    }

    // (설계문서 표에 없는 보충) 위 규칙에 해당하지 않는 급가속·급감속이 있으면 사실만 알려준다.
    // 이게 없으면 급감속이 있었는데도 "기록된 급가속·급감속이 없어요"라고 잘못 말하게 된다.
    private fun otherEventsRule(events: List<EventEntity>): ReportCard? {
        val brakes = events.count { it.type == EventType.HARD_BRAKE.name && it.nearTurn != true }
        val accels = events.count { it.type == EventType.HARD_ACCEL.name && it.fromStop != true }
        val irregular = events.count { it.type == EventType.IRREGULAR_SPEED.name }
        if (brakes + accels + irregular == 0) return null
        val parts = listOfNotNull(
            if (brakes > 0) "급감속 ${brakes}번" else null,
            if (accels > 0) "급가속 ${accels}번" else null,
            if (irregular > 0) "불규칙한 속도 변화 ${irregular}번" else null,
        )
        return ReportCard(
            CardKind.OTHER_EVENTS,
            "주행 중 ${parts.joinToString(", ")}이 기록됐어요",
            "아래 이벤트 목록에서 어디였는지 확인해 보세요",
        )
    }

    // 규칙 4: 질문한 주제가 있음
    private fun questionRule(topic: String): ReportCard {
        val tip = if (topic == "비보호 좌회전") "다음엔 비보호 구간이 있는 길로 연습해 봐요"
        else "다음엔 $topic${josa(topic, "이", "가")} 있는 길로 연습해 봐요"
        return ReportCard(CardKind.QUESTION, "오늘 $topic${josa(topic, "을", "를")} 물어보셨어요", tip)
    }

    /** 받침이 있으면 첫 번째 조사(을/이), 없으면 두 번째 조사(를/가)를 고른다 */
    internal fun josa(word: String, withFinal: String, withoutFinal: String): String {
        val last = word.lastOrNull() ?: return withoutFinal
        if (last !in '가'..'힣') return withoutFinal
        return if ((last - '가') % 28 != 0) withFinal else withoutFinal
    }
}
