package com.ambientai.data

import android.content.Context
import android.util.Log
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.WorkflowDefinitionRepository

/**
 * Seeds initial workflows into database on first run.
 * Checks if workflows exist before inserting to avoid duplicates.
 */
class WorkflowSeeder(private val context: Context) {

    private val repo = WorkflowDefinitionRepository(context)

    companion object {
        private const val TAG = "WorkflowSeeder"
        private const val PREFS_NAME = "workflow_seeder"
        private const val KEY_SEEDED = "workflows_seeded"
    }

    /**
     * Seed workflows if not already seeded.
     * Safe to call multiple times - uses SharedPreferences flag.
     */
    fun seedIfNeeded() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alreadySeeded = prefs.getBoolean(KEY_SEEDED, false)

        if (alreadySeeded) {
            Log.d(TAG, "Workflows already seeded, skipping")
            return
        }

        Log.d(TAG, "Seeding initial workflows...")

        try {
            seedTaskWorkflows()

            // Mark as seeded
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            Log.d(TAG, "Successfully seeded ${repo.count()} workflows")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seed workflows", e)
        }
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

        Log.d(TAG, "Seeded 5 task workflows")
    }
}