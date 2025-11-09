package com.ambientai.data.repositories.fakes

import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.IWorkflowDefinitionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeWorkflowDefinitionRepository : IWorkflowDefinitionRepository {
    private val workflows = mutableMapOf<Long, WorkflowDefinition>()
    private var nextId = 1L

    override fun save(workflow: WorkflowDefinition): WorkflowDefinition = (if (workflow.id == 0L) workflow.copy(id = nextId++) else workflow).also { workflows[it.id] = it }
    override fun getById(id: Long): WorkflowDefinition? = workflows[id]
    override fun getByName(name: String): WorkflowDefinition? = workflows.values.firstOrNull { it.name == name }
    override fun getAll(): List<WorkflowDefinition> = workflows.values.toList()
    override fun getEnabled(): List<WorkflowDefinition> = workflows.values.filter { it.enabled }
    override fun update(workflow: WorkflowDefinition) = workflows.set(workflow.id, workflow)
    override fun delete(id: Long) = workflows.remove(id)
    override fun delete(workflow: WorkflowDefinition) = workflows.remove(workflow.id)
    override fun deleteAll() = workflows.clear()
    override fun count(): Long = workflows.size.toLong()
    override fun countEnabled(): Long = workflows.values.count { it.enabled }.toLong()
    override fun getAllWorkflows(): Flow<List<WorkflowDefinition>> = flowOf(workflows.values.toList())
    fun addWorkflow(workflow: WorkflowDefinition) = save(workflow)
    fun clear() = workflows.clear().also { nextId = 1L }
}
