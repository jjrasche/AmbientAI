package com.ambientai.data.repositories

import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.entities.WorkflowDefinition_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class WorkflowDefinitionRepository {
    private val box: Box<WorkflowDefinition> = AmbientAIApp.boxStore.boxFor()

    fun save(workflow: WorkflowDefinition) = workflow.also { box.put(it) }
    fun getById(id: Long) = box.get(id)
    fun getByName(name: String) = box.query(WorkflowDefinition_.name.equal(name)).build().findFirst()
    fun getEnabled() = box.query(WorkflowDefinition_.enabled.equal(true)).build().find()
    fun update(workflow: WorkflowDefinition) = box.put(workflow)
    fun delete(id: Long) = box.remove(id)
    fun delete(workflow: WorkflowDefinition) = box.remove(workflow)
    fun deleteAll() = box.removeAll()
    fun count() = box.count()
    fun countEnabled() = box.query(WorkflowDefinition_.enabled.equal(true)).build().count()
    fun getAllTasks(): Flow<List<WorkflowDefinition>> = callbackFlow {
        val subscription = box.query().build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
}
