package com.safir.ai.humanoid

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class AiReply(
    val reply: String,
    val behavior: SpeechBehavior,
    val memories: List<Pair<String, String>> = emptyList(),
    val forgets: List<String> = emptyList(),
)

class AiReplyClient(
    private val endpoint: String = "https://wggaygghychkwhxvshfp.supabase.co/functions/v1/ai-assist",
) {
    fun request(
        text: String,
        memoryContext: String = "",
        onSuccess: (AiReply) -> Unit,
        onError: (String) -> Unit,
    ) {
        thread(name = "safir-ai-reply") {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6_000
                    readTimeout = 14_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $EVREN_PUBLISHABLE_KEY")
                    setRequestProperty("apikey", EVREN_PUBLISHABLE_KEY)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }

                val systemPrompt = buildString {
                    append("You are Safir AI Humanoid, a fast voice assistant. ")
                    append("Answer naturally in the user's language. Keep normal answers very short: usually 1-2 sentences. ")
                    append("When answering in Romanian, use correct Romanian grammar and diacritics. Prefer natural spoken forms for times and numbers; for example, write 'ora șaisprezece' rather than a malformed abbreviation. ")
                    append("Do not repeat the user's question. Give the useful answer immediately. ")
                    append("Use the Safir memory context below only when relevant. Do not mention that memory context exists. ")
                    append("When the user explicitly asks you to remember a durable fact, preference, person, project detail or expense, append one hidden tag at the end in the form [[MEMORY:fact text]] or [[EXPENSE:expense text]]. ")
                    append("When the user explicitly asks to cancel, delete or forget a previously saved appointment, reminder, expense, fact or preference, append exactly one hidden tag [[FORGET:target text]] that identifies the saved item to suppress. Do not also emit MEMORY or EXPENSE for that same item. ")
                    append("If the forget target is ambiguous, ask a short clarification instead of emitting FORGET. Forget directives override older memory and recent-turn references to the cancelled item. ")
                    append("Do not invent memories.\n")
                    if (memoryContext.isNotBlank()) {
                        append("SAFIR OWN MEMORY:\n")
                        append(memoryContext.take(3500))
                    }
                }

                val messages = JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", systemPrompt)
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", text)
                    )

                val body = JSONObject()
                    .put("messages", messages)
                    .toString()

                conn.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                val raw = if (status in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }

                if (status !in 200..299) {
                    throw IllegalStateException("AI HTTP $status ${raw.take(240)}")
                }

                val json = JSONObject(raw)
                val rawReply = json.optString("reply")
                val extractedMemories = extractMemoryTags(rawReply)
                val extractedForgets = extractForgetTags(rawReply)
                val reply = cleanVisibleReply(rawReply)
                if (reply.isBlank()) throw IllegalStateException("AI returned empty reply")

                onSuccess(
                    AiReply(
                        reply = reply,
                        behavior = SpeechBehavior(
                            emotion = "neutral",
                            energy = 0.55,
                            gesture = "calm",
                        ),
                        memories = extractedMemories,
                        forgets = extractedForgets,
                    )
                )
            } catch (t: Throwable) {
                onError(t.message ?: t.javaClass.simpleName)
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    private fun extractMemoryTags(value: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        Regex("(?s)\\[\\[(MEMORY|EXPENSE):(.*?)]]").findAll(value).forEach { match ->
            val kind = if (match.groupValues[1] == "EXPENSE") "expense" else "fact"
            val content = match.groupValues[2].trim()
            if (content.isNotBlank()) result += kind to content
        }
        return result
    }

    private fun extractForgetTags(value: String): List<String> {
        return Regex("(?s)\\[\\[FORGET:(.*?)]]")
            .findAll(value)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun cleanVisibleReply(value: String): String {
        return value
            .replace(Regex("(?s)\\n?\\[\\[MEMORY:.*?]]"), "")
            .replace(Regex("(?s)\\n?\\[\\[EXPENSE:.*?]]"), "")
            .replace(Regex("(?s)\\n?\\[\\[FORGET:.*?]]"), "")
            .trim()
    }

    private companion object {
        const val EVREN_PUBLISHABLE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndnZ2F5Z2doeWNoa3doeHZzaGZwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzczOTc4NjUsImV4cCI6MjA5Mjk3Mzg2NX0.LM1JlM63U8RfPrEREjWdo5_WdEC286m8dbwA59wOeGU"
    }
}
