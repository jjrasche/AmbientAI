# Workflow JSON Structure

## Overview

Workflows are defined in JSON format and stored in ObjectBox as `WorkflowDefinition` entities. Each workflow contains triggers (phrases that activate it) and a sequence of steps (actions to execute).

## Basic Structure

```json
{
  "triggers": ["phrase one", "phrase two", "phrase three"],
  "steps": [
    {
      "action": "action.name",
      "input": {
        "param1": "value1",
        "param2": "$variable"
      },
      "output": "variableName"
    }
  ]
}
```

## Triggers

An array of phrases that activate this workflow. Router uses exact phrase matching (case-insensitive).

```json
{
  "triggers": ["answer me", "what do you think", "your thoughts"]
}
```

When multiple workflows match, the system speaks an error via TTS and doesn't execute anything.

## Steps

Each step is an action to execute. Steps run sequentially unless control flow (if/else) changes the path.

### Action Structure

```json
{
  "action": "domain.action",     // Required: action identifier
  "input": {                      // Required: parameters for this action
    "key": "value",
    "variable": "$variableName"   // Variable substitution
  },
  "output": "resultVariable"      // Optional: store result in this variable
}
```

### Variable Substitution

Reference variables using `$variableName` syntax. Variables come from:
- Built-in: `$transcript` (original user input)
- Previous step outputs: Any step with `"output": "varName"` creates `$varName`

Example:
```json
{
  "steps": [
    {
      "action": "buffer.getRecentTranscript",
      "input": { "chunks": 3 },
      "output": "context"           // Creates $context variable
    },
    {
      "action": "llm.prompt",
      "input": {
        "system_prompt": "You are helpful",
        "user_prompt": "$context"   // Uses $context from previous step
      },
      "output": "response"          // Creates $response variable
    },
    {
      "action": "tts.speak",
      "input": { "text": "$response" }  // Uses $response
    }
  ]
}
```

## Control Flow

### If/Else Conditionals

```json
{
  "action": "control.if",
  "condition": "$error === null && $data.name !== null",
  "then": [
    {
      "action": "db.write",
      "input": { "table": "tasks", "data": "$data" }
    }
  ],
  "else": [
    {
      "action": "tts.speak",
      "input": { "text": "Something went wrong" }
    }
  ]
}
```

The `condition` is a JavaScript-like expression evaluated against current variables.

## Complete Example: Conversational Reply

```json
{
  "triggers": ["answer me", "what do you think", "your thoughts"],
  "steps": [
    {
      "action": "buffer.getRecentTranscript",
      "input": { "chunks": 3 },
      "output": "transcript"
    },
    {
      "action": "llm.prompt",
      "input": {
        "system_prompt": "You are a helpful assistant. Provide conversational responses in 1-2 sentences.",
        "user_prompt": "$transcript",
        "temperature": 0.7,
        "max_tokens": 100
      },
      "output": "response"
    },
    {
      "action": "tts.speak",
      "input": { "text": "$response" }
    }
  ]
}
```

## Complete Example: Task Tracking with Conditionals

```json
{
  "triggers": ["start task", "working on"],
  "steps": [
    {
      "action": "buffer.getRecentTranscript",
      "input": { "chunks": 2 },
      "output": "transcript"
    },
    {
      "action": "llm.prompt",
      "input": {
        "system_prompt": "Extract task name. Return JSON: {\"task_name\": \"string | null\"}",
        "user_prompt": "$transcript"
      },
      "output": "llmResponse"
    },
    {
      "action": "json.parse",
      "input": {
        "text": "$llmResponse",
        "schema": { "task_name": "string | null" }
      },
      "output": "taskData"
    },
    {
      "action": "control.if",
      "condition": "$taskData.task_name !== null",
      "then": [
        {
          "action": "db.write",
          "input": {
            "table": "tasks",
            "data": {
              "name": "$taskData.task_name",
              "status": "active",
              "started_at": "{{NOW}}"
            }
          }
        },
        {
          "action": "tts.speak",
          "input": { "text": "Started $taskData.task_name" }
        }
      ],
      "else": [
        {
          "action": "tts.speak",
          "input": { "text": "Didn't catch the task name" }
        }
      ]
    }
  ]
}
```

## Built-in Template Values

Use double curly braces for built-in values:
- `{{NOW}}` - Current timestamp in milliseconds
- `{{UUID}}` - Generate a UUID string

Example:
```json
{
  "action": "db.write",
  "input": {
    "table": "tasks",
    "data": {
      "id": "{{UUID}}",
      "created_at": "{{NOW}}"
    }
  }
}
```

## Available Actions (Phase 3 Implementation)

### Buffer Actions
- `buffer.getRecentTranscript` - Get last N transcript chunks

### LLM Actions
- `llm.prompt` - Generate LLM response

### TTS Actions
- `tts.speak` - Speak text to user

### JSON Actions
- `json.parse` - Parse and validate JSON with schema

### Database Actions (Phase 4)
- `db.write` - Write entity to ObjectBox
- `db.query` - Query entities
- `db.calculateDuration` - Calculate time between timestamps

### Control Flow Actions
- `control.if` - Conditional execution

## Execution Logging

Every action execution is logged to `ActionExecutionLog`:
- `inputJson` - Fully resolved inputs after variable substitution
- `outputJson` - Action output
- `latencyMs` - How long the action took
- `stepPath` - Position in execution tree (e.g., "3.then.0")

This enables:
- Debugging workflow execution
- Analyzing action performance
- Grading LLM responses
- Replaying workflows with different models

## Error Handling

If an action fails:
1. Action logs error in `ActionExecutionLog.errorMessage`
2. Workflow logs error in `WorkflowExecutionLog.errorMessage`
3. System speaks error via TTS
4. Workflow execution terminates

Example error: "Action failed at step 1: llm.prompt - API timeout after 10s"