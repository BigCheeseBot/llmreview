package dev.llmreview.llm

import dev.llmreview.model.DiffReview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonParserTest {

    @Test
    fun `stripFences removes json markdown fence`() {
        val input = """
            ```json
            {"summary": "test", "findings": []}
            ```
        """.trimIndent()

        val result = JsonParser.stripFences(input)
        assertTrue(result.startsWith("{"))
        assertTrue(result.endsWith("}"))
    }

    @Test
    fun `stripFences removes plain markdown fence`() {
        val input = """
            ```
            {"summary": "test", "findings": []}
            ```
        """.trimIndent()

        val result = JsonParser.stripFences(input)
        assertTrue(result.startsWith("{"))
    }

    @Test
    fun `stripFences passes through plain JSON`() {
        val input = """{"summary": "test", "findings": []}"""
        val result = JsonParser.stripFences(input)
        assertEquals(input, result)
    }

    @Test
    fun `parse handles fenced DiffReview`() {
        val input = """
            ```json
            {
              "schema_version": "1.0",
              "summary": "Looks good",
              "findings": [
                {
                  "severity": "info",
                  "message": "Nice code"
                }
              ]
            }
            ```
        """.trimIndent()

        val review: DiffReview = JsonParser.parse(input)
        assertEquals("Looks good", review.summary)
        assertEquals(1, review.findings.size)
        assertEquals("Nice code", review.findings[0].message)
    }
}
