package com.ambientai.core.workflow

import android.util.Log
import com.ambientai.data.entities.WorkflowDefinition
import org.json.JSONObject

class IncompletenessDetector {
    companion object {
        private const val TAG = "IncompletenessDetector"
    }
    fun isIncomplete(text: String, wordCount: Int, matchedWorkflow: WorkflowDefinition? = null): Boolean {
        if (text.isBlank()) return true
        val trimmed = text.trim()
        val words = trimmed.split("\\s+".toRegex())
        val lastWord = words.lastOrNull()?.lowercase() ?: return true
        if (lastWord in RoutingConfig.TRAILING_PREPOSITIONS) {
            Log.d(TAG, "   ↳ INCOMPLETE: Ends with preposition '$lastWord'")
            return true
        }
        if (lastWord in RoutingConfig.TRAILING_CONJUNCTIONS) {
            Log.d(TAG, "   ↳ INCOMPLETE: Ends with conjunction '$lastWord'")
            return true
        }
        if (lastWord in RoutingConfig.TRAILING_ARTICLES) {
            Log.d(TAG, "   ↳ INCOMPLETE: Ends with article '$lastWord'")
            return true
        }
        if (wordCount == 1 && text.length < 4) {
            Log.d(TAG, "   ↳ INCOMPLETE: Very short single word")
            return true
        }
        if (matchedWorkflow != null && workflowRequiresInput(matchedWorkflow)) {
            val triggerOnly = containsOnlyTrigger(text, matchedWorkflow)
            if (triggerOnly) {
                Log.d(TAG, "   ↳ INCOMPLETE: Workflow '${matchedWorkflow.name}' requires input, but transcript is only trigger phrase")
                return true
            }
        }
        return false
    }
    private fun workflowRequiresInput(workflow: WorkflowDefinition): Boolean = runCatching {
        JSONObject(workflow.definition).optBoolean("requiresInput", false)
    }.getOrElse { false }
    private fun containsOnlyTrigger(text: String, workflow: WorkflowDefinition): Boolean = runCatching {
        val lowerText = text.lowercase().trim()
        val json = JSONObject(workflow.definition)
        val triggers = json.optJSONObject("triggers")?.let { triggersObj ->
            triggersObj.optJSONArray("keywords")?.let { keywordsArray ->
                List(keywordsArray.length()) { i -> keywordsArray.getString(i).lowercase() }
            } ?: emptyList()
        } ?: json.optJSONArray("triggers")?.let { triggersArray ->
            List(triggersArray.length()) { i -> triggersArray.getString(i).lowercase() }
        } ?: emptyList()
        triggers.any { trigger ->
            when {
                lowerText == trigger -> true
                trigger.contains(lowerText) -> true
                lowerText.contains(trigger) -> {
                    val triggerIndex = lowerText.indexOf(trigger)
                    val afterTrigger = lowerText.substring(triggerIndex + trigger.length).trim()
                    val wordsAfterTrigger = if (afterTrigger.isEmpty()) 0 else afterTrigger.split("\\s+".toRegex()).size
                    wordsAfterTrigger <= 1
                }
                else -> false
            }
        }
    }.getOrElse { false }
    fun detectCancellation(text: String): Boolean {
        val words = text.lowercase().split("\\s+".toRegex())
        val recentWords = words.takeLast(3)
        val hasCancellation = recentWords.any { it in RoutingConfig.CANCELLATION_PHRASES }
        if (hasCancellation) {
            Log.d(TAG, "   ↳ CANCELLATION DETECTED: ${recentWords.find { it in RoutingConfig.CANCELLATION_PHRASES }}")
        }
        return hasCancellation
    }
}
