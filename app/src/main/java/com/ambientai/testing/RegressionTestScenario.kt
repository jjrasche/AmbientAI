package com.ambientai.testing

import org.json.JSONObject

data class RegressionTestScenario(
    val testId: String,
    val category: String,
    val description: String,
    val audioFile: String,
    val utterance: String,
    val preconditions: List<PreconditionStep> = emptyList(),
    val expected: TestExpectations
)

data class PreconditionStep(
    val action: String? = null,
    val input: Map<String, Any> = emptyMap(),
    val wait: Long? = null
)

data class TestExpectations(
    // Positive assertions (what SHOULD happen)
    val workflowMatched: String? = null,
    val workflowExecuted: Boolean? = null,
    val workflowSuccess: Boolean? = null,
    val actionsExecuted: List<String>? = null,
    val databaseChanges: Map<String, DatabaseAssertion>? = null,
    val serviceStateChanges: Map<String, Any>? = null,
    val sideEffects: Map<String, Any>? = null,
    val ttsSpoken: String? = null,
    val finalTranscript: String? = null,
    val shouldWaitForMoreSpeech: Boolean? = null,
    val vadTimeout: Int? = null,

    // Negative assertions (what should NOT happen)
    val shouldNotExecute: List<String>? = null,
    val shouldNotCreate: List<String>? = null,
    val shouldNotModify: List<String>? = null
)

data class DatabaseAssertion(
    val count: Int? = null,
    val minCount: Int? = null,
    val statusEquals: String? = null,
    val nameContains: String? = null,
    val hasField: Map<String, Any>? = null
)

data class TestResult(
    val testId: String,
    val passed: Boolean,
    val durationMs: Long,
    val failures: List<String> = emptyList(),
    val details: Map<String, Any> = emptyMap()
)

// Extension function to convert scenario to JSON
fun RegressionTestScenario.toJson(): JSONObject = JSONObject().apply {
    put("testId", testId)
    put("category", category)
    put("description", description)
    put("audioFile", audioFile)
    put("utterance", utterance)
    if (preconditions.isNotEmpty()) put("preconditions", org.json.JSONArray(preconditions.map { step ->
        JSONObject().apply {
            step.action?.let { put("action", it) }
            if (step.input.isNotEmpty()) put("input", JSONObject(step.input))
            step.wait?.let { put("wait", it) }
        }
    }))
    put("expected", JSONObject().apply {
        expected.workflowMatched?.let { put("workflowMatched", it) }
        expected.workflowExecuted?.let { put("workflowExecuted", it) }
        expected.workflowSuccess?.let { put("workflowSuccess", it) }
        expected.actionsExecuted?.let { put("actionsExecuted", it) }
        expected.databaseChanges?.let { dbChanges ->
            val dbJson = JSONObject()
            dbChanges.forEach { (key, value) ->
                val assertionJson = JSONObject()
                value.count?.let { assertionJson.put("count", it) }
                value.minCount?.let { assertionJson.put("minCount", it) }
                value.statusEquals?.let { assertionJson.put("statusEquals", it) }
                value.nameContains?.let { assertionJson.put("nameContains", it) }
                value.hasField?.let { assertionJson.put("hasField", JSONObject(it)) }
                dbJson.put(key, assertionJson)
            }
            put("databaseChanges", dbJson)
        }
        expected.serviceStateChanges?.let { put("serviceStateChanges", JSONObject(it)) }
        expected.sideEffects?.let { put("sideEffects", JSONObject(it)) }
        expected.ttsSpoken?.let { put("ttsSpoken", it) }
        expected.shouldNotExecute?.let { put("shouldNotExecute", it) }
        expected.shouldNotCreate?.let { put("shouldNotCreate", it) }
        expected.shouldNotModify?.let { put("shouldNotModify", it) }
    })
}
