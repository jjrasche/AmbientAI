package com.ambientai.data.repositories

import android.content.Context
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.ActionExecution
import com.ambientai.data.entities.ActionExecution_
import com.ambientai.data.entities.WorkflowExecution
import com.ambientai.data.entities.WorkflowExecution_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class WorkflowExecutionRepository() {
    private val executionBox: Box<WorkflowExecution> = AmbientAIApp.boxStore.boxFor()
    private val actionBox: Box<ActionExecution> = AmbientAIApp.boxStore.boxFor()
    fun save(log: WorkflowExecution): WorkflowExecution {
        executionBox.put(log)
        return log
    }
    fun saveAction(log: ActionExecution): ActionExecution {
        actionBox.put(log)
        return log
    }
    fun getExecutionById(id: Long) = executionBox.get(id)
    fun getActionsForExecution(executionId: Long): List<ActionExecution> {
        return actionBox.query(ActionExecution_.workflowExecutionId.equal(executionId)).build().find()
    }
    fun getRecentExecutions(): Flow<List<WorkflowExecution>> = callbackFlow {
        val query = executionBox.query()
            .order(WorkflowExecution_.timestamp, OrderFlags.DESCENDING)
            .build()
        val subscription = query.subscribe().observer { data -> trySend(data) }
        awaitClose { subscription.cancel() }
    }
}