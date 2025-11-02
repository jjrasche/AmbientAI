package com.ambientai.data.repositories

import android.content.Context
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.entities.WorkflowDefinition_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor

/**
 * Repository for CRUD operations on WorkflowDefinition entities.
 */
class WorkflowDefinitionRepository(context: Context) {

    private val box: Box<WorkflowDefinition> = AmbientAIApp.boxStore.boxFor()

    fun save(workflow: WorkflowDefinition): WorkflowDefinition {
        box.put(workflow)
        return workflow
    }

    fun getById(id: Long): WorkflowDefinition? {
        return box.get(id)
    }

    fun getAll(): List<WorkflowDefinition> {
        return box.all
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

    fun count(): Long {
        return box.count()
    }

    fun countEnabled(): Long {
        return box.query(WorkflowDefinition_.enabled.equal(true))
            .build()
            .count()
    }
}