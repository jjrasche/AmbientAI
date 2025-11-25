package com.ambientai.core.workflow

import android.util.Log
import com.ambientai.core.llm.GroqLlmService
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SlotFillingService @Inject constructor(private val llmService: GroqLlmService) {
    companion object { private const val TAG = "SlotFillingService" }

    suspend fun fillSlots(workflowDefinition: JSONObject, transcript: String): Map<String, Any> {
        val slots = workflowDefinition.optJSONObject("slots") ?: return emptyMap()
        if (slots.length() == 0) return emptyMap()

        val slotDescriptions = slots.keys().asSequence().map { key ->
            val slot = slots.getJSONObject(key)
            val description = slot.optString("description", key)
            val required = slot.optBoolean("required", true)
            val default = slot.opt("default")
            val requiredText = if (required) "required" else "optional, default: $default"
            "- $key: $description ($requiredText)"
        }.joinToString("\n")

        val systemPrompt = """Extract slot values from the user's command. Return ONLY valid JSON with the slot names as keys.

Slots to extract:
$slotDescriptions

Rules:
- Return raw values, not descriptions
- For missing optional slots, omit them (don't include null)
- For missing required slots, make your best guess from context"""

        val userPrompt = "User command: \"$transcript\""

        Log.d(TAG, "Extracting slots for: $transcript")
        Log.d(TAG, "Slot definitions:\n$slotDescriptions")

        val result = llmService.execute("llm.prompt", JSONObject().apply {
            put("systemPrompt", systemPrompt)
            put("userPrompt", userPrompt)
            put("temperature", 0.1)
            put("maxTokens", 200)
        })

        if (!result.optBoolean("success", false)) {
            Log.e(TAG, "Slot extraction failed: ${result.optString("error")}")
            return applyDefaults(slots)
        }

        val response = result.optString("response", "{}")
        Log.d(TAG, "LLM response: $response")

        return parseSlotValues(response, slots)
    }

    private fun parseSlotValues(response: String, slots: JSONObject): Map<String, Any> {
        val cleaned = response.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        return runCatching {
            val parsed = JSONObject(cleaned)
            val result = mutableMapOf<String, Any>()

            slots.keys().forEach { key ->
                val slot = slots.getJSONObject(key)
                val value = parsed.opt(key)
                if (value != null && value != JSONObject.NULL) {
                    result[key] = value
                } else {
                    slot.opt("default")?.let { result[key] = it }
                }
            }
            Log.d(TAG, "Extracted slots: $result")
            result
        }.getOrElse { e ->
            Log.e(TAG, "Failed to parse slot values: ${e.message}, response: $cleaned")
            applyDefaults(slots)
        }
    }

    private fun applyDefaults(slots: JSONObject): Map<String, Any> = mutableMapOf<String, Any>().also { result ->
        slots.keys().forEach { key -> slots.getJSONObject(key).opt("default")?.let { result[key] = it } }
    }
}
