package com.ambientai.data.repositories.fakes

import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.IWorkflowDefinitionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Fake implementation of IWorkflowDefinitionRepository for testing.
 *
 * This implementation stores workflows in memory and provides the same
 * interface as the real repository, making it ideal for unit tests.
 *
 * WHY FAKE OVER MOCK:
 * - Fakes are easier to reason about and maintain
 * - They provide real behavior without external dependencies
 * - Tests are more readable without complex mock setup
 * - Fakes can be reused across multiple tests
 */
class FakeWorkflowDefinitionRepository : IWorkflowDefinitionRepository {
    private val workflows = mutableMapOf<Long, WorkflowDefinition>()
    private var nextId = 1L

    override fun save(workflow: WorkflowDefinition): WorkflowDefinition {
        val savedWorkflow = if (workflow.id == 0L) {
            workflow.copy(id = nextId++)
        } else {
            workflow
        }
        workflows[savedWorkflow.id] = savedWorkflow
        return savedWorkflow
    }

    override fun getById(id: Long): WorkflowDefinition? = workflows[id]

    override fun getByName(name: String): WorkflowDefinition? =
        workflows.values.firstOrNull { it.name == name }

    override fun getAll(): List<WorkflowDefinition> = workflows.values.toList()

    override fun getEnabled(): List<WorkflowDefinition> =
        workflows.values.filter { it.enabled }

    override fun update(workflow: WorkflowDefinition) {
        workflows[workflow.id] = workflow
    }

    override fun delete(id: Long) {
        workflows.remove(id)
    }

    override fun delete(workflow: WorkflowDefinition) {
        workflows.remove(workflow.id)
    }

    override fun deleteAll() {
        workflows.clear()
    }

    override fun count(): Long = workflows.size.toLong()

    override fun countEnabled(): Long = workflows.values.count { it.enabled }.toLong()

    override fun getAllWorkflows(): Flow<List<WorkflowDefinition>> =
        flowOf(workflows.values.toList())

    // Helper methods for testing
    fun addWorkflow(workflow: WorkflowDefinition) {
        save(workflow)
    }

    fun clear() {
        workflows.clear()
        nextId = 1L
    }
}
