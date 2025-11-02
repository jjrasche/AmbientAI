package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

/**
 * Entity representing a workflow definition.
 * Contains triggers and execution steps in JSON format.
 */
@Entity
data class WorkflowDefinition(
    @Id var id: Long = 0,
    var name: String,                    // "conversational_reply"
    var definition: String,              // Full JSON including triggers and steps
    var enabled: Boolean = true
)

/**
 * Entity representing a complete workflow execution.
 * Links to individual action logs for detailed analysis.
 */
@Entity
data class WorkflowExecutionLog(
    @Id var id: Long = 0,
    var workflowId: Long,                // References WorkflowDefinition
    var workflowName: String,            // Denormalized for easier querying
    var transcript: String,              // Original user input
    var matchedTrigger: String,          // Which trigger phrase matched
    var success: Boolean,
    var errorMessage: String? = null,    // Only set if workflow-level error
    var executionTimeMs: Long,
    var timestamp: Long
)

/**
 * Entity representing a single action execution within a workflow.
 * Captures inputs, outputs, and performance for analysis and grading.
 */
@Entity
data class ActionExecutionLog(
    @Id var id: Long = 0,
    var workflowExecutionId: Long,       // Link to parent workflow execution
    var stepIndex: Int,                  // Sequential counter: 0, 1, 2, 3...
    var stepPath: String,                // Execution tree: "3.then.0" = step 3, then branch, action 0
    var actionName: String,              // "llm.prompt", "tts.speak", "control.if"
    var inputJson: String,               // Fully resolved inputs (after variable substitution)
    var outputJson: String,              // Action output
    var success: Boolean,
    var errorMessage: String? = null,
    var latencyMs: Long,
    var timestamp: Long,
    var grade: Int? = null               // 0-5 rating, null if not graded yet
)