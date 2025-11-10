# Workflow Review System

## Philosophy

The workflow review system is the **core improvement mechanism** for AmbientAI. Everything is a workflow - even base transcription. By reviewing workflow execution patterns, the system learns how you work and iteratively refines itself to better serve your needs.

**Key Principles:**
- **User-owned**: All data stays on device. You control when review happens and what changes are applied.
- **Evidence-based**: Only suggest changes backed by actual execution data.
- **Voice-first**: Review interaction happens through natural conversation, not UI editing.
- **Iterative**: Changes are proposed, discussed, adjusted through multiple LLM passes until you're satisfied.

## Review Trigger

Review is triggered **manually by user request**:
- "Review my workflows"
- "Analyze recent executions"
- "How can we improve the task workflow?"

No automated daily analysis. No scheduled jobs. You decide when review happens.

## Review Inputs: The Evidence

Review analyzes execution data from ObjectBox:

### 1. Workflow Executions
```kotlin
data class WorkflowExecution(
    val workflowId: Long,           // Which workflow ran
    val transcript: String,         // What the user said
    val matchedTrigger: String,     // Which trigger phrase matched
    val success: Boolean,           // Did it complete successfully?
    val executionTimeMs: Long,      // How long it took
    val errorMessage: String?,      // What went wrong (if failed)
    val timestamp: Long             // When it ran
)
```

### 2. Action Executions
```kotlin
data class ActionExecution(
    val workflowExecutionId: Long,  // Parent workflow
    val actionName: String,         // e.g., "llm.prompt", "task.start"
    val inputJson: String,          // Fully resolved inputs
    val outputJson: String?,        // Action result
    val success: Boolean,           // Did it succeed?
    val latencyMs: Long,            // How long it took
    val errorMessage: String?,      // Error details
    val stepPath: String,           // Position in workflow (e.g., "2.then.0")
    val grade: Int?                 // Manual quality rating (1-5, null if not graded)
)
```

### 3. Current Workflow Definitions
```kotlin
data class WorkflowDefinition(
    val id: Long,
    val name: String,
    val definition: String,         // JSON: { "triggers": [...], "steps": [...] }
    val enabled: Boolean,
    val version: Int                // Increments with each change
)
```

## Review Outputs: Standardized Changes

Review produces **four types of improvements**:

### 1. Workflow Refinement
**Purpose**: Optimize existing workflows based on execution patterns.

**Types of refinements**:
- **Trigger adjustments**: Add/remove trigger phrases
- **Prompt improvements**: Clarify LLM system prompts
- **Error handling**: Add conditionals for edge cases
- **Performance optimization**: Replace slow actions

**Output format**:
```json
{
  "type": "workflow_refinement",
  "workflow": "start_task",
  "changes": [
    {
      "path": "triggers",
      "operation": "add",
      "value": "back to",
      "rationale": "User said 'back to Android migration' 4 times with no match. Adding trigger enables task switching."
    },
    {
      "path": "steps.0.input.system_prompt",
      "operation": "replace",
      "old_value": "Extract task name from user input.",
      "new_value": "Extract task name from user input. If user says 'back to X' or 'switch to X', return X as task name. Return JSON: {\"task_name\": \"string | null\"}",
      "rationale": "LLM failed to extract task name from 'back to' phrasing in 3/4 cases. More explicit prompt should improve extraction."
    }
  ]
}
```

### 2. Workflow Expansion
**Purpose**: Create new workflows for repeated patterns or missing functionality.

**Output format**:
```json
{
  "type": "workflow_expansion",
  "name": "switch_task",
  "definition": {
    "triggers": ["back to", "switch to", "return to"],
    "steps": [
      {
        "action": "llm.prompt",
        "input": {
          "system_prompt": "Extract task name from user input like 'back to Android migration'. Return JSON: {\"task_name\": \"string | null\"}",
          "user_prompt": "$transcript"
        },
        "output": "taskData"
      },
      {
        "action": "control.if",
        "condition": "$taskData.task_name !== null",
        "then": [
          {
            "action": "task.switch",
            "input": { "name": "$taskData.task_name" },
            "output": "task"
          },
          {
            "action": "tts.speak",
            "input": { "text": "Switched to $task.name" }
          }
        ],
        "else": [
          {
            "action": "tts.speak",
            "input": { "text": "Couldn't identify task to switch to" }
          }
        ]
      }
    ]
  },
  "rationale": "User said 'back to X' 4 times with no workflow match. Pattern suggests need for task switching workflow distinct from starting new tasks."
}
```

### 3. LLM Grading (Data Labeling)
**Purpose**: Label LLM action quality to improve future decisions and enable fine-tuning.

**Grading scale**:
- **5**: Perfect extraction, exactly what was needed
- **4**: Good, minor formatting issues
- **3**: Acceptable but suboptimal (extra words, wrong case)
- **2**: Partially wrong (missed a field, wrong value)
- **1**: Completely failed

**Output format**:
```json
{
  "type": "llm_grading",
  "grades": [
    {
      "action_execution_id": 123,
      "grade": 4,
      "notes": "Correctly extracted 'Android migration' but included extra period. Output: {\"task_name\": \"Android migration.\"}",
      "suggested_improvement": "Add to prompt: 'Clean output - no trailing punctuation'"
    },
    {
      "action_execution_id": 124,
      "grade": 2,
      "notes": "Failed to extract task from 'back to Android migration'. Output: {\"task_name\": null}",
      "suggested_improvement": "Prompt doesn't handle 'back to' phrasing. See workflow_refinement suggestion above."
    }
  ]
}
```

**Application**: Grades are written to `ActionExecution.grade` field. Over time, accumulation of graded examples enables:
- Identifying consistently problematic prompts (avg grade < 3)
- A/B testing prompt variations
- Fine-tuning custom models (if 1000+ graded examples accumulate)

### 4. Information Aggregation
**Purpose**: Extract semantic patterns and entities for improved context awareness.

**Current scope** (keeping it simple):
- **Semantic embeddings**: Each transcript gets vector embedding for similarity search
- **Entity extraction**: Identify recurring entities (people, projects, concepts) that appear 3+ times

**Output format**:
```json
{
  "type": "entity_extraction",
  "entities": [
    {
      "type": "project",
      "name": "Android migration",
      "mentions": 7,
      "first_mentioned": 1699564800000,
      "last_mentioned": 1699651200000,
      "related_transcripts": [101, 105, 112, 118, 125, 130, 134],
      "suggested_action": "Consider creating dedicated workflow or context variable for frequently mentioned project"
    },
    {
      "type": "person",
      "name": "Sarah",
      "mentions": 4,
      "context": "Appears in communication/email related transcripts",
      "suggested_action": "May warrant Contact entity in database if pattern continues"
    }
  ]
}
```

**Future expansion** (not now):
- Knowledge graph of entity relationships
- Temporal context (time-based triggers like "same as yesterday")
- Cross-workflow pattern detection

## Application Mechanism: Voice-First Iteration

Changes are **not directly applied**. Instead, the review output becomes a conversation.

### Flow

```
User: "Review my workflows"
  ↓
System analyzes executions, generates suggestions
  ↓
System: "I found 3 improvements for start_task workflow and 1 new workflow suggestion.
         Want to hear about the start_task refinements first?"
  ↓
User: "Yes"
  ↓
System: "I suggest adding 'back to' as a trigger because you said 'back to Android migration'
         4 times with no match. Should I make this change?"
  ↓
User: "Actually, I think that should be a separate workflow for switching tasks"
  ↓
System: [Regenerates suggestions with this feedback, removes trigger addition,
         creates new switch_task workflow instead]
  ↓
System: "Good point. I've created a switch_task workflow with triggers 'back to',
         'switch to', 'return to'. It will handle task switching. Sound good?"
  ↓
User: "Yes, apply that"
  ↓
System: [Writes new WorkflowDefinition to ObjectBox]
  ↓
System: "Applied. Switch task workflow is now active."
```

### Two Application Modes

**Mode 1: Voice-First Iteration** (Primary)
- Review suggestions presented via TTS
- User responds with voice ("yes", "no", "change X to Y")
- LLM adjusts suggestions based on feedback
- Multiple passes until user approves
- Final approval triggers write to ObjectBox

**Mode 2: Improvement Review Screen** (Optional UI)
- Simple screen showing pending suggestions
- "Apply" / "Reject" buttons for each
- Useful for batch review of multiple suggestions
- Still voice-optional: can voice "apply all" or "reject the trigger change"

**Key insight**: You don't edit JSON directly. You **discuss changes with the system**, which adjusts its suggestions iteratively.

## Workflow Versioning

### Version Tracking
Each `WorkflowDefinition` has a `version` field that increments on change:

```kotlin
data class WorkflowDefinition(
    val id: Long,
    val name: String,
    val definition: String,         // Current JSON
    val enabled: Boolean,
    val version: Int,               // Increments on each change
    val lastModified: Long,         // Timestamp of last change
    val modifiedBy: String          // "user" | "review_system"
)
```

### Version History
Track all changes in separate entity:

```kotlin
@Entity
data class WorkflowVersion(
    @Id var id: Long = 0,
    val workflowId: Long,           // Foreign key to WorkflowDefinition
    val version: Int,               // Version number
    val definition: String,         // JSON snapshot at this version
    val createdAt: Long,            // When this version was created
    val createdBy: String,          // "user" | "review_system"
    val changeReason: String,       // Human-readable reason for change
    val changeType: String          // "refinement" | "expansion" | "rollback"
)
```

### Rollback Support
```
User: "Undo the last workflow change"
  ↓
System: [Queries WorkflowVersion for previous version]
System: "Last change was adding 'back to' trigger to start_task. Want to revert?"
  ↓
User: "Yes"
  ↓
System: [Writes previous version.definition to WorkflowDefinition, increments version]
System: "Reverted to version 2. 'Back to' trigger removed."
```

## LLM-Assisted Grading: Learning Your Preferences

### The Problem
Manual grading of every LLM action is tedious. But without grading, the system can't learn what "good" looks like for you.

### The Solution: LLM as Grading Assistant
Train an LLM to replicate your grading logic by:
1. You manually grade ~20-30 diverse LLM action executions
2. System fine-tunes small model or builds few-shot prompt with your examples
3. LLM auto-grades future executions
4. You spot-check and correct when LLM grades incorrectly
5. Corrections feed back into training data

### Example: Few-Shot Grading Prompt
```
You are grading LLM action quality on a 1-5 scale based on the user's preferences.

User's grading examples:
- Input: "working on Android migration"
  Expected: Extract task name
  Output: {"task_name": "Android migration"}
  User grade: 5
  User note: "Perfect extraction"

- Input: "working on Android migration"
  Expected: Extract task name
  Output: {"task_name": "Android migration."}
  User grade: 4
  User note: "Correct but has trailing period"

- Input: "back to Android migration"
  Expected: Extract task name
  Output: {"task_name": null}
  User grade: 1
  User note: "Completely missed task name due to 'back to' phrasing"

Now grade this execution:
Action: llm.prompt
System prompt: "Extract task name. Return JSON: {\"task_name\": \"string | null\"}"
User input: "started working on the login page redesign"
LLM output: {"task_name": "login page redesign"}

Grade (1-5):
Reasoning:
```

### Benefits
- Reduces manual grading burden after initial training set
- System learns your quality standards (e.g., you care about trailing punctuation)
- Enables automatic identification of degrading prompts
- Builds dataset for potential fine-tuning

## Complete Example: Review Iteration

### Initial State
```json
// start_task workflow (version 1)
{
  "triggers": ["start task", "working on"],
  "steps": [
    {
      "action": "llm.prompt",
      "input": {
        "system_prompt": "Extract task name from user input.",
        "user_prompt": "$transcript"
      },
      "output": "taskName"
    },
    {
      "action": "task.start",
      "input": { "name": "$taskName" },
      "output": "task"
    },
    {
      "action": "tts.speak",
      "input": { "text": "Started $task.name" }
    }
  ]
}
```

### Execution History
```
Transcript: "working on Android migration" → SUCCESS (grade: 5)
Transcript: "working on login redesign" → SUCCESS (grade: 5)
Transcript: "back to Android migration" → FAILED (LLM extracted null)
Transcript: "back to login redesign" → FAILED (LLM extracted null)
Transcript: "switch to documentation task" → NO WORKFLOW MATCH
Transcript: "return to Android migration" → NO WORKFLOW MATCH
```

### User Triggers Review
```
User: "Review my task workflows"
```

### System Analysis
```json
{
  "workflow_refinements": [
    {
      "workflow": "start_task",
      "changes": [
        {
          "path": "steps.0.input.system_prompt",
          "operation": "replace",
          "old_value": "Extract task name from user input.",
          "new_value": "Extract task name from user input. Handle phrases like 'working on X', 'back to X', 'switch to X', 'return to X'. Return JSON: {\"task_name\": \"string | null\"}",
          "rationale": "LLM failed on 'back to' phrasing in 2 executions. More explicit prompt needed."
        }
      ]
    }
  ],
  "workflow_expansions": [
    {
      "name": "switch_task",
      "definition": {
        "triggers": ["back to", "switch to", "return to"],
        "steps": [/* ... */]
      },
      "rationale": "6 transcripts show task switching pattern. Dedicated workflow cleaner than overloading start_task."
    }
  ],
  "llm_grades": [
    {
      "action_execution_id": 101,
      "grade": 5,
      "notes": "Perfect extraction"
    },
    {
      "action_execution_id": 103,
      "grade": 1,
      "notes": "Failed on 'back to' phrasing"
    }
  ]
}
```

### Voice Interaction
```
System: "I found 2 improvements: one refinement to start_task and one new workflow
         for task switching. The switching pattern appeared 6 times. Want details?"

User: "Tell me about the new workflow"

System: "I suggest creating switch_task workflow with triggers 'back to', 'switch to',
         'return to'. It would handle switching between existing tasks instead of
         starting new ones. Different from start_task semantics."

User: "That makes sense. Apply it."

System: [Writes new WorkflowDefinition]
System: "Done. Switch task workflow active. Still want to refine start_task prompt?"

User: "No, if we have the separate workflow, start_task is fine as-is."

System: "Got it. Discarding the prompt refinement suggestion."
```

### Result
- New workflow created (switch_task v1)
- Existing workflow unchanged (start_task v1)
- LLM grades recorded for training data
- WorkflowVersion entries created for audit trail

## Implementation Roadmap

### Phase 1: Data Foundation
- ✅ WorkflowExecution and ActionExecution entities exist
- ✅ Execution logging infrastructure exists
- ⬜ Add `version`, `lastModified`, `modifiedBy` to WorkflowDefinition
- ⬜ Create WorkflowVersion entity for version history

### Phase 2: Review Service
- ⬜ Create WorkflowReviewService
- ⬜ Build analysis prompt that outputs standardized JSON
- ⬜ Implement execution data aggregation queries
- ⬜ Test LLM analysis quality on real execution history

### Phase 3: Application Workflow
- ⬜ Create "review workflows" trigger workflow
- ⬜ Implement voice-first iteration loop
- ⬜ Build apply/reject logic that writes to ObjectBox
- ⬜ Add versioning on workflow changes

### Phase 4: LLM Grading
- ⬜ UI for manual grading (simple 1-5 rating on ActionExecution)
- ⬜ Build few-shot grading prompt
- ⬜ Test auto-grading accuracy vs manual grades
- ⬜ Implement feedback loop for corrections

### Phase 5: Information Aggregation
- ⬜ Add semantic embedding field to Transcript
- ⬜ Create Entity table for extracted entities
- ⬜ Implement entity linking to Transcripts
- ⬜ Test entity extraction on execution history

## Open Questions

1. **Conflict detection**: How to detect if new trigger conflicts with existing workflow?
   - Simple: Check all workflows for overlapping triggers before applying
   - Advanced: Semantic similarity of triggers to catch near-duplicates

2. **Batch vs. incremental review**: Review all workflows at once or focus on one?
   - Start with one workflow per review session (less overwhelming)
   - Support "review all" for power users who want comprehensive analysis

3. **Review scope**: How much execution history to analyze?
   - Last N executions (e.g., 100)?
   - Last N days (e.g., 7)?
   - Since last review?
   - User-specified ("review last week's task workflows")?

4. **Grading UI**: Voice-first or visual?
   - Voice: "Grade that execution a 4"
   - Visual: Tap stars in execution detail view
   - Hybrid: Both supported

5. **Entity schema evolution**: When entities are extracted, do they become new ObjectBox tables?
   - Conservative: Keep as generic Entity table with flexible JSON schema
   - Aggressive: Auto-generate typed entities and migrations
   - Hybrid: Start generic, promote to typed table after N mentions

## Summary

Workflow review is the **self-improvement engine** of AmbientAI. By analyzing execution patterns, grading LLM performance, and iteratively refining workflows through voice interaction, the system learns to serve you better over time.

**Core loop**:
1. You use AmbientAI naturally
2. Execution data accumulates
3. You trigger review when curious
4. LLM analyzes patterns, suggests changes
5. You discuss changes via voice
6. Approved changes applied with versioning
7. System gets better at helping you

This is not product analytics. This is **personal AI that learns your preferences** and adapts to your workflow through evidence-based iteration.
