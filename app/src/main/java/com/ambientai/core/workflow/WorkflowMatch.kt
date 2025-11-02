package com.ambientai.workflow

import com.ambientai.data.entities.WorkflowDefinition

/**
 * Result of workflow routing.
 * Contains the matched workflow and initialized execution context.
 */
data class WorkflowMatch(
    val definition: WorkflowDefinition,
    val context: WorkflowExecutionContext
)