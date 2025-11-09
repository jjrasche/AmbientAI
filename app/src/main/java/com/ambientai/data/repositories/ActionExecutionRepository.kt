package com.ambientai.data.repositories

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

class ActionExecutionRepository {
    private val box: Box<ActionExecution> = AmbientAIApp.boxStore.boxFor()

    fun save(log: ActionExecution) = log.also { box.put(it) }
    fun getById(id: Long) = box.get(id)
    fun count() = box.count()
    fun countByActionName(actionName: String) = box.query(ActionExecution_.actionName.equal(actionName)).build().count()

    fun getByWorkflowExecution(executionId: Long) =
        box.query(ActionExecution_.workflowExecutionId.equal(executionId)).order(ActionExecution_.stepIndex).build().find()

    fun getByActionName(actionName: String) =
        box.query(ActionExecution_.actionName.equal(actionName)).order(ActionExecution_.timestamp, OrderFlags.DESCENDING).build().find()

    fun getGraded() =
        box.query().notNull(ActionExecution_.grade).order(ActionExecution_.timestamp, OrderFlags.DESCENDING).build().find()

    fun getUngraded() =
        box.query().isNull(ActionExecution_.grade).order(ActionExecution_.timestamp, OrderFlags.DESCENDING).build().find()

    fun getSlowActions(minLatencyMs: Long) =
        box.query(ActionExecution_.latencyMs.greater(minLatencyMs)).order(ActionExecution_.latencyMs, OrderFlags.DESCENDING).build().find()

    fun getFailed() =
        box.query(ActionExecution_.success.equal(false)).order(ActionExecution_.timestamp, OrderFlags.DESCENDING).build().find()

    fun updateGrade(id: Long, grade: Int) =
        box.get(id)?.let { it.grade = grade; box.put(it); true } ?: false

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
        val subscription = box.query(ActionExecution_.actionName.equal("llm.prompt"))
            .order(ActionExecution_.timestamp, OrderFlags.DESCENDING).build()
            .subscribe().observer { trySend(it.mapNotNull(::extractLlmView)) }
        awaitClose { subscription.cancel() }
    }

    private fun extractLlmView(action: ActionExecution) = action.takeIf { it.success }?.let {
        JSONObject(it.inputJson).let { input ->
            JSONObject(it.outputJson).let { output ->
                LlmInteractionView(it.id, input.optString("systemPrompt", ""), input.optString("userPrompt", ""),
                    output.optString("response", ""), it.timestamp, it.latencyMs, it.grade)
            }
        }
    }
}
