package com.ambientai.data

import android.content.Context
import android.util.Log
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.WorkflowDefinitionRepository

class WorkflowSeeder() {
    private val repo = WorkflowDefinitionRepository()
    fun seed() {
        seedTaskWorkflows()

        repo.save(WorkflowDefinition(
            name = "web_search",
            enabled = true,
            definition = """{
  "triggers":["search for","look up","find information about","what is"],
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
      "maxTokens":200
    },"output":"answer"},
    {"action":"tts.speak","input":{"text":"${'$'}answer.response"}}
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

        Log.d("WorkflowSeeder", "Seeded 5 task workflows")
    }


}