// app/src/main/java/com/ambientai/data/repositories/ActionExecutionRepository.kt
package com.ambientai.data.repositories

import android.content.Context
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.ActionExecution
import com.ambientai.data.entities.ActionExecution_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags

class ActionExecutionRepository(context: Context) {

    private val box: Box<ActionExecution> = AmbientAIApp.boxStore.boxFor()

    fun save(log: ActionExecution): ActionExecution {
        box.put(log)
        return log
    }

    fun getById(id: Long): ActionExecution? {
        return box.get(id)
    }

    fun getByWorkflowExecution(executionId: Long): List<ActionExecution> {
        return box.query(ActionExecution_.workflowExecutionId.equal(executionId))
            .order(ActionExecution_.stepIndex)
            .build()
            .find()
    }

    fun getByActionName(actionName: String): List<ActionExecution> {
        return box.query(ActionExecution_.actionName.equal(actionName))
            .order(ActionExecution_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun getGraded(): List<ActionExecution> {
        return box.query()
            .notNull(ActionExecution_.grade)
            .order(ActionExecution_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun getUngraded(): List<ActionExecution> {
        return box.query()
            .isNull(ActionExecution_.grade)
            .order(ActionExecution_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun updateGrade(id: Long, grade: Int): Boolean {
        val log = box.get(id) ?: return false
        log.grade = grade
        box.put(log)
        return true
    }

    fun getSlowActions(minLatencyMs: Long): List<ActionExecution> {
        return box.query(ActionExecution_.latencyMs.greater(minLatencyMs))
            .order(ActionExecution_.latencyMs, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun getFailed(): List<ActionExecution> {
        return box.query(ActionExecution_.success.equal(false))
            .order(ActionExecution_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun count(): Long {
        return box.count()
    }

    fun countByActionName(actionName: String): Long {
        return box.query(ActionExecution_.actionName.equal(actionName))
            .build()
            .count()
    }
}