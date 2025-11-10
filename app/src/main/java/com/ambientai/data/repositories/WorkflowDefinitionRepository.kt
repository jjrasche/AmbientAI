package com.ambientai.data.repositories

import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.entities.WorkflowDefinition_
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowDefinitionRepository @Inject constructor(
    private val boxStore: BoxStore
) : IWorkflowDefinitionRepository {
    private val box: Box<WorkflowDefinition> = boxStore.boxFor()

    override fun save(workflow: WorkflowDefinition) = workflow.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun getByName(name: String) = box.query(WorkflowDefinition_.name.equal(name)).build().findFirst()
    override fun getAll() = box.all
    override fun getEnabled() = box.query(WorkflowDefinition_.enabled.equal(true)).build().find()
    override fun update(workflow: WorkflowDefinition) { box.put(workflow) }
    override fun delete(id: Long) { box.remove(id) }
    override fun delete(workflow: WorkflowDefinition) { box.remove(workflow) }
    override fun deleteAll() = box.removeAll()
    override fun count() = box.count()
    override fun countEnabled() = box.query(WorkflowDefinition_.enabled.equal(true)).build().count()
    fun toggleEnabled(id: Long) = box.get(id)?.let { it.enabled = !it.enabled; box.put(it) }
    override fun updateReviewNotes(id: Long, notes: String) { box.get(id)?.let { it.reviewNotes = notes; box.put(it) } }
    override fun getAllWorkflows(): Flow<List<WorkflowDefinition>> = callbackFlow {
        val subscription = box.query().build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
}
