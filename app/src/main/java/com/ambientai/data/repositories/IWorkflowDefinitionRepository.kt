package com.ambientai.data.repositories

import com.ambientai.data.entities.WorkflowDefinition
import kotlinx.coroutines.flow.Flow

interface IWorkflowDefinitionRepository {
    // CRUD operations
    fun save(workflowDefinition: WorkflowDefinition): WorkflowDefinition
    fun getById(id: Long): WorkflowDefinition?
    fun update(workflowDefinition: WorkflowDefinition)
    fun delete(id: Long)
    fun deleteAll()

    // Queries
    fun getAll(): List<WorkflowDefinition>
    fun getEnabled(): List<WorkflowDefinition>
    fun count(): Long

    // Domain operations
    fun toggleEnabled(id: Long)

    // Reactive streams
    fun getAllWorkflows(): Flow<List<WorkflowDefinition>>
}
