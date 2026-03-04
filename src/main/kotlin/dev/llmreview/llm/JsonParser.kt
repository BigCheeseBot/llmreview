package dev.llmreview.llm

import kotlinx.serialization.json.Json

/**
 * Utilities for parsing JSON from LLM responses.
 * Handles common issues like markdown fences around JSON.
 */
object JsonParser {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Strip markdown code fences from LLM output before parsing.
     * Handles: ```json\n...\n```, ```\n...\n```, and plain JSON.
     */
    fun stripFences(raw: String): String {
        val trimmed = raw.trim()

        // Match ```json ... ``` or ``` ... ```
        val fenceRegex = Regex("""^```(?:json)?\s*\n?(.*?)\n?\s*```$""", RegexOption.DOT_MATCHES_ALL)
        val match = fenceRegex.find(trimmed)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        return trimmed
    }

    /**
     * Parse JSON from an LLM response, stripping fences first.
     */
    inline fun <reified T> parse(raw: String): T {
        val cleaned = stripFences(raw)
        return json.decodeFromString<T>(cleaned)
    }
}
