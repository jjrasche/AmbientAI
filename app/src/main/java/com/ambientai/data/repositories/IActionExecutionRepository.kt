package com.ambientai.data.repositories

import com.ambientai.data.entities.ActionExecution
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

interface IActionExecutionRepository {
    // CRUD operations
    fun save(actionExecution: ActionExecution): ActionExecution
    fun getById(id: Long): ActionExecution?

    // Queries
    fun count(): Long
    fun countByActionName(actionName: String): Long
    fun getByWorkflowExecution(executionId: Long): List<ActionExecution>
    fun getByActionName(actionName: String): List<ActionExecution>
    fun getGraded(): List<ActionExecution>
    fun getUngraded(): List<ActionExecution>
    fun getSlowActions(minLatencyMs: Long): List<ActionExecution>
    fun getFailed(): List<ActionExecution>

    // Domain operations
    fun updateGrade(id: Long, grade: Int): Boolean

    // Domain-specific queries
    data class LlmInteractionView(
        val id: Long,
        val systemPrompt: String,
        val userPrompt: String,
        val response: String,
        val timestamp: Long,
        val latencyMs: Long,
        val grade: Int?
    )
    fun getLlmInteractions(): Flow<List<LlmInteractionView>>
}
