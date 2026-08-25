package com.safir.ai.humanoid

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class HumanoidMemoryClient(
    private val endpoint: String = "${BuildConfig.SUPABASE_URL}/functions/v1/humanoid-memory",
) {
    fun fetchContext(
        onSuccess: (String) -> Unit,
        onError: (() -> Unit)? = null,
    ) {
        thread(name = "safir-memory-context") {
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection()
                val body = JSONObject().put("action", "context").toString()
                conn.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                val raw = if (status in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
                if (status !in 200..299) throw IllegalStateException("Memory HTTP $status")

                val json = JSONObject(raw)
                val lines = mutableListOf<String>()

                json.optJSONObject("profile")?.let { profile ->
                    val displayName = profile.optString("display_name").trim()
                    val language = profile.optString("preferred_language").trim()
                    val currency = profile.optString("default_currency").trim()
                    if (displayName.isNotBlank()) lines += "User: $displayName"
                    if (language.isNotBlank()) lines += "Preferred language: $language"
                    if (currency.isNotBlank()) lines += "Default currency: $currency"
                }

                val memories = json.optJSONArray("memories")
                if (memories != null && memories.length() > 0) {
                    lines += "Persistent memories:"
                    for (i in 0 until memories.length()) {
                        val item = memories.optJSONObject(i) ?: continue
                        val title = item.optString("title").trim()
                        val content = item.optString("content").trim()
                        if (content.isNotBlank()) {
                            lines += if (title.isBlank()) "- $content" else "- $title: $content"
                        }
                    }
                }

                val turns = json.optJSONArray("recent_turns")
                if (turns != null && turns.length() > 0) {
                    lines += "Recent conversation:"
                    for (i in 0 until turns.length()) {
                        val item = turns.optJSONObject(i) ?: continue
                        val role = item.optString("role").trim()
                        val content = item.optString("content").trim()
                        if (role.isNotBlank() && content.isNotBlank()) lines += "- $role: $content"
                    }
                }

                onSuccess(lines.joinToString("\n").take(3500))
            } catch (_: Throwable) {
                onError?.invoke()
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    fun storeTurn(role: String, content: String) {
        val safeRole = if (role == "assistant") "assistant" else "user"
        val safeContent = content.trim()
        if (safeContent.isBlank()) return

        postAsync(
            threadName = "safir-memory-$safeRole",
            body = JSONObject()
                .put("action", "store_turn")
                .put("role", safeRole)
                .put("content", safeContent)
        )
    }

    fun storeMemory(kind: String, content: String) {
        val safeContent = content.trim()
        if (safeContent.isBlank()) return
        val safeKind = kind.trim().ifBlank { "fact" }.take(40)
        val title = safeContent.replace(Regex("\\s+"), " ").take(120)

        postAsync(
            threadName = "safir-memory-persist",
            body = JSONObject()
                .put("action", "store_memory")
                .put("kind", safeKind)
                .put("title", title)
                .put("content", safeContent)
                .put("importance", 3)
        )
    }

    private fun postAsync(threadName: String, body: JSONObject) {
        thread(name = threadName) {
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection()
                conn.outputStream.use { out -> out.write(body.toString().toByteArray(Charsets.UTF_8)) }
                runCatching { conn.inputStream.close() }
            } catch (_: Throwable) {
                // Persistence must never block or break the critical voice path.
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    private fun openConnection(): HttpURLConnection {
        return (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
    }
}
