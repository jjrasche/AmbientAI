package com.ambientai.data.repositories

import com.ambientai.data.entities.ActionExecution
import com.ambientai.data.entities.ActionExecution_
import com.ambientai.data.entities.WorkflowExecution
import com.ambientai.data.entities.WorkflowExecution_
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowExecutionRepository @Inject constructor(
    private val boxStore: BoxStore
) : IWorkflowExecutionRepository {
    private val executionBox: Box<WorkflowExecution> = boxStore.boxFor()
    private val actionBox: Box<ActionExecution> = boxStore.boxFor()

    override fun save(log: WorkflowExecution) = log.also { executionBox.put(it) }
    override fun saveAction(log: ActionExecution) = log.also { actionBox.put(it) }
    override fun getById(id: Long) = executionBox.get(id)
    override fun getActionsForExecution(executionId: Long) = actionBox.query(ActionExecution_.workflowExecutionId.equal(executionId)).build().find()
    override fun getAll() = executionBox.all
    override fun count() = executionBox.count()
    override fun getByWorkflowId(workflowId: Long) = executionBox.query(WorkflowExecution_.workflowId.equal(workflowId)).build().find()
    override fun deleteAll() = executionBox.removeAll().also { actionBox.removeAll() }
    override fun getAllExecutions(): Flow<List<WorkflowExecution>> = callbackFlow {
        val subscription = executionBox.query().order(WorkflowExecution_.timestamp, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
    override fun getRecentExecutions(): Flow<List<WorkflowExecution>> = callbackFlow {
        val subscription = executionBox.query().order(WorkflowExecution_.timestamp, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
    override fun getExecutionsForWorkflow(workflowId: Long): Flow<List<WorkflowExecution>> = callbackFlow {
        val subscription = executionBox.query(WorkflowExecution_.workflowId.equal(workflowId)).order(WorkflowExecution_.timestamp, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
}
