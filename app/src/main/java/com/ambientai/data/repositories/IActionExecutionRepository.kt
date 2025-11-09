package com.ambientai.data.repositories

import com.ambientai.data.entities.ActionExecution
import kotlinx.coroutines.flow.Flow

interface IActionExecutionRepository {
    // CRUD operations
    fun save(actionExecution: ActionExecution): ActionExecution
    fun getById(id: Long): ActionExecution?
    fun deleteAll()

    // Queries
    fun getAll(): List<ActionExecution>
    fun count(): Long
    fun getByWorkflowExecution(workflowExecutionId: Long): List<ActionExecution>

    // Domain-specific queries
    data class LlmInteractionView(
        val id: Long,
        val actionName: String,
        val inputJson: String,
        val outputJson: String,
        val timestamp: Long,
        val latencyMs: Long,
        val success: Boolean,
        val grade: String?
    )
    fun getLlmInteractions(): Flow<List<LlmInteractionView>>
}
