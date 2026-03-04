package dev.llmreview.pipeline

/**
 * Prompt templates for each pipeline phase.
 */
object Prompts {

    fun phase1System(rules: String): String = """
You are an expert code reviewer. You will review a git diff and produce a structured JSON review.

## Rules
The following project-specific rules MUST be considered during the review:

$rules

## Output Format
Respond with ONLY valid JSON (no markdown fences, no explanation). Use this structure:
{
  "schema_version": "1.0",
  "summary": "Brief overall summary of the changes",
  "findings": [
    {
      "severity": "info|warn|error",
      "file": "path/to/file (if applicable)",
      "line_hint": "line number or range hint (if applicable)",
      "hunk_context": "relevant code context (if applicable)",
      "message": "Description of the finding",
      "suggestion": "Recommended fix or improvement (if applicable)"
    }
  ],
  "markdown": "Full review as markdown text suitable for a PR comment"
}

Focus on: bugs, edge cases, logic errors, style, naming, consistency, and the project rules above.
Be specific — reference files, hunks, and line numbers where possible.
    """.trimIndent()

    fun phase1User(diff: String): String = """
Review the following git diff:

```diff
$diff
```
    """.trimIndent()

    fun perFileUser(filePath: String, diff: String): String = """
Review the following changes to `$filePath`:

```diff
$diff
```
    """.trimIndent()

    fun phase2System(rules: String): String = """
You are a code analysis expert. You will annotate every line of a source file with structured metadata.

## Rules
The following project-specific rules MUST be considered:

$rules

## Output Format
Respond with ONLY valid JSON (no markdown fences, no explanation). Use this structure:
{
  "schema_version": "1.0",
  "file_path": "path/to/file",
  "language_guess": "kotlin|python|java|etc",
  "lines": [
    {
      "line_number": 1,
      "raw_text": "the actual line content",
      "categories": ["Import", "Declaration", "Assignment", "ControlFlow", "Branch", "FunctionDef", "Call", "Comment", "Docstring", "Test", "ErrorHandling", "Whitespace", "Other"],
      "metadata": { "key": "value pairs with relevant info like identifiers, dependencies, complexity hints" },
      "rule_hints": ["relevant rule texts from the rules above"],
      "confidence": 0.95
    }
  ]
}

Annotate EVERY line. For blank lines use category "Whitespace" with empty metadata.
    """.trimIndent()

    fun phase2User(filePath: String, content: String): String = """
Annotate every line of the following file:

**File:** `$filePath`

```
$content
```
    """.trimIndent()
}
