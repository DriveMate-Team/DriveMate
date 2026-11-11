package com.example.drivemate.shared

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * [6단계] Gleo AI가 우리 앱 함수를 호출하면(브로드캐스트) 여기로 들어온다.
 *
 * Gleo 문서 규칙:
 *  - 받는 인텐트: action = 함수 이름(schema.json의 name, 예: GET_DRIVING_STATUS), 추가 데이터 "ACTION_PARAM"(JSON 글자)
 *  - 응답: action "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE" 브로드캐스트,
 *          extras "packageName" + "response"(콜백 JSON). Gleo는 7초 안에 응답이 없으면 취소한다.
 *
 * 우리 앱의 처리 순서:
 *  1) 저장된 주행 기록(JSON 파일)을 읽어서 답을 만든다 (화면이 꺼져 있어도 동작)
 *  2) Gleo에게 "silent" 모드로 먼저 응답한다 (Gleo는 말하지 않음 -> 7초 제한 안전)
 *  3) 우리 앱의 TTS가 코치 말투로 한국어 답을 직접 말한다
 *     - 화면이 켜져 있으면 그 코치(DriveCoach)로, 꺼져 있으면 일회용 TTS(OneShotSpeaker)로 말한다
 */
class GleoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val function = intent.action ?: return
        val param = intent.getStringExtra("ACTION_PARAM") ?: "{}"
        Log.i(TAG, "Gleo 호출 수신: function=$function param=$param")

        // 말하기가 끝날 때까지 이 수신기가 죽지 않게 한다 (OneShotSpeaker가 끝나면 finish)
        val pending = goAsync()
        try {
            val answer = answerFor(function, readDriveLog(context))
            Log.i(TAG, "답변 생성: status=${answer.status} spoken=${answer.spoken}")

            // 1) Gleo에게 먼저 응답 (silent: 말은 우리가 한다)
            sendGleoResponse(context, function, answer.status, answer.english, mode = "silent", data = answer.data)

            // 2) 우리가 말한다
            val coach = DriveCoach.active
            if (coach != null && coach.speakAnswer(answer.spoken)) {
                Log.i(TAG, "화면의 코치로 말하기")
                pending.finish()
            } else {
                Log.i(TAG, "일회용 TTS로 말하기")
                OneShotSpeaker(context.applicationContext).speak(answer.spoken) { pending.finish() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "처리 중 오류: $e")
            sendGleoResponse(context, function, "fail", "An error occurred while processing the request.")
            pending.finish()
        }
    }

    // Gleo에게 응답을 보낸다 (문서의 sendGleoBroadcast와 같은 형식)
    private fun sendGleoResponse(
        context: Context,
        functionName: String,
        status: String,
        message: String,
        mode: String? = null,
        data: String? = null,
    ) {
        val json = JSONObject().apply {
            put("functionName", functionName)
            put("status", status)
            put("message", message)
            mode?.let { put("mode", it) }
            data?.let { put("data", it) }
        }
        val response = Intent(ACTION_APP_ACTION_RESPONSE).apply {
            putExtra("packageName", context.packageName)
            putExtra("response", json.toString())
        }
        context.sendBroadcast(response)
        Log.i(TAG, "Gleo에 응답 전송: $json")
    }

    companion object {
        private const val TAG = "DMGleo"
        const val ACTION_APP_ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    }
}
