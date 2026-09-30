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

                val forgetTargets = mutableListOf<String>()
                val memories = json.optJSONArray("memories")
                if (memories != null && memories.length() > 0) {
                    for (i in 0 until memories.length()) {
                        val item = memories.optJSONObject(i) ?: continue
                        val kind = item.optString("kind").trim()
                        val content = item.optString("content").trim()
                        if (kind.equals("forget", ignoreCase = true) || content.startsWith("FORGET:", ignoreCase = true)) {
                            val target = content.substringAfter(":", content).trim()
                            if (target.isNotBlank()) forgetTargets += target
                        }
                    }

                    val visibleMemories = mutableListOf<String>()
                    for (i in 0 until memories.length()) {
                        val item = memories.optJSONObject(i) ?: continue
                        val kind = item.optString("kind").trim()
                        val title = item.optString("title").trim()
                        val content = item.optString("content").trim()
                        if (content.isBlank()) continue
                        if (kind.equals("forget", ignoreCase = true) || content.startsWith("FORGET:", ignoreCase = true)) continue

                        val candidate = listOf(title, content).filter { it.isNotBlank() }.joinToString(" ")
                        if (forgetTargets.any { target -> memoryMatchesForget(candidate, target) }) continue

                        visibleMemories += if (title.isBlank()) "- $content" else "- $title: $content"
                    }

                    if (visibleMemories.isNotEmpty()) {
                        lines += "Persistent memories:"
                        lines += visibleMemories
                    }
                }

                val turns = json.optJSONArray("recent_turns")
                if (turns != null && turns.length() > 0) {
                    lines += "Recent conversation:"
                    for (i in 0 until turns.length()) {
                        val item = turns.optJSONObject(i) ?: continue
                        val role = item.optString("role").trim()
                        val content = item.optString("content").trim()
                        if (role.isBlank() || content.isBlank()) continue
                        if (forgetTargets.any { target -> memoryMatchesForget(content, target) }) continue
                        lines += "- $role: $content"
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

    fun storeForget(target: String) {
        val safeTarget = target.trim()
        if (safeTarget.isBlank()) return
        val marker = "FORGET: " + safeTarget.replace(Regex("\\s+"), " ").take(500)

        postAsync(
            threadName = "safir-memory-forget",
            body = JSONObject()
                .put("action", "store_memory")
                .put("kind", "forget")
                .put("title", "FORGET")
                .put("content", marker)
                .put("importance", 5)
        )
    }

    private fun memoryMatchesForget(memory: String, target: String): Boolean {
        val memoryNormalized = normalizeForMatch(memory)
        val targetNormalized = normalizeForMatch(target)
        if (memoryNormalized.isBlank() || targetNormalized.isBlank()) return false
        if (memoryNormalized.contains(targetNormalized) || targetNormalized.contains(memoryNormalized)) return true

        val memoryTokens = memoryNormalized.split(" ").filter { it.length >= 3 }.toSet()
        val targetTokens = targetNormalized.split(" ").filter { it.length >= 3 }.toSet()
        if (memoryTokens.isEmpty() || targetTokens.isEmpty()) return false

        val overlap = memoryTokens.intersect(targetTokens).size
        val required = if (targetTokens.size <= 2) targetTokens.size else maxOf(2, (targetTokens.size * 2 + 2) / 3)
        return overlap >= required
    }

    private fun normalizeForMatch(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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
