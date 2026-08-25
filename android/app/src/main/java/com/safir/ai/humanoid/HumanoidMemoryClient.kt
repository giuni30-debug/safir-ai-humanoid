package com.safir.ai.humanoid

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class HumanoidMemoryClient(
    private val endpoint: String = "${BuildConfig.SUPABASE_URL}/functions/v1/humanoid-memory",
) {
    fun storeTurn(role: String, content: String) {
        val safeRole = if (role == "assistant") "assistant" else "user"
        val safeContent = content.trim()
        if (safeContent.isBlank()) return

        thread(name = "safir-memory-$safeRole") {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4_000
                    readTimeout = 8_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }

                val body = JSONObject()
                    .put("action", "store_turn")
                    .put("role", safeRole)
                    .put("content", safeContent)
                    .toString()

                conn.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }
                runCatching { conn.inputStream.close() }
            } catch (_: Throwable) {
                // Memory persistence is deliberately off the critical voice path.
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }
}
