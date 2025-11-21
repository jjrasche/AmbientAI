package com.ambientai.testing

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegressionTestScenarios @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "RegressionTestScenarios"
        private const val SCENARIOS_FILE = "test_scenarios.json"
        private const val WRITABLE_SCENARIOS_FILE = "test_scenarios_writable.json"
    }

    fun getAllScenarios() = loadScenariosFromJson()

    fun updateTestScenarioAudio(testId: String, audioFilePath: String): Boolean {
        try {
            val scenarios = getAllScenarios().toMutableList()
            val scenarioIndex = scenarios.indexOfFirst { it.testId == testId }
            if (scenarioIndex == -1) {
                Log.e(TAG, "✖ Test scenario not found: $testId")
                return false
            }
            val updatedScenario = scenarios[scenarioIndex].copy(audioFile = audioFilePath)
            scenarios[scenarioIndex] = updatedScenario
            saveScenarios(scenarios)
            Log.d(TAG, "✓ Updated $testId with audio: $audioFilePath")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "✖ Failed to update scenario", e)
            return false
        }
    }

    fun getUpdatedScenariosJson(): String {
        val writableFile = java.io.File(context.filesDir, WRITABLE_SCENARIOS_FILE)
        return if (writableFile.exists()) {
            writableFile.readText()
        } else {
            context.assets.open(SCENARIOS_FILE).bufferedReader().use { it.readText() }
        }
    }

    private fun saveScenarios(scenarios: List<RegressionTestScenario>) {
        val jsonArray = JSONArray()
        scenarios.forEach { scenario ->
            jsonArray.put(scenarioToJson(scenario))
        }
        val writableFile = java.io.File(context.filesDir, WRITABLE_SCENARIOS_FILE)
        writableFile.writeText(jsonArray.toString(2))
        Log.d(TAG, "✓ Saved ${scenarios.size} scenarios to writable file")
    }

    private fun scenarioToJson(scenario: RegressionTestScenario): JSONObject = JSONObject().apply {
        put("testId", scenario.testId)
        put("category", scenario.category)
        put("description", scenario.description)
        put("audioFile", scenario.audioFile)
        put("utterance", scenario.utterance)
        if (scenario.preconditions.isNotEmpty()) {
            put("preconditions", JSONArray(scenario.preconditions.map { step ->
                JSONObject().apply {
                    step.action?.let { put("action", it) }
                    if (step.input.isNotEmpty()) put("input", JSONObject(step.input))
                    step.wait?.let { put("wait", it) }
                }
            }))
        }
        put("expected", expectationsToJson(scenario.expected))
    }

    private fun expectationsToJson(expected: TestExpectations): JSONObject = JSONObject().apply {
        expected.workflowMatched?.let { put("workflowMatched", it) }
        expected.workflowExecuted?.let { put("workflowExecuted", it) }
        expected.workflowSuccess?.let { put("workflowSuccess", it) }
        expected.actionsExecuted?.let { put("actionsExecuted", JSONArray(it)) }
        expected.databaseChanges?.let { changes ->
            val dbJson = JSONObject()
            changes.forEach { (key, assertion) ->
                dbJson.put(key, JSONObject().apply {
                    assertion.count?.let { put("count", it) }
                    assertion.minCount?.let { put("minCount", it) }
                    assertion.statusEquals?.let { put("statusEquals", it) }
                    assertion.nameContains?.let { put("nameContains", it) }
                })
            }
            put("databaseChanges", dbJson)
        }
        expected.serviceStateChanges?.let { put("serviceStateChanges", JSONObject(it)) }
        expected.ttsSpoken?.let { put("ttsSpoken", it) }
        expected.finalTranscript?.let { put("finalTranscript", it) }
        expected.shouldWaitForMoreSpeech?.let { put("shouldWaitForMoreSpeech", it) }
        expected.vadTimeout?.let { put("vadTimeout", it) }
        expected.shouldNotExecute?.let { put("shouldNotExecute", JSONArray(it)) }
        expected.shouldNotCreate?.let { put("shouldNotCreate", JSONArray(it)) }
    }

    private fun loadScenariosFromJson(): List<RegressionTestScenario> {
        return try {
            val writableFile = java.io.File(context.filesDir, WRITABLE_SCENARIOS_FILE)
            val json = if (writableFile.exists()) {
                Log.d(TAG, "📝 Loading from writable file")
                writableFile.readText()
            } else {
                Log.d(TAG, "📦 Loading from assets (read-only)")
                context.assets.open(SCENARIOS_FILE).bufferedReader().use { it.readText() }
            }
            val scenariosArray = JSONArray(json)
            val scenarios = mutableListOf<RegressionTestScenario>()
            for (i in 0 until scenariosArray.length()) {
                val scenarioJson = scenariosArray.getJSONObject(i)
                scenarios.add(parseScenario(scenarioJson))
            }
            Log.d(TAG, "✓ Loaded ${scenarios.size} test scenarios from JSON")
            scenarios
        } catch (e: Exception) {
            Log.e(TAG, "✖ Failed to load scenarios from JSON", e)
            emptyList()
        }
    }

    private fun parseScenario(json: JSONObject): RegressionTestScenario {
        val preconditions = if (json.has("preconditions")) {
            val precondArray = json.getJSONArray("preconditions")
            List(precondArray.length()) { i ->
                val stepJson = precondArray.getJSONObject(i)
                val input = if (stepJson.has("input")) {
                    val inputJson = stepJson.getJSONObject("input")
                    inputJson.keys().asSequence().associateWith { inputJson.get(it) }
                } else emptyMap()
                PreconditionStep(
                    action = if (stepJson.has("action")) stepJson.getString("action") else null,
                    input = input,
                    wait = if (stepJson.has("wait")) stepJson.getLong("wait") else null,
                    clearActiveTasks = if (stepJson.has("clearActiveTasks")) stepJson.getBoolean("clearActiveTasks") else null,
                    clearSubscriptions = if (stepJson.has("clearSubscriptions")) stepJson.getString("clearSubscriptions") else null
                )
            }
        } else emptyList()
        return RegressionTestScenario(
            testId = json.getString("testId"),
            category = json.getString("category"),
            description = json.getString("description"),
            audioFile = json.getString("audioFile"),
            utterance = json.getString("utterance"),
            preconditions = preconditions,
            expected = parseTestExpectations(json.getJSONObject("expected"))
        )
    }

    private fun parseTestExpectations(json: JSONObject): TestExpectations {
        val actionsExecuted = if (json.has("actionsExecuted")) {
            val array = json.getJSONArray("actionsExecuted")
            List(array.length()) { array.getString(it) }
        } else null
        val shouldNotExecute = if (json.has("shouldNotExecute")) {
            val array = json.getJSONArray("shouldNotExecute")
            List(array.length()) { array.getString(it) }
        } else null
        val shouldNotCreate = if (json.has("shouldNotCreate")) {
            val array = json.getJSONArray("shouldNotCreate")
            List(array.length()) { array.getString(it) }
        } else null
        val databaseChanges = if (json.has("databaseChanges")) {
            val dbJson = json.getJSONObject("databaseChanges")
            val changes = mutableMapOf<String, DatabaseAssertion>()
            dbJson.keys().forEach { key ->
                changes[key] = parseDatabaseAssertion(dbJson.getJSONObject(key))
            }
            changes
        } else null
        val serviceStateChanges = if (json.has("serviceStateChanges")) {
            val stateJson = json.getJSONObject("serviceStateChanges")
            val changes = mutableMapOf<String, Any>()
            stateJson.keys().forEach { key ->
                changes[key] = stateJson.get(key)
            }
            changes
        } else null
        return TestExpectations(
            workflowMatched = if (json.has("workflowMatched")) json.getString("workflowMatched") else null,
            workflowExecuted = json.optBooleanOrNull("workflowExecuted"),
            workflowSuccess = json.optBooleanOrNull("workflowSuccess"),
            actionsExecuted = actionsExecuted,
            databaseChanges = databaseChanges,
            serviceStateChanges = serviceStateChanges,
            ttsSpoken = if (json.has("ttsSpoken")) json.getString("ttsSpoken") else null,
            finalTranscript = if (json.has("finalTranscript")) json.getString("finalTranscript") else null,
            shouldWaitForMoreSpeech = json.optBooleanOrNull("shouldWaitForMoreSpeech"),
            vadTimeout = json.optIntOrNull("vadTimeout"),
            shouldNotExecute = shouldNotExecute,
            shouldNotCreate = shouldNotCreate
        )
    }

    private fun parseDatabaseAssertion(json: JSONObject) = DatabaseAssertion(
        count = json.optIntOrNull("count"),
        minCount = json.optIntOrNull("minCount"),
        statusEquals = if (json.has("statusEquals")) json.getString("statusEquals") else null,
        nameContains = if (json.has("nameContains")) json.getString("nameContains") else null
    )

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key)) getBoolean(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key)) getInt(key) else null
}
