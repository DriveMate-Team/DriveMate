package com.example.drivemate.shared

import org.json.JSONArray
import org.json.JSONObject

// ====================================================================================
// [6단계] 운전자 질문 답변 -- 함수 이름과 문구를 바꾸고 싶으면 "이 부분만" 고치면 된다.
//  Gleo가 운전자의 말을 아래 함수 중 하나로 바꿔서 우리 앱을 호출한다. (schema.json의 함수 이름과 같아야 함)
// ====================================================================================
const val FN_STATUS = "GET_DRIVING_STATUS"          // "지금 나 괜찮게 하고 있어?"
const val FN_DISTANCE = "GET_TRIP_DISTANCE"         // "얼마나 달렸어?"
const val FN_GRADUATION = "GET_GRADUATION_PROGRESS" // "졸업까지 얼마나 남았어?"

const val ANSWER_NO_TRIP = "아직 주행 기록이 없어요. 출발하면 같이 볼게요."
const val ANSWER_UNKNOWN = "잘 이해하지 못했어요. 다시 한 번 물어봐 주세요."
const val ANSWER_GRADUATED = "이미 졸업했어요! 정말 대단해요."
// ====================================================================================

/**
 * Gleo에게 돌려줄 답.
 * @param spoken  우리 앱의 TTS가 직접 말할 한국어 답 (코치 말투를 유지하려고 Gleo가 아니라 우리가 말한다)
 * @param english Gleo 콜백의 message. 문서가 "영어 문장"을 요구해서 영어로 쓴다 (silent 모드라 Gleo는 말하지 않음)
 * @param data    Gleo 콜백의 data (JSON 배열 글자). Gleo가 알아야 할 핵심 값만 담는다
 * @param status  "success" 또는 "fail"
 */
data class GleoAnswer(val spoken: String, val english: String, val data: String, val status: String = "success")

/** 함수 이름과 저장된 주행 기록으로 답을 만든다. (계산만 하는 순수 함수라 따로 테스트하기 쉽다) */
fun answerFor(function: String, snap: DriveLogSnapshot): GleoAnswer = when (function) {
    FN_STATUS -> answerStatus(snap)
    FN_DISTANCE -> answerDistance(snap)
    FN_GRADUATION -> answerGraduation(snap)
    else -> GleoAnswer(ANSWER_UNKNOWN, "The requested function is not supported.", "[]", status = "fail")
}

private fun noTripAnswer() = GleoAnswer(ANSWER_NO_TRIP, "There is no driving record yet.", "[]")

private fun dataOf(vararg pairs: Pair<String, Any>): String =
    JSONArray().put(JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }).toString()

// "지금 나 괜찮게 하고 있어?" : 이번(또는 지난) 주행의 과속/급커브 기록으로 평가
private fun answerStatus(snap: DriveLogSnapshot): GleoAnswer {
    val r = snap.latestResult() ?: return noTripAnswer()
    val inProgress = !r.trip.closed
    val spoken = when {
        r.mistakeCount == 0 -> if (inProgress) "지금까지 아주 잘하고 있어요!" else "지난 주행은 아주 잘했어요!"
        r.mistakeCount == 1 -> "${mistakeParts(r)} 빼고는 아주 좋아요!"
        else -> "${mistakeParts(r)} 있었어요. 조금만 더 신경 써봐요."
    }
    val which = if (inProgress) "current" else "most recent"
    return GleoAnswer(
        spoken,
        "On the $which trip there were ${r.speedCount} speeding and ${r.curveCount} sharp curve mistakes.",
        dataOf("speedingCount" to r.speedCount, "sharpCurveCount" to r.curveCount, "tripInProgress" to inProgress),
    )
}

// "얼마나 달렸어?" : 이번(또는 지난) 주행 거리
private fun answerDistance(snap: DriveLogSnapshot): GleoAnswer {
    val r = snap.latestResult() ?: return noTripAnswer()
    val inProgress = !r.trip.closed
    val km = km1(r.distanceKm)
    val spoken = if (inProgress) "이번에 지금까지 ${km}킬로미터 달렸어요." else "지난 주행은 ${km}킬로미터였어요."
    val which = if (inProgress) "current" else "most recent"
    return GleoAnswer(
        spoken,
        "The $which trip distance is $km kilometers.",
        dataOf("distanceKm" to km, "tripInProgress" to inProgress),
    )
}

// "졸업까지 얼마나 남았어?" : 졸업 단계와 남은 조건
private fun answerGraduation(snap: DriveLogSnapshot): GleoAnswer {
    val s = snap.graduationStatus()
    if (s.graduated) {
        return GleoAnswer(
            ANSWER_GRADUATED,
            "The driver has already graduated from the driving coach.",
            dataOf("graduated" to true, "totalKm" to km1(s.totalKm)),
        )
    }
    val needStreak = maxOf(0, GRADUATE_CLEAN_STREAK - s.cleanStreak)
    val needKm = maxOf(0.0, GRADUATE_MIN_KM - s.totalKm)
    val needs = listOfNotNull(
        if (needStreak > 0) "깨끗한 주행 ${needStreak}번 더" else null,
        if (needKm > 0) "${km1(needKm)}킬로미터 더" else null,
    )
    val spoken = "지금은 ${stageName(s.stage)} 단계예요." +
        if (needs.isNotEmpty()) " 졸업까지 ${needs.joinToString(", ")} 필요해요." else ""
    return GleoAnswer(
        spoken,
        "Graduation progress: stage ${s.stage}, ${s.cleanStreak} of $GRADUATE_CLEAN_STREAK clean trips in a row, ${km1(s.totalKm)} of ${km1(GRADUATE_MIN_KM)} kilometers.",
        dataOf("stage" to s.stage, "cleanStreak" to s.cleanStreak, "totalKm" to km1(s.totalKm), "graduated" to false),
    )
}
