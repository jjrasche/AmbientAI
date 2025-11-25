package com.ambientai.workflow

import com.ambientai.data.entities.WorkflowDefinition

data class WorkflowMatch(
    val definition: WorkflowDefinition,
    val context: WorkflowExecutionContext,
    val triggerStart: Int = 0,
    val triggerEnd: Int = 0
)