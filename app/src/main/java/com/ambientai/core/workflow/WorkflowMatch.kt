package com.ambientai.workflow

import com.ambientai.data.entities.WorkflowDefinition

data class WorkflowMatch(
    val definition: WorkflowDefinition,
    val context: WorkflowExecutionContext
)