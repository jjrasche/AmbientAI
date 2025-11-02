package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

@Entity
data class WorkflowExecution(
    @Id var id: Long = 0,
    var workflowId: Long,
    var workflowName: String,
    var transcript: String,
    var matchedTrigger: String,
    var success: Boolean,
    var errorMessage: String? = null,
    var executionTimeMs: Long,
    var timestamp: Long
)