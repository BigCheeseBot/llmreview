package dev.llmreview.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Run Manifest ---

@Serializable
data class RunManifest(
    @SerialName("schema_version") val schemaVersion: String = "1.0",
    @SerialName("run_id") val runId: String,
    val timestamp: String,
    @SerialName("tool_version") val toolVersion: String,
    val git: GitInfo,
    val llm: LlmParams,
)

@Serializable
data class GitInfo(
    @SerialName("head_sha") val headSha: String,
    @SerialName("is_dirty") val isDirty: Boolean,
    @SerialName("base_ref") val baseRef: String? = null,
    @SerialName("head_ref") val headRef: String? = null,
    @SerialName("base_sha") val baseSha: String? = null,
    @SerialName("merge_base_sha") val mergeBaseSha: String? = null,
    @SerialName("diff_mode") val diffMode: String, // "unstaged", "staged", "merge-base"
)

@Serializable
data class LlmParams(
    val model: String,
    val endpoint: String,
    val temperature: Double,
)

// --- Files in Diff ---

@Serializable
data class DiffFileList(
    @SerialName("schema_version") val schemaVersion: String = "1.0",
    val files: List<DiffFile>,
)

@Serializable
data class DiffFile(
    val path: String,
    val operation: FileOperation,
    @SerialName("old_path") val oldPath: String? = null, // for renames
)

@Serializable
enum class FileOperation {
    @SerialName("add") ADD,
    @SerialName("modify") MODIFY,
    @SerialName("delete") DELETE,
    @SerialName("rename") RENAME,
    @SerialName("binary") BINARY,
}

// --- Phase 1: Diff Review ---

@Serializable
data class DiffReview(
    @SerialName("schema_version") val schemaVersion: String = "1.0",
    val summary: String,
    val findings: List<Finding>,
    val markdown: String? = null,
)

@Serializable
data class Finding(
    val severity: Severity,
    val file: String? = null,
    @SerialName("line_hint") val lineHint: String? = null,
    @SerialName("hunk_context") val hunkContext: String? = null,
    val message: String,
    val suggestion: String? = null,
)

@Serializable
enum class Severity {
    @SerialName("info") INFO,
    @SerialName("warn") WARN,
    @SerialName("error") ERROR,
}

// --- Phase 2: File Annotations ---

@Serializable
data class FileAnnotations(
    @SerialName("schema_version") val schemaVersion: String = "1.0",
    @SerialName("file_path") val filePath: String,
    @SerialName("language_guess") val languageGuess: String? = null,
    val lines: List<LineAnnotation>,
)

@Serializable
data class LineAnnotation(
    @SerialName("line_number") val lineNumber: Int,
    @SerialName("raw_text") val rawText: String,
    val categories: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    @SerialName("rule_hints") val ruleHints: List<String> = emptyList(),
    val confidence: Double? = null,
)
