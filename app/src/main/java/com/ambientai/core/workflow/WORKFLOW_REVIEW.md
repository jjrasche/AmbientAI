# Workflow Review System

## Summary

Workflow review is the **self-improvement engine** of AmbientAI. By analyzing execution patterns, grading LLM performance, and iteratively refining workflows, the system learns your preferences and adapts to your workflow through evidence-based iteration.

**Core loop**:
1. Use AmbientAI naturally - execution data accumulates
2. Open review screen - navigate through workflows visually
3. Voice record thoughts while examining executions
4. LLM analyzes your annotations and execution data
5. Approve/reject suggested changes
6. System improves to better serve you

This is not product analytics. This is **personal AI that learns how you work** by reviewing what actually happened.

## Philosophy

The workflow review system is the **core improvement mechanism** for AmbientAI. Everything is a workflow - even base transcription. By reviewing workflow execution patterns, the system learns how you work and iteratively refines itself to better serve your needs.

Review is manually triggered when you want to reflect on and improve your workflows. All data stays on device. You control when review happens and what changes are applied.

## Review Process

### Visual Review Screen
A dedicated screen shows all workflows with their recent executions. Navigate through workflows one at a time (previous/next navigation). For each workflow, see:
- Workflow definition (triggers, steps)
- Recent executions (transcripts, success/failure, timing)
- Action-level details (inputs, outputs, errors)

### Voice Annotation
While examining a workflow, **voice record your thoughts**:
- "This trigger phrase is too broad"
- "The LLM response was too wordy here"
- "This action failed because X"
- "I don't want this workflow triggered anymore"

These annotations are saved to the workflow and used as primary input for generating improvements.

### Review Considerations
As you review each workflow, consider:
- **Trigger accuracy**: Did I want this workflow triggered?
- **Action failures**: Did any actions fail? Why? What needs to change?
- **LLM quality**: Was the LLM response correct? Too wordy? Missing information?
- **Action correctness**: Did the action do what I expected?

### LLM Grading
For each LLM action, tap stars to grade on a 1-5 scale:
- **5**: Perfect - exactly what was needed
- **4**: Good - minor issues (formatting, trailing punctuation)
- **3**: Acceptable - suboptimal (extra words, wrong case)
- **2**: Partially wrong - missed field or wrong value
- **1**: Completely failed

Grading builds training data for fine-tuning prompts and identifying problematic patterns.

## Review Outputs: Standardized Changes

LLM analyzes your voice annotations, execution data, and grades to generate structured change recommendations:

### 1. Workflow Refinement
Optimize existing workflows:
- **Add/remove triggers**: "Add 'back to' trigger" or "Remove overly broad trigger"
- **Update LLM prompts**: Clarify instructions, add examples, handle edge cases
- **Modify TTS text**: Change what's spoken to user

**Note**: Complex conditional changes (adding if/else logic) are NOT auto-suggested. Keep refinements simple and atomic.

### 2. Workflow Expansion
Create new workflows when patterns emerge:
- User said similar phrases 3+ times with no workflow match
- Repeated manual sequences that could be automated
- Clear semantic gap in existing workflows

### 3. LLM Grading Data
Aggregate grading insights:
- Actions with consistently low grades (avg < 3)
- Prompt improvement suggestions based on failures
- Training data for fine-tuning future prompt optimization

## Applying Changes

After recording annotations and grades, generate improvement suggestions:

1. **Generate suggestions**: LLM analyzes voice annotations + execution data + grades → structured change recommendations
2. **Review suggestions**: Visual list of proposed changes with rationale
3. **Iterate via voice**: Discuss changes ("I don't want this change", "adjust that trigger") → LLM regenerates suggestions
4. **Apply/reject**: Approve changes to write to ObjectBox or reject to discard

Changes are applied atomically. Each applied change updates the workflow definition immediately.

## Storage

### Voice Annotations
Store review thoughts on WorkflowDefinition:
- Add `reviewNotes` field (text or JSON array of timestamped notes)
- Persists your thinking about each workflow
- Used as primary LLM input for generating suggestions

### Grades
ActionExecution already has `grade` field (1-5, nullable). Tap stars to populate it during review.

## Future: LLM-Assisted Grading

Once you've manually graded ~20-30 diverse LLM actions, use those as training data:
- Build few-shot prompt with your grading examples
- LLM auto-grades future executions based on your standards
- Spot-check and correct when wrong
- Reduces manual grading burden while learning your quality preferences
