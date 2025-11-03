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
        // Start Task
        repo.save(WorkflowDefinition(
            name = "start_task",
            definition = """{
  "triggers":["start task","working on","begin task"],
  "steps":[
    {"action":"llm.prompt","input":{"systemPrompt":"Extract task name from user input. Return only the task name, nothing else.","userPrompt":"${'$'}transcript"},"output":"taskName"},
    {"action":"task.start","input":{"name":"${'$'}taskName.response"},"output":"result"},
    {"action":"control.if","condition":"${'$'}result.success === true","then":[
    {"action":"tts.speak","input":{"text":"Started ${'$'}result.task.name"}}],"else":[
    {"action":"tts.speak","input":{"text":"${'$'}result.error"}}]}
  ]
}""".trimIndent(),
            enabled = true
        ))

        // Pause Task
        repo.save(WorkflowDefinition(
            name = "pause_task",
            definition = """{
  "triggers":["pause task","stop task","taking a break"],
  "steps":[
    {"action":"task.pause","input":{},"output":"result"},
    {"action":"control.if","condition":"${'$'}result.success === true","then":[
    {"action":"tts.speak","input":{"text":"Paused ${'$'}result.task.name"}}],"else":[
    {"action":"tts.speak","input":{"text":"${'$'}result.error"}}]}
  ]
}""".trimIndent(),
            enabled = true
        ))

        // Complete Task
        repo.save(WorkflowDefinition(
            name = "complete_task",
            definition = """{
  "triggers":["complete task","done with task","finished task"],
  "steps":[
    {"action":"task.complete","input":{},"output":"result"},
    {"action":"control.if","condition":"${'$'}result.success === true","then":[
    {"action":"tts.speak","input":{"text":"Completed ${'$'}result.task.name"}}],"else":[
    {"action":"tts.speak","input":{"text":"${'$'}result.error"}}]}
  ]
}""".trimIndent(),
            enabled = true
        ))

        // Task Status
        repo.save(WorkflowDefinition(
            name = "task_status",
            definition = """{
  "triggers":["current task","what am I working on","task status","how long"],
  "steps":[
    {"action":"task.getActive","input":{},"output":"task"},
    {"action":"control.if","condition":"${'$'}task.status === 'Active'","then":[
    {"action":"tts.speak","input":{"text":"${'$'}task.elapsed on ${'$'}task.name"}}],"else":[
    {"action":"tts.speak","input":{"text":"No active task"}}]}
  ]
}""".trimIndent(),
            enabled = true
        ))

        repo.save(WorkflowDefinition(
            name = "switch_task",
            definition = """{
  "triggers":["switch to","switch task","now working on","back to"],
  "steps":[
    {"action":"task.getNonCompleted","input":{},"output":"available"},
    {"action":"control.if","condition":"${'$'}available.tasks.length === 0","then":[
      {"action":"tts.speak","input":{"text":"No tasks available. Creating new task."}},
      {"action":"task.start","input":{"name":"${'$'}transcript"},"output":"result"},
      {"action":"tts.speak","input":{"text":"Started ${'$'}result.task.name"}}
    ],"else":[
      {"action":"llm.prompt","input":{"systemPrompt":"You are a task matcher. Given a user's voice input and a list of tasks, determine which task they're referring to. Match on partial names, keywords, or descriptions. Handle phrases like 'previous task', 'last one', 'the bug thing'. If no clear match, return null. Return ONLY valid JSON. Output format: {\"taskId\": <id or null>, \"reason\": \"brief explanation\"}","userPrompt":" \"${'$'}transcript\"\n\nAvailable tasks:\n${'$'}available.tasks\n\nWhich task are they referring to?","temperature":0.3,"maxTokens":50},"output":"llmResponse"},
      {"action":"json.parse","input":{"text":"${'$'}llmResponse.response","schema":{"taskId":"number | null","reason":"string"}},"output":"match"},
      {"action":"control.if","condition":"${'$'}match.taskId !== null","then":[
        {"action":"task.resume","input":{"taskId":"${'$'}match.taskId"},"output":"result"},
        {"action":"control.if","condition":"${'$'}result.success === true","then":[
          {"action":"tts.speak","input":{"text":"Switched to ${'$'}result.task.name"}}
        ],"else":[
          {"action":"tts.speak","input":{"text":"${'$'}result.error"}}
        ]}
      ],"else":[
        {"action":"tts.speak","input":{"text":"Couldn't match task. Creating new."}},
        {"action":"task.start","input":{"name":"${'$'}transcript"},"output":"result"},
        {"action":"tts.speak","input":{"text":"Started ${'$'}result.task.name"}}
      ]}
    ]}
  ]
}""".trimIndent(),
            enabled = true
        ))

        Log.d(TAG, "Seeded 4 task workflows")
    }
}