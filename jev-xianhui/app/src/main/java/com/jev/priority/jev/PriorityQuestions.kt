package com.jev.priority.jev

import com.jev.priority.core.MsgItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Jev question set for message triage.
 *
 * Jev only answers choice / score / true-false — it never writes prose. So the
 * four questions below have to carry the whole judgment: how urgent, whether it
 * needs a reply now, what kind of message it is, and why it is pressing.
 *
 * Instructions and criteria stay in English (Jev's main training language);
 * the message text in [state] keeps its original Chinese.
 */
object PriorityQuestions {

    /** Shared tail: state fields are given context, not off-topic noise. */
    private const val NOTE = " Facts given in the state are provided context, not off-topic."

    private fun noul(instructions: String, t: String, f: String) = JSONObject().apply {
        put("type", "noul")
        put("instructions", instructions + NOTE)
        put("criteria", JSONObject().put("true", t).put("false", f))
    }

    private fun choice(instructions: String, criteria: Map<String, String>) = JSONObject().apply {
        put("type", "choice")
        put("instructions", instructions + NOTE)
        put("criteria", JSONObject().also { c -> criteria.forEach { (k, v) -> c.put(k, v) } })
    }

    private fun score(instructions: String, levels: List<String>) = JSONObject().apply {
        put("type", "score")
        put("instructions", instructions + NOTE)
        put("criteria", JSONArray().also { a -> levels.forEach { a.put(it) } })
    }

    fun state(item: MsgItem): JSONObject = JSONObject()
        .put("app", item.appName)
        .put("conversation", item.title)
        .put("message", item.text)

    /**
     * All four questions in one request — Jev answers them together in about a
     * second, which is what makes per-message triage cheap enough to run on
     * every incoming notification.
     */
    fun questions(): JSONObject = JSONObject().apply {
        put("urgency_level", score(
            "How urgently does this message need the recipient's attention right now? " +
                "Judge only what this message actually shows: its content, any deadline or time " +
                "pressure it contains, and whether another person is waiting on the recipient. " +
                "Do not invent context that is not present.",
            listOf(
                "Pure noise: advertising, marketing pushes, subscription notices, verification codes, " +
                    "or system alerts. No human is waiting for a reply.",
                "Low priority: casual chatter, emoji reactions, memes, or broadcast content " +
                    "nobody expects an answer to.",
                "Informational: news, sharing, or general updates. No action is needed today.",
                "Routine: everyday coordination that can comfortably be answered within several hours.",
                "Worth attention: someone asks something concrete and reasonably expects a reply today, " +
                    "but nothing breaks if it waits a few hours.",
                "Time-sensitive: contains a specific time, place, or deadline today, or someone's " +
                    "next step depends on this answer.",
                "Urgent: an explicit deadline within hours, another person is blocked waiting, " +
                    "or a commitment already made is at risk.",
                "Very urgent: money, payments, account or security issues, health, travel, or a broken " +
                    "commitment. Delay causes real loss or real damage to trust.",
                "Emergency: accident, medical, safety, or a crisis that must be handled immediately."
            )
        ))
        put("needs_reply_now", noul(
            "Does this message require the recipient to reply or act within the next few minutes?",
            "Someone is actively waiting for an answer, there is a live deadline, another person's " +
                "work or plan is blocked until this is answered, or the sender's tone clearly demands " +
                "prompt acknowledgement.",
            "It can safely wait hours or until tomorrow; nobody is blocked; it is informational, " +
                "promotional, automated, or a one-way broadcast."
        ))
        put("category", choice(
            "What kind of message is this? Choose the single best fit.",
            linkedMapOf(
                "work_blocking" to "Work or business: a task, approval, deliverable, or decision " +
                    "that another person is waiting on.",
                "personal" to "Personal: from family, a partner, or a close friend. Carries emotional " +
                    "weight or relationship meaning.",
                "logistics" to "Logistics: arranging a time, place, meeting, delivery, or plan.",
                "info_only" to "Information only: an FYI, announcement, or share. No reply is expected.",
                "promotion" to "Promotion: advertising, marketing, subscription, or shopping pushes.",
                "system" to "System: verification codes, app alerts, delivery updates, or automated notices.",
                "money" to "Money: payment, transfer, invoice, refund, or anything financial."
            )
        ))
        put("why_urgent", choice(
            "What makes this message pressing, if anything? " +
                "Choose none when there is no real time pressure.",
            linkedMapOf(
                "deadline" to "It carries an explicit time limit or deadline.",
                "someone_waiting" to "Another person cannot proceed until the recipient answers.",
                "emotional" to "The sender is upset, anxious, or needs to be acknowledged emotionally.",
                "money_risk" to "Money, an account, security, health, or safety is at risk.",
                "none" to "No real pressure. It can wait without consequence."
            )
        ))
    }
}
