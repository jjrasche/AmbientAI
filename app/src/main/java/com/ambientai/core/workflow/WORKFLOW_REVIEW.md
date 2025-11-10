# Workflow Review System

## Summary

Workflow review is the **self-improvement engine** of AmbientAI. The review process itself is a workflow (`review_workflow`) - making it transparent, editable, and self-improving.

**Core loop**:
1. Use AmbientAI naturally - execution data accumulates
2. Open review screen - navigate through workflows visually
3. Record thoughts while examining executions (text or voice)
4. System executes `review_workflow` to analyze execution data
5. Review suggested changes
6. System improves to better serve you

**The beautiful recursion**: Because review is a workflow, you can review the review process itself. When you review `review_workflow`, it analyzes its own execution history and suggests improvements to its own analysis prompts. This creates a meta-learning loop where the system learns how to learn better.

This is not product analytics. This is **personal AI that learns how you work** by reviewing what actually happened.

## Architecture

The review system is built on three layers:

1. **`workflow.getExecutionData` action** - Aggregates execution metrics for any workflow (successes, failures, grades, review notes)
2. **`review_workflow` definition** - JSON workflow that calls getExecutionData → LLM analysis → structured suggestions
3. **`WorkflowReviewService`** - Thin wrapper that executes review_workflow and extracts suggestions output

All analysis logic lives in the workflow JSON, not Kotlin code. This means review prompts are visible, editable, and refinable through the same review process they power.

## Implementation Status

### ✅ Complete Features

#### Core Architecture
- **WorkflowReviewService** (WorkflowReviewService.kt:14-52) - Executes review_workflow and parses structured JSON suggestions
- **workflow.getExecutionData action** (WorkflowActionHandler.kt:21-88) - Aggregates execution metrics, failures, grades
- **review_workflow definition** (review_workflow.json, WorkflowSeeder.kt:178-204) - LLM-powered analysis workflow
- **WorkflowDefinition.reviewNotes field** (WorkflowDefinition.kt:12) - Stores voice/text annotations

#### UI Components
- **WorkflowReviewScreen** (WorkflowReviewScreen.kt:29-45) - Main review interface with workflow navigation
- **WorkflowDefinitionCard** (WorkflowReviewScreen.kt:47-50) - Displays workflow details and editable review notes
- **WorkflowExecutionCard** (WorkflowReviewScreen.kt:52-57) - Shows execution details, expandable to action-level
- **ActionExecutionRow** (WorkflowReviewScreen.kt:59-62) - Action details with LLM grading UI
- **SuggestionsDialog** (WorkflowReviewScreen.kt:64-224) - Displays workflow refinements, expansions, and grading insights

#### Data Flow
- Workflows loaded via Flow from repository (reactive updates)
- Previous/Next navigation between workflows
- Execution history with success/failure indicators
- Expandable action details with inputs/outputs
- Star rating system for LLM actions (1-5 scale)
- Review notes editable via OutlinedTextField, saved to repository

#### Suggestion Generation
- "Generate Suggestions" button triggers WorkflowReviewService
- LLM analyzes execution data + review notes + grades
- Returns structured JSON with three suggestion types:
  - **workflow_refinements**: Add/remove triggers, update prompts, modify TTS
  - **workflow_expansions**: Suggest new workflows for unmet patterns
  - **llm_grading_insights**: Auto-grade suggestions based on user patterns
- All suggestions displayed with rationale in dialog

### ⚠️ Partially Complete Features

#### Voice Recording
**Status**: UI button exists but not connected to speech recognition

**What's implemented**:
- "Record Thoughts" button with visual state toggle (WorkflowReviewScreen.kt:42)
- `isRecording` state variable tracks recording status
- Button changes to red "Stop" when active

**What's missing**:
- Integration with VoiceListeningService or SpeechRecognizer
- Capturing voice input and transcribing to text
- Appending transcribed notes to `workflow.reviewNotes`
- Visual feedback showing transcription in progress

**Implementation path**:
1. Inject VoiceListeningService or SpeechRecognizer into WorkflowReviewScreen
2. On "Record" button press: start STT with dedicated listener
3. On "Stop": save transcript to current workflow's reviewNotes field
4. Display transcription feedback (partial/final) in UI

#### Suggestion Application
**Status**: Dialog displays suggestions but no apply/reject workflow

**What's implemented**:
- SuggestionsDialog shows all three suggestion types with full details
- Loading state while LLM generates suggestions
- Error handling for failed suggestion generation

**What's missing**:
- Accept/Reject buttons for individual suggestions
- "Apply All" action to batch-apply multiple changes
- Actual mutation of WorkflowDefinition.definition JSON
- Confirmation UI showing before/after changes
- Rollback capability if applied changes cause issues

**Implementation path**:
1. Add Accept/Reject buttons to each suggestion card in dialog
2. Parse WorkflowDefinition.definition JSON
3. For refinements: apply JSONPath-style updates to workflow JSON
4. For expansions: create new WorkflowDefinition entities
5. Save updated definitions to repository
6. Show confirmation snackbar with undo option

### ❌ Not Implemented (Future Features)

#### Iterative Voice Refinement
**Description**: Voice-based conversation to refine suggestions before applying

**Design intent**:
- After seeing initial suggestions, user speaks: "I don't want that trigger change"
- System re-runs review_workflow with additional context
- New suggestions reflect user's refinement requests
- Continues until user satisfied

**Why deferred**: Requires multi-turn conversation state management within review context

#### LLM-Assisted Auto-Grading
**Description**: LLM grades future actions based on user's grading patterns

**Design intent**:
- User manually grades ~20-30 diverse LLM actions
- System builds few-shot prompt from user's grading examples
- Future executions auto-graded based on learned preferences
- User spot-checks and corrects when wrong

**Why deferred**: Requires sufficient grading data to establish patterns, prompt engineering for grading consistency

#### Complex Conditional Suggestions
**Description**: LLM suggests adding control.if logic, nested steps, etc.

**Design intent**: System recognizes patterns like "user said X but workflow did Y" and suggests conditional branching

**Why deferred**: JSON manipulation for complex structural changes is risky, needs validation layer

## Philosophy

The workflow review system is the **core improvement mechanism** for AmbientAI. Everything is a workflow - even base transcription. By reviewing workflow execution patterns, the system learns how you work and iteratively refines itself to better serve your needs.

Review is manually triggered when you want to reflect on and improve your workflows. All data stays on device. You control when review happens and what changes are applied.

## Review Process

### Visual Review Screen

A dedicated screen shows all workflows with their recent executions. Navigate through workflows one at a time (previous/next buttons). For each workflow, see:
- Workflow definition (triggers, steps, enabled status)
- Review notes text field for annotations
- Recent executions (transcripts, success/failure, timing)
- Action-level details (inputs, outputs, errors, latency)

### Recording Review Thoughts

While examining a workflow, **record your thoughts**:
- Via text: type directly into "Review Notes" field (auto-saved)
- Via voice: press "Record Thoughts" button (🚧 **UI exists, not yet functional**)

Example annotations:
- "This trigger phrase is too broad"
- "The LLM response was too wordy here"
- "This action failed because X"
- "I don't want this workflow triggered anymore"

These annotations are the primary input for generating improvement suggestions.

### Review Considerations

As you review each workflow, consider:
- **Trigger accuracy**: Did I want this workflow triggered?
- **Action failures**: Did any actions fail? Why? What needs to change?
- **LLM quality**: Was the LLM response correct? Too wordy? Missing information?
- **Action correctness**: Did the action do what I expected?
- **Performance**: Are actions taking too long? (see latencyMs)

### LLM Grading

For each LLM action, tap stars to grade on a 1-5 scale:
- **5**: Perfect - exactly what was needed
- **4**: Good - minor issues (formatting, trailing punctuation)
- **3**: Acceptable - suboptimal (extra words, wrong case)
- **2**: Partially wrong - missed field or wrong value
- **1**: Completely failed

Grading builds training data for:
- Identifying low-quality prompts (avg grade < 3)
- Prompt improvement suggestions
- Future: auto-grading based on your preferences

Grades are stored in `ActionExecution.grade` field and displayed in suggestions dialog.

## Review Outputs: Structured Suggestions

LLM analyzes your review notes, execution data, and grades to generate structured change recommendations.

### 1. Workflow Refinement

Optimize existing workflows with atomic changes:
- **trigger_add**: Add new trigger phrase
- **trigger_remove**: Remove overly broad/wrong trigger
- **prompt_update**: Clarify LLM system prompt, add examples, handle edge cases
- **tts_update**: Modify what's spoken to user

**Format**:
```json
{
  "type": "prompt_update",
  "path": "steps.0.input.systemPrompt",
  "value": "New improved prompt text",
  "old_value": "Original prompt text",
  "rationale": "User noted responses were too wordy. Shortened prompt to emphasize brevity."
}
```

**Note**: Complex conditional changes (adding if/else logic) are NOT auto-suggested. Keep refinements simple and atomic.

### 2. Workflow Expansion

Create new workflows when patterns emerge:
- User said similar phrases 3+ times with no workflow match
- Repeated manual sequences that could be automated
- Clear semantic gap in existing workflows

**Format**:
```json
{
  "name": "check_weather",
  "triggers": ["what's the weather", "weather today"],
  "rationale": "User asked about weather 5 times with no matching workflow. Conversational default handled it but dedicated workflow would be faster."
}
```

### 3. LLM Grading Insights

Aggregate grading patterns:
- Actions with consistently low grades (avg < 3)
- Prompt improvement suggestions based on failure patterns
- Future: Auto-grade suggestions for ungraded actions

**Format**:
```json
{
  "action_id": 123,
  "suggested_grade": 4,
  "notes": "Similar to action #115 which user graded 4. Response length and format match user's good examples."
}
```

## Applying Changes (🚧 UI Not Yet Implemented)

**Current state**: SuggestionsDialog displays all suggestions but only has "Close" button.

**Planned workflow**:
1. **Generate suggestions**: Press "Generate Suggestions" → LLM analyzes → dialog shows results
2. **Review suggestions**: Visual list of proposed changes with rationale
3. **Accept/Reject**: Buttons on each suggestion card (or "Apply All" for batch)
4. **Confirmation**: Show before/after diff for refinements
5. **Apply**: Update WorkflowDefinition JSON and save to repository
6. **Feedback**: Snackbar confirms changes with undo option

Changes should be applied atomically. Each applied change updates the workflow definition immediately and can be tested.

## Data Model

### WorkflowDefinition Entity
```kotlin
@Entity
data class WorkflowDefinition(
    @Id var id: Long = 0,
    var name: String,
    var definition: String, // JSON workflow definition
    var enabled: Boolean = true,
    var reviewNotes: String = "" // Voice/text annotations from review sessions
)
```

### ActionExecution Entity
Includes `grade: Int?` field (1-5, nullable) for LLM action quality ratings.

### Review Suggestions (Data Classes)
```kotlin
data class ReviewSuggestions(
    val workflowRefinements: List<WorkflowRefinement>,
    val workflowExpansions: List<WorkflowExpansion>,
    val llmGradingInsights: List<LlmGradingInsight>
)

data class WorkflowRefinement(
    val type: String,        // trigger_add, trigger_remove, prompt_update, tts_update
    val path: String,        // JSONPath to field
    val value: String,       // New value
    val oldValue: String,    // Existing value
    val rationale: String    // Why this improves workflow
)

data class WorkflowExpansion(
    val name: String,           // Suggested workflow name
    val triggers: List<String>, // Trigger phrases
    val rationale: String       // Why needed
)

data class LlmGradingInsight(
    val actionId: Long,      // ActionExecution ID
    val suggestedGrade: Int, // 1-5
    val notes: String        // Grading rationale
)
```

## workflow.getExecutionData Action

**Action**: `workflow.getExecutionData`

**Input**:
```json
{
  "workflowId": 123,
  "limit": 100
}
```

**Output**: Comprehensive execution analytics
```json
{
  "workflow": {
    "id": 123,
    "name": "task_status",
    "definition": "{...}",
    "enabled": true,
    "reviewNotes": "User said responses too verbose"
  },
  "summary": {
    "totalExecutions": 47,
    "successfulExecutions": 45,
    "failedExecutions": 2,
    "llmActions": 47,
    "gradedLlmActions": 12,
    "avgLlmGrade": 4.2
  },
  "recentExecutions": [...],      // Last 10 executions
  "failedExecutions": [...],      // All failures
  "failedActions": [...],         // All failed actions
  "gradedActions": [...]          // All graded LLM actions
}
```

**Implementation**: WorkflowActionHandler.kt:21-88

## review_workflow Definition

**File**: review_workflow.json / WorkflowSeeder.kt:178-204

**Structure**:
1. Step 1: Call `workflow.getExecutionData` with target workflow ID from context
2. Step 2: LLM analyzes execution data and review notes
3. Output: Structured JSON with refinements, expansions, and grading insights

**Key prompt sections**:
- Workflow definition and status
- Review notes (user annotations)
- Execution summary statistics
- Recent, failed, and graded execution details
- Output schema specification

**Note**: workflow is **disabled** and has **no triggers**. It's only executed programmatically via WorkflowReviewService.

## Navigation Path

Timeline Screen → (Database button) → Database Screen → (Review button) → Workflow Review Screen → (Back) → Database Screen

## Development Notes

### Adding New Suggestion Types

1. Update review_workflow.json prompt with new suggestion type in output schema
2. Add data class in WorkflowReviewService.kt
3. Add parser in `parseReviewSuggestions()`
4. Update SuggestionsDialog.kt to display new type

### Testing Review System

1. Use app naturally to generate workflow executions
2. Grade some LLM actions (especially failures)
3. Navigate to Database → Review
4. Add review notes for a workflow
5. Press "Generate Suggestions"
6. Verify suggestions are contextually relevant

### Debugging Suggestions

- Check review_workflow execution logs in WorkflowExecution table
- Verify LLM returned valid JSON (common failure: markdown fences)
- Inspect `executionData` JSON structure passed to LLM
- Test with workflows that have clear issues (failures, low grades)

## Future Enhancements

### Voice Recording Integration
**Priority**: High (core UX feature for voice-first app)

**Tasks**:
- Connect "Record Thoughts" button to SpeechRecognizer
- Display transcription feedback during recording
- Append final transcript to reviewNotes field
- Consider voice command to trigger recording while reviewing

### Suggestion Application
**Priority**: High (completes the improvement loop)

**Tasks**:
- Add Accept/Reject UI to each suggestion card
- Implement JSON manipulation for workflow refinements
- Create new WorkflowDefinition entities for expansions
- Add confirmation/undo mechanism
- Handle edge cases (malformed JSON, conflicting changes)

### Iterative Refinement
**Priority**: Medium (nice-to-have for power users)

**Tasks**:
- Add voice input to SuggestionsDialog
- Re-run review_workflow with additional context
- Track refinement conversation state
- Limit iterations to prevent infinite loops

### Auto-Grading
**Priority**: Low (requires sufficient training data)

**Tasks**:
- Create grading patterns analysis workflow
- Build few-shot prompt from user's grading history
- Add auto-grade action to workflow system
- Provide spot-check UI for corrections

### Review Analytics
**Priority**: Low (informational, not critical)

**Tasks**:
- Track which suggestions are accepted/rejected
- Measure improvement in success rates post-review
- Identify workflows that need frequent review
- Surface insights: "Your task workflows have 95% success rate"
