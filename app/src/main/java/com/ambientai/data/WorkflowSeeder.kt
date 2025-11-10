package com.ambientai.data

import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.WorkflowDefinitionRepository

class WorkflowSeeder {
    private val repo = WorkflowDefinitionRepository()
    fun seed() {
        seedTaskWorkflows()
        seedLogWorkflow()
        seedNarrativeWorkflow()
        seedReviewWorkflow()

        repo.save(WorkflowDefinition(
            name = "web_search",
            enabled = true,
            definition = """{
  "triggers":["search for","look up"],
  "steps":[
    {"action":"llm.prompt","input":{
      "systemPrompt":"Extract the search query from the user's input. Return ONLY the search query, nothing else. Remove phrases like 'search for', 'look up', etc.",
      "userPrompt":"${'$'}transcript",
      "temperature":0.3,
      "maxTokens":50
    },"output":"searchQuery"},
    {"action":"search.query","input":{
      "query":"${'$'}searchQuery.response",
      "numResults":3
    },"output":"searchResults"},
    {"action":"llm.prompt","input":{
      "systemPrompt":"You are a helpful assistant. Answer the user's question based on the search results provided. Be concise and cite sources when relevant.",
      "userPrompt":"Question: ${'$'}transcript\n\nSearch Results:\n${'$'}searchResults.snippets",
      "temperature":0.7,
      "maxTokens":50
    },"output":"answer"},
    {"action":"tts.speak","input":{"text":"${'$'}answer.response"}}
  ]
}""".trimIndent()
        ))

        repo.save(WorkflowDefinition(
            name = "remember_this",
            enabled = true,
            definition = """{
  "triggers":["remember this","remember that","don't forget"],
  "steps":[
    {"action":"llm.prompt","input":{
      "systemPrompt":"Extract what the user wants to remember. Return JSON: {\"memory\": \"what they want remembered\"}",
      "userPrompt":"${'$'}transcript",
      "temperature":0.3,
      "maxTokens":100
    },"output":"extracted"},
    {"action":"log.write","input":{
      "type":"memory",
      "data":"${'$'}extracted.response",
      "transcriptId":"${'$'}transcriptId"
    },"output":"entry"},
    {"action":"tts.speak","input":{"text":"Got it"}}
  ]
}""".trimIndent()
        ))
    }
    private fun seedTaskWorkflows() {
        val llmPullNameFromTranscriptAction = """{"action":"llm.prompt","input":{"systemPrompt":"Extract task name from user input. Return only the task name, nothing else.","userPrompt":"${'$'}transcript"},"output":"taskName"},"""
        repo.save(WorkflowDefinition(name = "start_task", enabled = true, definition = """{
  "triggers":["start task","working on","begin task"],
  "steps":[
    $llmPullNameFromTranscriptAction
    {"action":"task.start","input":{"name":"${'$'}taskName.response"},"output":"task"},
    {"action":"tts.speak","input":{"text":"Started ${'$'}task.name"}}
  ]
}""".trimIndent()))
        repo.save(WorkflowDefinition(name = "pause_task", enabled = true, definition = """{
  "triggers":["pause task","stop task","taking a break"],
  "steps":[
    {"action":"task.pause","output":"task"},
    {"action":"tts.speak","input":{"text":"Paused ${'$'}task.name"}}
  ]
}""".trimIndent()))
        repo.save(WorkflowDefinition(name = "complete_task", enabled = true, definition = """{
  "triggers":["complete task","done with task","finished task"],
  "steps":[
    {"action":"task.complete","output":"task"},
    {"action":"tts.speak","input":{"text":"Completed ${'$'}task.name"}}
  ]
}""".trimIndent()))
        repo.save(WorkflowDefinition(name = "task_status", enabled = true, definition = """{
  "triggers":["current task","what am I working on","task status","how long"],
  "steps":[
    {"action":"task.getActive","output":"task"},
    {"action":"tts.speak","input":{"text":"${'$'}task.elapsed on ${'$'}task.name"}}
  ]
}""".trimIndent()))
        repo.save(WorkflowDefinition(name = "switch_task", enabled = true, definition = """{
  "triggers":["switch to","switch task","now working on","back to"],
  "steps":[
    {"action":"task.getNonCompleted","output":"available"},
    {"action":"control.if","condition":"${'$'}available.tasks.length === 0","then":[
      {"action":"tts.speak","input":{"text":"No tasks available. Creating new task."}},
      $llmPullNameFromTranscriptAction
      {"action":"task.start","input":{"name":"${'$'}taskName.response"},"output":"task"},
      {"action":"tts.speak","input":{"text":"Started ${'$'}task.name"}}
    ],"else":[
      {"action":"llm.prompt","input":{"systemPrompt":"You are a task matcher. Given a user's voice input and a list of tasks, determine which task they're referring to. Return ONLY valid JSON. Output format: {\"taskId\": <id or null>, \"reason\": \"brief explanation\"}","userPrompt":"${'$'}transcript\n\nAvailable tasks:\n${'$'}available.tasks","temperature":0.3,"maxTokens":50},"output":"llmResponse"},
      {"action":"control.if","condition":"${'$'}llmResponse.response.taskId !== null","then":[
        {"action":"task.resume","input":{"taskId":"${'$'}llmResponse.response.taskId"},"output":"task"},
        {"action":"tts.speak","input":{"text":"Switched to ${'$'}task.name"}}
      ],"else":[
        {"action":"tts.speak","input":{"text":"Couldn't match task. Creating new."}},
        $llmPullNameFromTranscriptAction
        {"action":"task.start","input":{"name":"${'$'}taskName.response"},"output":"task"},
        {"action":"tts.speak","input":{"text":"Started ${'$'}task.name"}}
      ]}
    ]}
  ]
}""".trimIndent()))
    }

    private fun seedLogWorkflow() {
        repo.save(WorkflowDefinition(name = "log_entry", enabled = true, definition = """{
  "triggers":["log this"],
  "steps":[
    {"action":"llm.prompt","input":{
      "systemPrompt":"Classify what's being logged and extract data.\n\nValid types: medication, food, supplement, activity, symptom, unknown\n\nReturn JSON:\n{\n  \"type\": \"<one of the valid types>\",\n  \"data\": {\n    // For medication: {\"name\": \"string\", \"dosage\": \"string\"}\n    // For food: {\"name\": \"string\", \"quantity\": \"string\", \"meal\": \"string\"}\n    // For supplement: {\"name\": \"string\", \"amount\": \"string\"}\n    // For activity: {\"name\": \"string\", \"duration\": \"string\"}\n    // For symptom: {\"name\": \"string\", \"severity\": \"string\"}\n    // Extract whatever fields make sense for the type\n  }\n}\n\nIf unclear, use type: \"unknown\" and capture what you can.",
      "userPrompt":"${'$'}transcript",
      "temperature":0.3,
      "maxTokens":150
    },"output":"classification"},
    {"action":"log.write","input":{
      "type":"${'$'}classification.response.type",
      "data":"${'$'}classification.response.data",
      "transcriptId":"${'$'}transcriptId"
    },"output":"entry"},
    {"action":"tts.speak","input":{"text":"Logged ${'$'}classification.response.type"}}
  ]
}""".trimIndent()))
    }

    private fun seedNarrativeWorkflow() {
        repo.save(WorkflowDefinition(
            name = "thinker",
            enabled = true,
            definition = """{
  "triggers":{
    "keywords":["update narrative","what do you know","tell me what you understand"],
    "onWorkflowComplete":["start_task","pause_task","complete_task","switch_task","log_entry"]
  },
  "steps":[
    {
      "action":"state.gather",
      "output":"state"
    },
    {
      "action":"llm.prompt",
      "input":{
        "systemPrompt":"You are the internal reasoning system for an ambient AI assistant. Synthesize current state into a 2-3 sentence first-person narrative focusing on: what user is doing (GOALS), why and how it's going (REASONING), key patterns to remember (MEMORY). Be concise and focus on actionable understanding.",
        "userPrompt":"Current state:\n${'$'}state",
        "temperature":0.7,
        "maxTokens":200
      },
      "output":"narrative"
    },
    {
      "action":"narrative.save",
      "input":{
        "text":"${'$'}narrative.response",
        "stateSnapshot":"${'$'}state"
      }
    }
  ]
}""".trimIndent()
        ))
    }
    private fun seedReviewWorkflow() {
        repo.save(WorkflowDefinition(
            name = "review_workflow",
            enabled = false,
            definition = """{
  "triggers":[],
  "steps":[
    {
      "action":"workflow.getExecutionData",
      "input":{
        "workflowId":"${'$'}context.targetWorkflowId",
        "limit":100
      },
      "output":"executionData"
    },
    {
      "action":"llm.prompt",
      "input":{
        "systemPrompt":"You are analyzing workflow execution data to suggest improvements. Return ONLY valid JSON, no markdown fences.",
        "userPrompt":"Analyze workflow execution data and suggest improvements.\n\nWORKFLOW: ${'$'}executionData.workflow.name\nStatus: ${'$'}executionData.workflow.enabled\n\nREVIEW NOTES:\n${'$'}executionData.workflow.reviewNotes\n\nEXECUTION SUMMARY:\n- Total: ${'$'}executionData.summary.totalExecutions\n- Successful: ${'$'}executionData.summary.successfulExecutions\n- Failed: ${'$'}executionData.summary.failedExecutions\n- LLM actions: ${'$'}executionData.summary.llmActions\n- Graded: ${'$'}executionData.summary.gradedLlmActions\n- Avg grade: ${'$'}executionData.summary.avgLlmGrade\n\nRECENT EXECUTIONS:\n${'$'}executionData.recentExecutions\n\nFAILED EXECUTIONS:\n${'$'}executionData.failedExecutions\n\nFAILED ACTIONS:\n${'$'}executionData.failedActions\n\nGRADED ACTIONS:\n${'$'}executionData.gradedActions\n\nReturn JSON:\n{\n  \"workflow_refinements\": [{\"type\": \"trigger_add|trigger_remove|prompt_update|tts_update\", \"path\": \"path\", \"value\": \"new\", \"old_value\": \"old\", \"rationale\": \"why\"}],\n  \"workflow_expansions\": [{\"name\": \"name\", \"triggers\": [\"phrases\"], \"rationale\": \"why\"}],\n  \"llm_grading_insights\": [{\"action_id\": 123, \"suggested_grade\": 4, \"notes\": \"why\"}]\n}"
      },
      "output":"suggestions"
    }
  ]
}""".trimIndent()
        ))
    }
}