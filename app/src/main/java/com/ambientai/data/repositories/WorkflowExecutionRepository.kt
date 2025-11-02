package com.ambientai.data.repositories

import android.content.Context
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.ActionExecution
import com.ambientai.data.entities.ActionExecution_
import com.ambientai.data.entities.WorkflowExecution
import io.objectbox.Box
import io.objectbox.kotlin.boxFor


// app/src/main/java/com/ambientai/data/repositories/WorkflowExecutionRepository.kt
class WorkflowExecutionRepository(context: Context) {
    private val executionBox: Box<WorkflowExecution> = AmbientAIApp.boxStore.boxFor()
    private val actionBox: Box<ActionExecution> = AmbientAIApp.boxStore.boxFor()

    fun save(log: WorkflowExecution): WorkflowExecution {
        executionBox.put(log)
        return log
    }

    fun saveAction(log: ActionExecution): ActionExecution {
        actionBox.put(log)
        return log
    }

    fun getExecutionById(id: Long) = executionBox.get(id)

    fun getActionsForExecution(executionId: Long): List<ActionExecution> {
        return actionBox.query(ActionExecution_.workflowExecutionId.equal(executionId))
            .build()
            .find()
    }
}