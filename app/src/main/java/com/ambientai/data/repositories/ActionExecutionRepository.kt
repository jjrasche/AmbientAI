// app/src/main/java/com/ambientai/data/repositories/ActionExecutionRepository.kt
package com.ambientai.data.repositories

import android.content.Context
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.ActionExecution
import com.ambientai.data.entities.ActionExecution_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject

class ActionExecutionRepository() {

    private val box: Box<ActionExecution> = AmbientAIApp.boxStore.boxFor()

    fun save(log: ActionExecution): ActionExecution {
        box.put(log)
        return log
    }

    fun getById(id: Long): ActionExecution? {
        return box.get(id)
    }

    fun getByWorkflowExecution(executionId: Long): List<ActionExecution> {
        return box.query(ActionExecution_.workflowExecutionId.equal(executionId))
            .order(ActionExecution_.stepIndex)
            .build()
            .find()
    }

    fun getByActionName(actionName: String): List<ActionExecution> {
        return box.query(ActionExecution_.actionName.equal(actionName))
            .order(ActionExecution_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun getGraded(): List<ActionExecution> {
        return box.query()
            .notNull(ActionExecution_.grade)
            .order(ActionExecution_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun getUngraded(): List<ActionExecution> {
        return box.query()
            .isNull(ActionExecution_.grade)
            .order(ActionExecution_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun updateGrade(id: Long, grade: Int): Boolean {
        val log = box.get(id) ?: return false
        log.grade = grade
        box.put(log)
        return true
    }

    fun getSlowActions(minLatencyMs: Long): List<ActionExecution> {
        return box.query(ActionExecution_.latencyMs.greater(minLatencyMs))
            .order(ActionExecution_.latencyMs, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun getFailed(): List<ActionExecution> {
        return box.query(ActionExecution_.success.equal(false))
            .order(ActionExecution_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun count(): Long {
        return box.count()
    }

    fun countByActionName(actionName: String): Long {
        return box.query(ActionExecution_.actionName.equal(actionName))
            .build()
            .count()
    }

    data class LlmInteractionView(
        val id: Long,
        val systemPrompt: String,
        val userPrompt: String,
        val response: String,
        val timestamp: Long,
        val latencyMs: Long,
        val grade: Int?
    )
    fun getLlmInteractions(): Flow<List<LlmInteractionView>> = callbackFlow {
        val query = box.query(ActionExecution_.actionName.equal("llm.prompt")).order(ActionExecution_.timestamp, OrderFlags.DESCENDING).build()
        val subscription = query.subscribe().observer { actions ->
            val llmViews = actions.mapNotNull { extractLlmView(it) }
            trySend(llmViews)
        }
        awaitClose { subscription.cancel() }
    }
    private fun extractLlmView(action: ActionExecution): LlmInteractionView? {
        if (!action.success) return null
        val input = JSONObject(action.inputJson)
        val output = JSONObject(action.outputJson)
        return LlmInteractionView(
            id = action.id,
            systemPrompt = input.optString("systemPrompt", ""),
            userPrompt = input.optString("userPrompt", ""),
            response = output.optString("response", ""),
            timestamp = action.timestamp,
            latencyMs = action.latencyMs,
            grade = action.grade
        )
    }
}