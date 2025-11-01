package com.ambientai.data

/**
 * Data classes for Groq API requests and responses.
 */

data class LlmRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Float,
    val max_tokens: Int,
    val stream: Boolean
)

data class Message(
    val role: String,
    val content: String
)

data class LlmResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage?
)

data class Choice(
    val message: Message,
    val finish_reason: String?
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)