package com.ambientai.testing

import org.json.JSONObject

data class RegressionTestScenario(
    val testId: String,
    val category: String,
    val description: String,
    val input: TestInput,
    val expected: TestExpectations
)

data class TestInput(
    val utterance: String,
    val elapsedMs: Int = 3000,
    val preconditions: Map<String, Any> = emptyMap()
)

data class TestExpectations(
    val workflowMatched: String? = null,
    val workflowExecuted: Boolean? = null,
    val workflowSuccess: Boolean? = null,
    val actionsExecuted: List<String>? = null,
    val databaseChanges: Map<String, DatabaseAssertion>? = null,
    val sideEffects: Map<String, Any>? = null,
    val ttsSpoken: String? = null
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
    put("test_id", testId)
    put("category", category)
    put("description", description)
    put("input", JSONObject().apply {
        put("utterance", input.utterance)
        put("elapsed_ms", input.elapsedMs)
        if (input.preconditions.isNotEmpty()) {
            put("preconditions", JSONObject(input.preconditions))
        }
    })
    put("expected", JSONObject().apply {
        expected.workflowMatched?.let { put("workflow_matched", it) }
        expected.workflowExecuted?.let { put("workflow_executed", it) }
        expected.workflowSuccess?.let { put("workflow_success", it) }
        expected.actionsExecuted?.let { put("actions_executed", it) }
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
            put("database_changes", dbJson)
        }
        expected.sideEffects?.let { put("side_effects", JSONObject(it)) }
        expected.ttsSpoken?.let { put("tts_spoken", it) }
    })
}
