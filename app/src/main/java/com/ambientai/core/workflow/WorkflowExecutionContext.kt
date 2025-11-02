package com.ambientai.workflow
/**
 * Runtime context for workflow execution.
 * Not persisted - just working data that gets passed between actions.
 * Variables accumulate as actions execute and produce outputs.
 */
data class WorkflowExecutionContext(
    val workflowId: Long,                             // Which workflow is running
    val workflowName: String,                         // For logging/debugging
    val transcript: String,                           // Original user input
    val matchedTrigger: String,                       // What phrase triggered this workflow
    val variables: MutableMap<String, Any> = mutableMapOf(), // $transcript, $llmResponse, etc.
    val startTime: Long = System.currentTimeMillis()
)