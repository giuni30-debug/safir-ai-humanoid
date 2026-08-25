package com.safir.ai.humanoid

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class AiReply(
    val reply: String,
    val behavior: SpeechBehavior,
)

class AiReplyClient(
    private val endpoint: String = "https://wggaygghychkwhxvshfp.supabase.co/functions/v1/ai-assist",
) {
    fun request(
        text: String,
        onSuccess: (AiReply) -> Unit,
        onError: (String) -> Unit,
    ) {
        thread(name = "safir-ai-reply") {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $EVREN_PUBLISHABLE_KEY")
                    setRequestProperty("apikey", EVREN_PUBLISHABLE_KEY)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }

                val messages = JSONArray().put(
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
                val reply = cleanVisibleReply(json.optString("reply"))
                if (reply.isBlank()) throw IllegalStateException("AI returned empty reply")

                onSuccess(
                    AiReply(
                        reply = reply,
                        behavior = SpeechBehavior(
                            emotion = "neutral",
                            energy = 0.55,
                            gesture = "calm",
                        ),
                    )
                )
            } catch (t: Throwable) {
                onError(t.message ?: t.javaClass.simpleName)
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    private fun cleanVisibleReply(value: String): String {
        return value
            .replace(Regex("(?s)\\n?\\[\\[MEMORY:.*?]]"), "")
            .replace(Regex("(?s)\\n?\\[\\[EXPENSE:.*?]]"), "")
            .trim()
    }

    private companion object {
        const val EVREN_PUBLISHABLE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndnZ2F5Z2doeWNoa3doeHZzaGZwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzczOTc4NjUsImV4cCI6MjA5Mjk3Mzg2NX0.LM1JlM63U8RfPrEREjWdo5_WdEC286m8dbwA59wOeGU"
    }
}
