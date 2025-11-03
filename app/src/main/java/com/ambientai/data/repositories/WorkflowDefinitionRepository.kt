package com.ambientai.data.repositories

import android.content.Context
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.entities.WorkflowDefinition_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow


class WorkflowDefinitionRepository() {

    private val box: Box<WorkflowDefinition> = AmbientAIApp.boxStore.boxFor()

    fun save(workflow: WorkflowDefinition): WorkflowDefinition {
        box.put(workflow)
        return workflow
    }

    fun getById(id: Long): WorkflowDefinition? {
        return box.get(id)
    }

    fun getAllTasks(): Flow<List<WorkflowDefinition>> = callbackFlow {
        val query = box.query().build()
        val subscription = query.subscribe().observer { data -> trySend(data) }
        awaitClose { subscription.cancel(); }
    }

    fun getEnabled(): List<WorkflowDefinition> {
        return box.query(WorkflowDefinition_.enabled.equal(true))
            .build()
            .find()
    }

    fun getByName(name: String): WorkflowDefinition? {
        return box.query(WorkflowDefinition_.name.equal(name))
            .build()
            .findFirst()
    }

    fun update(workflow: WorkflowDefinition) {
        box.put(workflow)
    }

    fun delete(id: Long): Boolean {
        return box.remove(id)
    }

    fun delete(workflow: WorkflowDefinition) {
        box.remove(workflow)
    }
    fun deleteAll() {
        box.removeAll()
    }

    fun count(): Long {
        return box.count()
    }

    fun countEnabled(): Long {
        return box.query(WorkflowDefinition_.enabled.equal(true))
            .build()
            .count()
    }
}