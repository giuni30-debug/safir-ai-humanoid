package com.safir.ai.humanoid

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

data class AiReply(
    val reply: String,
    val behavior: SpeechBehavior,
)

class AiReplyClient(
    private val endpoint: String = "https://safir-orb.lovable.app/api/humanoid-reply",
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
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }

                val body = JSONObject()
                    .put("text", text)
                    .put("locale", Locale.getDefault().toLanguageTag())
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
                val reply = json.optString("reply").trim()
                if (reply.isBlank()) throw IllegalStateException("AI returned empty reply")

                val emotion = json.optString("emotion", "neutral")
                val gesture = json.optString("gesture", "calm")
                val energy = json.optDouble("energy", 0.5).coerceIn(0.0, 1.0)

                onSuccess(
                    AiReply(
                        reply = reply,
                        behavior = SpeechBehavior(
                            emotion = emotion,
                            energy = energy,
                            gesture = gesture,
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
}
