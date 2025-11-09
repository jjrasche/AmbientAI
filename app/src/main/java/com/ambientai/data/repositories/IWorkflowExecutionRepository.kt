package com.ambientai.data.repositories

import com.ambientai.data.entities.ActionExecution
import com.ambientai.data.entities.WorkflowExecution
import kotlinx.coroutines.flow.Flow

interface IWorkflowExecutionRepository {
    // CRUD operations
    fun save(workflowExecution: WorkflowExecution): WorkflowExecution
    fun getById(id: Long): WorkflowExecution?
    fun deleteAll()

    // Queries
    fun getAll(): List<WorkflowExecution>
    fun count(): Long
    fun getByWorkflowId(workflowId: Long): List<WorkflowExecution>

    // Domain operations
    fun saveAction(actionExecution: ActionExecution): ActionExecution

    // Reactive streams
    fun getAllExecutions(): Flow<List<WorkflowExecution>>
}
