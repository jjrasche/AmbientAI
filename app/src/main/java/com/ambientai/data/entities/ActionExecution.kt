package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

@Entity
data class ActionExecution(
    @Id var id: Long = 0,
    var workflowExecutionId: Long,
    var stepIndex: Int,
    var stepPath: String,
    var actionName: String,
    var inputJson: String,
    var outputJson: String,
    var success: Boolean,
    var errorMessage: String? = null,
    var latencyMs: Long,
    var timestamp: Long,
    var grade: Int? = null
)