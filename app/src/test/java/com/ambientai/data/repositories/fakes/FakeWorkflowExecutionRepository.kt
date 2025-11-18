package com.ambientai.data.repositories.fakes

import com.ambientai.data.entities.ActionExecution
import com.ambientai.data.entities.WorkflowExecution
import com.ambientai.data.repositories.IWorkflowExecutionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeWorkflowExecutionRepository : IWorkflowExecutionRepository {
    private val executions = mutableMapOf<Long, WorkflowExecution>()
    private val actions = mutableMapOf<Long, ActionExecution>()
    private var nextExecutionId = 1L
    private var nextActionId = 1L

    override fun save(log: WorkflowExecution): WorkflowExecution {
        if (log.id == 0L) {
            // New entity - assign ID
            val saved = log.copy(id = nextExecutionId++)
            executions[saved.id] = saved
            return saved
        } else {
            // Update existing entity (mimics ObjectBox put behavior)
            executions[log.id] = log
            return log
        }
    }
    override fun saveAction(log: ActionExecution): ActionExecution {
        if (log.id == 0L) {
            val saved = log.copy(id = nextActionId++)
            actions[saved.id] = saved
            return saved
        } else {
            actions[log.id] = log
            return log
        }
    }
    override fun getById(id: Long): WorkflowExecution? = executions[id]
    override fun getActionsForExecution(executionId: Long): List<ActionExecution> = actions.values.filter { it.workflowExecutionId == executionId }
    override fun getAll(): List<WorkflowExecution> = executions.values.toList()
    override fun count(): Long = executions.size.toLong()
    override fun getRecent(limit: Int): List<WorkflowExecution> = executions.values.sortedByDescending { it.timestamp }.take(limit)
    override fun getByWorkflowId(workflowId: Long): List<WorkflowExecution> = executions.values.filter { it.workflowId == workflowId }
    override fun deleteAll() = executions.clear().also { actions.clear() }
    override fun getAllExecutions(): Flow<List<WorkflowExecution>> = flowOf(executions.values.toList())
    override fun getRecentExecutions(): Flow<List<WorkflowExecution>> = flowOf(executions.values.sortedByDescending { it.timestamp })
    override fun getExecutionsForWorkflow(workflowId: Long): Flow<List<WorkflowExecution>> = flowOf(executions.values.filter { it.workflowId == workflowId }.sortedByDescending { it.timestamp })
    fun getExecutionCount(): Int = executions.size
    fun getActionCount(): Int = actions.size
    fun getSuccessfulExecutions(): List<WorkflowExecution> = executions.values.filter { it.success }
    fun getFailedExecutions(): List<WorkflowExecution> = executions.values.filter { !it.success }
    fun clear() = executions.clear().also { actions.clear(); nextExecutionId = 1L; nextActionId = 1L }
}
