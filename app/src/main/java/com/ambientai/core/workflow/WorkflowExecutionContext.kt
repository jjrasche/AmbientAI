package com.ambientai.workflow

data class WorkflowExecutionContext(
    val workflowId: Long,
    val workflowName: String,
    val transcript: String,
    val matchedTrigger: String,
    val variables: MutableMap<String, Any> = mutableMapOf(),
    val startTime: Long = System.currentTimeMillis()
)