package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

/**
 * Entity representing a complete LLM inference interaction.
 * Stores both request and response in a single entity.
 */
@Entity
data class LlmInteraction(
    @Id var id: Long = 0,
    var systemPrompt: String,     // System instructions
    var userPrompt: String,        // User query (context)
    var response: String,          // LLM generated text
    var timestamp: Long,
    var latencyMs: Long,           // Time taken for inference
    var model: String,             // Model used (e.g., "llama-3.1-8b-instant")
    var temperature: Float,        // Temperature used
    var maxTokens: Int,            // Max tokens requested
    var grade: Int? = null         // 0-5 rating, null if not graded yet
)