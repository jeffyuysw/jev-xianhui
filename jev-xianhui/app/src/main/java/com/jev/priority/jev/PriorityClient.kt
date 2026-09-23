package com.jev.priority.jev

import android.util.Log
import com.jev.priority.core.MsgItem
import com.jev.priority.core.Prefs
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The judgment route only. One call scores a single message on all four
 * questions; there is nothing generative here, so this stays fast and cheap.
 */
class PriorityClient(private val prefs: Prefs) {

    /** Throws on transport failure; the caller records it on the item. */
    fun judge(item: MsgItem) {
        val body = JSONObject()
            .put("model", prefs.model)
            .put("state", PriorityQuestions.state(item))
            .put("questions", PriorityQuestions.questions())

        val resp = HttpJson.post(prefs.endpoint, prefs.apiKey, body)
        val answers = resp.optJSONObject("answers")
            ?: throw IllegalStateException("响应里没有 answers 字段")

        val level = answers.optJSONObject("urgency_level")
        val raw = level?.optDouble("score", 0.0) ?: 0.0
        item.urgency = raw.roundToInt().coerceIn(1, 9)
        item.confidence = level?.optDouble("confidence", 0.0) ?: 0.0

        // Jev returns noul as the probability of "true".
        val noul = answers.optJSONObject("needs_reply_now")?.optDouble("noul", 0.0) ?: 0.0
        item.needsNow = noul >= 0.5

        item.category = answers.optJSONObject("category")?.optString("choice", "").orEmpty()
        item.reason = answers.optJSONObject("why_urgent")?.optString("choice", "").orEmpty()
        item.judged = true
        item.error = null

        Log.i(TAG, "judged ${item.title}: urgency=${item.urgency} now=${item.needsNow} " +
            "category=${item.category} why=${item.reason} conf=${item.confidence}")
    }

    /** Connectivity probe used by the settings screen. */
    fun probe(): String {
        val probeItem = MsgItem(
            key = "probe", pkg = "", appName = "微信",
            title = "同事", text = "方案今天下班前能发我吗？老板在等", timeMs = 0
        )
        judge(probeItem)
        return "紧急度 ${probeItem.urgency}/9 · ${if (probeItem.needsNow) "要马上回" else "可以晚点"}" +
            " · 类型 ${probeItem.category} · 把握 ${(probeItem.confidence * 100).roundToInt()}%"
    }

    companion object { private const val TAG = "JEVPRIORITY" }
}
