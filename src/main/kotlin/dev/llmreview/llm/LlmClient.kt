package dev.llmreview.llm

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
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
    private val timeoutMs: Long = 300_000,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = timeoutMs
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

    /**
     * Send a streaming chat completion request. Calls [onToken] for each content chunk
     * as it arrives. Returns the full accumulated response.
     */
    suspend fun chatCompletionStreaming(
        systemPrompt: String,
        userPrompt: String,
        onToken: (String) -> Unit,
    ): String {
        val url = "${endpoint.trimEnd('/')}/v1/chat/completions?stream=true"

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
            header("Accept", "text/event-stream")
            header("Cache-Control", "no-cache")
            header("Connection", "keep-alive")
            if (apiKey != null) {
                header("Authorization", "Bearer $apiKey")
            }
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            throw LlmException("LLM API returned ${response.status}: ${response.bodyAsText()}")
        }

        val body = response.bodyAsText()
        val sb = StringBuilder()

        for (line in body.lines()) {
            val trimmed = line.trim()

            // End of stream
            if (trimmed == "data: [DONE]") break

            // Parse SSE data lines
            if (trimmed.startsWith("data: ")) {
                val data = trimmed.substring(6)
                try {
                    val streamChunk = json.decodeFromString<StreamChunk>(data)
                    val content = streamChunk.choices
                        .firstOrNull()
                        ?.delta
                        ?.content
                    if (!content.isNullOrBlank()) {
                        sb.append(content)
                        onToken(content)
                    }
                } catch (_: Exception) {
                    // Skip malformed chunks (some servers send partial lines)
                }
            }
        }

        val result = sb.toString()
        if (result.isBlank()) {
            throw LlmException("LLM returned empty streaming response")
        }
        return result
    }

    fun close() {
        client.close()
    }
}

@Serializable
data class StreamChunk(
    val choices: List<StreamChoice>,
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta,
)

@Serializable
data class StreamDelta(
    val role: String? = null,
    val content: String? = null,
)

class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
