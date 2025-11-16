package com.ambientai.data.repositories

import com.ambientai.data.entities.ActionExecution
import com.ambientai.data.entities.ActionExecution_
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionExecutionRepository @Inject constructor(
    private val boxStore: BoxStore
) : IActionExecutionRepository {
    private val box: Box<ActionExecution> = boxStore.boxFor()

    override fun save(log: ActionExecution) = log.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun count() = box.count()
    override fun getRecent(limit: Int) = box.query().order(ActionExecution_.timestamp, OrderFlags.DESCENDING).build().find(0, limit.toLong())
    override fun countByActionName(actionName: String) = box.query(ActionExecution_.actionName.equal(actionName)).build().count()
    override fun getByWorkflowExecution(executionId: Long) =
        box.query(ActionExecution_.workflowExecutionId.equal(executionId)).order(ActionExecution_.stepIndex).build().find()
    override fun getByActionName(actionName: String) =
        box.query(ActionExecution_.actionName.equal(actionName)).order(ActionExecution_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun getGraded() =
        box.query().notNull(ActionExecution_.grade).order(ActionExecution_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun getUngraded() =
        box.query().isNull(ActionExecution_.grade).order(ActionExecution_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun getSlowActions(minLatencyMs: Long) =
        box.query(ActionExecution_.latencyMs.greater(minLatencyMs)).order(ActionExecution_.latencyMs, OrderFlags.DESCENDING).build().find()
    override fun getFailed() =
        box.query(ActionExecution_.success.equal(false)).order(ActionExecution_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun updateGrade(id: Long, grade: Int) =
        box.get(id)?.let { it.grade = grade; box.put(it); true } ?: false
    override fun getLlmInteractions(): Flow<List<IActionExecutionRepository.LlmInteractionView>> = callbackFlow {
        val subscription = box.query(ActionExecution_.actionName.equal("llm.prompt"))
            .order(ActionExecution_.timestamp, OrderFlags.DESCENDING).build()
            .subscribe().observer { trySend(it.mapNotNull(::extractLlmView)) }
        awaitClose { subscription.cancel() }
    }

    private fun extractLlmView(action: ActionExecution) = action.takeIf { it.success }?.let {
        JSONObject(it.inputJson).let { input ->
            JSONObject(it.outputJson).let { output ->
                IActionExecutionRepository.LlmInteractionView(it.id, input.optString("systemPrompt", ""), input.optString("userPrompt", ""),
                    output.optString("response", ""), it.timestamp, it.latencyMs, it.grade)
            }
        }
    }
}
