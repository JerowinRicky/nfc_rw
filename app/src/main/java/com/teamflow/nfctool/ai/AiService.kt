package com.teamflow.nfctool.ai

import com.teamflow.nfctool.domain.AiConfig
import com.teamflow.nfctool.domain.AiProvider
import com.teamflow.nfctool.domain.TagSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AiService {

    suspend fun generateResponse(
        prompt: String,
        config: AiConfig,
        currentTag: TagSnapshot?
    ): String = withContext(Dispatchers.IO) {
        val systemContext = buildTagContext(currentTag)
        val fullPrompt = if (systemContext.isNotBlank()) {
            "$systemContext\n\nUser Question: $prompt"
        } else {
            prompt
        }

        if (config.apiKey.isBlank() && config.provider != AiProvider.OLLAMA_CUSTOM) {
            return@withContext generateLocalFallback(prompt, currentTag)
        }

        runCatching {
            when (config.provider) {
                AiProvider.GEMINI -> callGeminiApi(fullPrompt, config)
                AiProvider.OPENAI -> callOpenAiApi("https://api.openai.com/v1/chat/completions", fullPrompt, config)
                AiProvider.GROQ -> callOpenAiApi("https://api.groq.com/openai/v1/chat/completions", fullPrompt, config)
                AiProvider.OLLAMA_CUSTOM -> callOpenAiApi(config.customEndpoint.ifBlank { "http://localhost:11434/v1/chat/completions" }, fullPrompt, config)
                AiProvider.CLAUDE -> callClaudeApi(fullPrompt, config)
            }
        }.getOrElse { error ->
            val errMsg = error.message ?: ""
            if (errMsg.contains("model_not_found") || errMsg.contains("does not exist")) {
                "⚠️ **Model Not Found**: The model `${config.modelName}` was not found on ${config.provider.displayName}.\n\n" +
                "💡 **Solution**: Open the **Settings** tab and set the Model Name to an active model:\n" +
                "• **Groq**: `llama-3.1-8b-instant` or `llama-3.3-70b-specdec` or `mixtral-8x7b-32768`\n" +
                "• **OpenAI**: `gpt-4o-mini` or `gpt-4o`\n" +
                "• **Gemini**: `gemini-1.5-flash` or `gemini-2.0-flash`\n\n" +
                "---\n" +
                generateLocalFallback(prompt, currentTag)
            } else {
                "AI Service Error (${error.javaClass.simpleName}): ${error.message ?: "Could not connect to ${config.provider.displayName}."}\n\nFalling back to local technical assistant:\n\n" + generateLocalFallback(prompt, currentTag)
            }
        }
    }

    private fun callGeminiApi(prompt: String, config: AiConfig): String {
        val model = config.modelName.ifBlank { "gemini-1.5-flash" }
        val urlStr = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${config.apiKey.trim()}"
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 20000

        val bodyJson = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", prompt)
                }))
            }))
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(bodyJson.toString()) }

        val responseCode = conn.responseCode
        val inputStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val responseStr = BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { it.readText() }

        if (responseCode !in 200..299) {
            error("HTTP $responseCode: $responseStr")
        }

        val json = JSONObject(responseStr)
        val candidates = json.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val content = candidates.getJSONObject(0).optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts != null && parts.length() > 0) {
                return parts.getJSONObject(0).getString("text")
            }
        }
        return "No text output returned by Gemini API."
    }

    private fun callOpenAiApi(endpoint: String, prompt: String, config: AiConfig): String {
        val defaultM = if (config.provider == AiProvider.GROQ) "llama-3.1-8b-instant" else "gpt-4o-mini"
        val model = config.modelName.ifBlank { defaultM }
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (config.apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
        }
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 20000

        val bodyJson = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are an expert NFC, RFID, Smart Card & Cryptography engineer assistant in an Android app.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(bodyJson.toString()) }

        val responseCode = conn.responseCode
        val inputStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val responseStr = BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { it.readText() }

        if (responseCode !in 200..299) {
            error("HTTP $responseCode: $responseStr")
        }

        val json = JSONObject(responseStr)
        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val message = choices.getJSONObject(0).optJSONObject("message")
            return message?.optString("content") ?: "No message content."
        }
        return "No content returned by OpenAI-compatible endpoint."
    }

    private fun callClaudeApi(prompt: String, config: AiConfig): String {
        val model = config.modelName.ifBlank { "claude-3-5-haiku-20241022" }
        val url = URL("https://api.anthropic.com/v1/messages")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("x-api-key", config.apiKey.trim())
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 20000

        val bodyJson = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1024)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(bodyJson.toString()) }

        val responseCode = conn.responseCode
        val inputStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val responseStr = BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { it.readText() }

        if (responseCode !in 200..299) {
            error("HTTP $responseCode: $responseStr")
        }

        val json = JSONObject(responseStr)
        val contentArray = json.optJSONArray("content")
        if (contentArray != null && contentArray.length() > 0) {
            return contentArray.getJSONObject(0).optString("text")
        }
        return "No text returned by Claude API."
    }

    private fun buildTagContext(tag: TagSnapshot?): String {
        if (tag == null) return ""
        return buildString {
            append("CURRENTLY SCANNED NFC TAG DATA:\n")
            append("• Chip / Tag Model: ${tag.chipModel ?: "Unknown"}\n")
            append("• Hardware UID: ${tag.uid ?: "Unavailable"}\n")
            append("• NDEF Availability: ${tag.ndefAvailability.label}\n")
            append("• Writable Status: ${tag.writable?.let { if (it) "Writable" else "Read-Only" } ?: "Unknown"}\n")
            append("• Protection: ${tag.protection.label}\n")
            append("• Technologies Detected: ${tag.technologies.joinToString { it.name }}\n")
            tag.attendanceInfo?.let { att ->
                att.wiegand26Dec10?.let { append("• Biometric Attendance ID (10-Digit): $it\n") }
                if (att.wiegand26Facility != null && att.wiegand26Card != null) {
                    append("• Wiegand 26-bit: Facility ${att.wiegand26Facility}, Card ${att.wiegand26Card}\n")
                }
            }
            if (tag.ndefRecords.isNotEmpty()) {
                append("• NDEF Records (${tag.ndefRecords.size}):\n")
                tag.ndefRecords.forEachIndexed { idx, r ->
                    append("  - Rec ${idx+1}: [${r.kind}] Type=${r.type} Payload=${r.value}\n")
                }
            }
        }
    }

    private fun generateLocalFallback(prompt: String, tag: TagSnapshot?): String {
        val query = prompt.lowercase()
        return buildString {
            append("🤖 **NFC Technical Assistant Analysis**\n\n")
            if (tag != null) {
                append("### Scanned Tag: `${tag.chipModel ?: "NFC Tag"}`\n")
                append("- **UID**: `${tag.uid ?: "N/A"}`\n")
                append("- **Tech Stack**: `${tag.technologies.joinToString { it.name }}`\n")
                append("- **NDEF Status**: `${tag.ndefAvailability.label}`\n\n")

                if (query.contains("wiegand") || query.contains("attendance") || query.contains("biometric") || query.contains("card")) {
                    append("#### 💳 Attendance & Wiegand Card Breakdown\n")
                    tag.attendanceInfo?.let { att ->
                        append("• **10-Digit Attendance ID**: `${att.wiegand26Dec10 ?: "N/A"}`\n")
                        append("• **Wiegand 26-bit Format**: Facility `${att.wiegand26Facility}`, Card `${att.wiegand26Card}`\n")
                        append("• **Hex UIDs**: Big-Endian (`${att.uidHexBigEndian}`), Little-Endian (`${att.uidHexLittleEndian}`)\n")
                    }
                    append("Most biometric attendance terminals convert the tag's raw UID bytes into a 26-bit or 34-bit Wiegand integer stored in their attendance database.\n\n")
                }

                if (query.contains("clone") || query.contains("copy")) {
                    append("#### 📋 Tag Cloneability Assessment\n")
                    if (tag.technologies.any { it.name.contains("MIFARE Classic", true) }) {
                        append("This is a **MIFARE Classic** tag. Standard Android NFC APIs only permit reading/writing accessible sectors with known keys (default `FFFFFFFFFFFF`). Hardware UID cloning requires magic UID cards (Gen1a/Gen2/CUID) and dedicated hardware.\n\n")
                    } else if (tag.technologies.any { it.name.contains("DESFire", true) }) {
                        append("This is a **MIFARE DESFire** tag featuring 3DES / AES hardware cryptographic encryption. Application payloads and UIDs are locked behind cryptographic keys and cannot be cloned.\n\n")
                    } else {
                        append("NDEF records on this tag can be saved as an NDEF Profile in the **Write** tab and reproduced on any writable NDEF target tag (NTAG213/215/216). Note that physical UIDs are fixed by hardware.\n\n")
                    }
                }

                if (query.contains("memory") || query.contains("structure") || query.contains("format")) {
                    append("#### 💾 Memory Architecture & Format\n")
                    append("• **Capacity**: ${tag.maxSize?.let { "$it bytes" } ?: "Varies by chip specification"}\n")
                    append("• **NDEF Formatted**: ${if (tag.ndefAvailability == com.teamflow.nfctool.domain.NdefAvailability.AVAILABLE) "Yes" else "No / Raw Memory"}\n")
                    append("• **Formatting Support**: ${if (tag.formatable) "Can be formatted using NdefFormatable API in the Write tab" else "Pre-formatted or unformatable"}\n\n")
                }

                append("*(Tip: To get live responses from Google Gemini, OpenAI, Claude, or Groq, go to the **Settings** tab and configure your API key!)*")
            } else {
                append("No active tag is currently loaded. Tap an NFC tag in the **Read** tab or ask me any question about NFC standards (NTAG213/215/216, MIFARE Classic/DESFire/Plus, FeliCa, ICODE SLIX, ST25, Topaz, or Wiegand Attendance formats)!\n\n")
                append("*(To connect live online AI models, enter your API key in the **Settings** tab.)*")
            }
        }
    }
}
