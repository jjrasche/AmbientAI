package com.ambientai.data

import android.content.Context
import android.util.Log
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.WorkflowDefinitionRepository

/**
 * Seeds initial workflows into database on first run.
 * Checks if workflows exist before inserting to avoid duplicates.
 */
class WorkflowSeeder(context: Context) {

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
    fun seedIfNeeded(context: Context) {
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

    /**
     * Force re-seed all workflows (overwrites existing).
     * Use for development/testing only.
     */
    fun forceReseed(context: Context) {
        Log.d(TAG, "Force reseeding workflows...")

        // Delete all existing workflows
        repo.getAll().forEach { repo.delete(it) }

        // Seed fresh
        seedTaskWorkflows()

        // Update flag
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()

        Log.d(TAG, "Force reseed complete: ${repo.count()} workflows")
    }

    private fun seedTaskWorkflows() {
        // Start Task
        repo.save(WorkflowDefinition(
            name = "start_task",
            definition = """{
  "triggers":["start task","working on","begin task"],
  "steps":[
    {"action":"task.start","input":{"name":"${'$'}transcript"},"output":"result"},
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
    {"action":"task.status","input":{},"output":"status"},
    {"action":"control.if","condition":"${'$'}status.hasActive === true","then":[
    {"action":"tts.speak","input":{"text":"${'$'}status.elapsed on ${'$'}status.name"}}],"else":[
    {"action":"tts.speak","input":{"text":"No active task"}}]}
  ]
}""".trimIndent(),
            enabled = true
        ))

        Log.d(TAG, "Seeded 4 task workflows")
    }
}