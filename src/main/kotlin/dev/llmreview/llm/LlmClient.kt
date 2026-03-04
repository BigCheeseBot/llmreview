package dev.llmreview.llm

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.1,
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice>,
)

@Serializable
data class Choice(
    val message: ChatMessage,
)

class LlmClient(
    private val endpoint: String,
    private val model: String,
    private val temperature: Double = 0.1,
    private val apiKey: String? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    /**
     * Send a chat completion request and return the assistant's response content.
     */
    suspend fun chatCompletion(systemPrompt: String, userPrompt: String): String {
        val url = "${endpoint.trimEnd('/')}/v1/chat/completions"

        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt),
            ),
            temperature = temperature,
        )

        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            if (apiKey != null) {
                header("Authorization", "Bearer $apiKey")
            }
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            throw LlmException("LLM API returned ${response.status}: ${response.bodyAsText()}")
        }

        val completion = json.decodeFromString<ChatCompletionResponse>(response.bodyAsText())
        return completion.choices.firstOrNull()?.message?.content
            ?: throw LlmException("LLM returned empty response")
    }

    fun close() {
        client.close()
    }
}

class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
