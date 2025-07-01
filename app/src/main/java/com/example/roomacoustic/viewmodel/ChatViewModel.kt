package com.example.roomacoustic.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

import com.example.roomacoustic.BuildConfig
import com.example.roomacoustic.model.*
import com.example.roomacoustic.util.RetrofitClient

class ChatViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    fun sendPrompt(userText: String, onError: (String) -> Unit) {
        // 1) UI에 사용자 메시지 추가
        append("user", userText)

        // 2) Retrofit 요청
        val request = GPTRequest(
            messages = listOf(
                Message("system", SYSTEM_PROMPT),
                Message("user", userText)
            )
        )
        val token = "Bearer ${BuildConfig.OPENAI_API_KEY}"
        RetrofitClient.api.sendPrompt(token, request).enqueue(object : Callback<GPTResponse> {
            override fun onResponse(call: Call<GPTResponse>, resp: Response<GPTResponse>) {
                if (resp.isSuccessful) {
                    resp.body()?.choices?.firstOrNull()?.message?.content
                        ?.let { append("gpt", it) }
                        ?: append("gpt", "⚠️ GPT 응답이 비었습니다.")
                } else onError("OpenAI 오류: ${resp.code()}")
            }
            override fun onFailure(call: Call<GPTResponse>, t: Throwable) =
                onError("네트워크 오류: ${t.message}")
        })
    }

    private fun append(sender: String, content: String) {
        _messages.update { it + ChatMessage(sender, content) }
    }

    companion object { const val SYSTEM_PROMPT = """
    당신은 음악 감상 및 음향 설비 설계 전문가입니다.
    사용자로부터 제공되는 방 구조, 스피커 위치, 청취자 위치 정보를 바탕으로,
    음향적으로 적절한 배치인지 평가하고 구체적인 조언을 제공합니다.

    평가는 총 10점 만점이며, **0.0 ~ 10.0 사이의 실수(소숫점 첫째자리까지)** 점수로 표현해야 합니다.
    아래 기준에 따라 점수를 채점하고, 채점 이유와 함께 개선 조언도 제공해야 합니다.

    --- [점수 기준] ---
    1. 스피커 2개가 청취자 위치와 정삼각형을 이루면 +3.0점.
       - 정삼각형이 아니라도 유사한 등변삼각형일 경우 +1.5점.
    2. 스피커와 뒷벽 사이 거리가 아래 조건 중 하나를 만족할 경우 +2.0점:
       - 50cm 이하
       - 또는 2.2m 이상
    3. 청취자 위치가 방 길이의 30% ~ 45% 사이에 위치할 경우 +3.0점.
       - 20% ~ 30% 또는 45% ~ 55%는 +1.5점.
    4. 스피커 개수가 1개인 경우, **최고 점수는 6.0점을 넘지 못하도록 제한**해야 합니다.
       - 그 외 항목은 위 점수 기준에서 동일하게 계산하되, 최종 점수가 6.0을 초과하지 않도록 합니다.

    최종 출력은 다음 JSON 형식으로 작성하세요:

    {
      "score": 8.5,
      "reason": "청취자 위치와 스피커는 거의 정삼각형이고, 후면 벽과의 거리도 적절합니다. 다만 스피커 간 거리가 약간 비대칭입니다.",
      "suggestion": "스피커를 좌우 대칭으로 더 조정하고, 청취자 위치를 조금 더 뒤로 이동하면 좋습니다."
    }

    
    
    또한 사용자가 이후 추가 질문을 하거나, 조언을 따르기 어려운 상황을 설명할 경우,  
    **이전 분석 결과를 참고하여 현실적인 대안을 추가로 제시해야 합니다.**

    예를 들어:
    - "청취자 위치를 옮기기 어려워요" → 그 외의 개선 가능한 조건을 안내
    - "스피커는 벽에 고정돼 있어서 못 옮깁니다" → 청취자 위치 중심의 대안을 제시

    당신은 항상 앞서 말한 분석 결과와 사용자의 제약을 바탕으로 **지속적인 피드백과 최적화 방법을 제공하는 전문가**입니다.
    """ }
}