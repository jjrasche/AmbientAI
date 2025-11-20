package com.ambientai.data

import android.content.Context
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.IWorkflowDefinitionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowSeeder @Inject constructor(
    private val repo: IWorkflowDefinitionRepository,
    @ApplicationContext private val context: Context
) {
    fun reseedAll() {
        repo.deleteAll()
        seed()
    }
    fun seed() = context.assets.open("workflows.json").bufferedReader().use { reader ->
        val json = JSONObject(reader.readText())
        json.getJSONArray("workflows").let { workflows ->
            (0 until workflows.length()).forEach { i ->
                workflows.getJSONObject(i).let { workflow ->
                    repo.save(WorkflowDefinition(
                        name = workflow.getString("name"),
                        enabled = workflow.getBoolean("enabled"),
                        definition = workflow.getJSONObject("definition").toString()
                    ))
                }
            }
        }
    }
}