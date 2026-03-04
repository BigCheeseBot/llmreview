package dev.llmreview.pipeline

import dev.llmreview.git.GitService
import dev.llmreview.llm.JsonParser
import dev.llmreview.llm.LlmClient
import dev.llmreview.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.time.measureTimedValue
import kotlin.time.DurationUnit

class ReviewPipeline(
    private val gitService: GitService,
    private val llmClient: LlmClient,
    private val runDir: File,
    private val runId: String,
    private val annotate: Boolean,
    private val maxContextBytes: Long?,
    private val verbose: Boolean = false,
    private val onProgress: (String) -> Unit = {},
    private val onDebug: (String) -> Unit = {},
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Execute the full review pipeline.
     */
    suspend fun execute(
        baseRef: String?,
        headRef: String?,
        staged: Boolean,
        rules: String,
        llmParams: LlmParams,
    ): File {
        runDir.mkdirs()

        // Save rules snapshot
        val rulesFile = File(runDir, "rules_used.txt")
        rulesFile.writeText(rules)

        // FR-1: Generate diff
        onProgress("Phase 0: Generating diff...")
        val diff = gitService.generateDiff(baseRef, headRef, staged)
        if (diff.isBlank()) {
            onProgress("No changes detected — nothing to review.")
            val reviewFile = File(runDir, "review.md")
            reviewFile.writeText("# Review\n\nNo changes detected.\n")
            return reviewFile
        }
        File(runDir, "diff.patch").writeText(diff)
        onDebug("[debug] Diff size: ${diff.length} chars, ${diff.lines().size} lines")
        onDebug("[debug] HEAD: ${gitService.headSha}")
        onDebug("[debug] Dirty: ${gitService.isDirty}")

        // FR-2: Affected files
        val fileList = gitService.getAffectedFiles(baseRef, headRef, staged)
        File(runDir, "files_in_diff.json").writeText(json.encodeToString(DiffFileList.serializer(), fileList))
        onDebug("[debug] Affected files:")
        fileList.files.forEach { onDebug("[debug]   ${it.operation.name.lowercase()} ${it.path}") }

        // FR-6: Manifest
        val manifest = RunManifest(
            runId = runId,
            timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            toolVersion = "0.1.0-SNAPSHOT",
            git = gitService.buildGitInfo(baseRef, headRef, staged),
            llm = llmParams,
        )
        File(runDir, "manifest.json").writeText(json.encodeToString(RunManifest.serializer(), manifest))

        // Phase 1: Diff Review
        onProgress("Phase 1: Reviewing diff (${fileList.files.size} files affected)...")
        val diffReview = executeDiffReview(rules, diff)
        File(runDir, "phase1_diff_review.json").writeText(json.encodeToString(DiffReview.serializer(), diffReview))

        // Phase 2: Annotations (opt-in)
        var annotations: Map<String, FileAnnotations>? = null
        if (annotate) {
            val filesToAnnotate = fileList.files.filter { it.operation != FileOperation.DELETE && it.operation != FileOperation.BINARY }
            onProgress("Phase 2: Annotating ${filesToAnnotate.size} files...")
            annotations = executeAnnotations(rules, filesToAnnotate)

            val annotationsDir = File(runDir, "phase2_file_annotations")
            annotationsDir.mkdirs()
            annotations.forEach { (path, annotation) ->
                val sanitized = path.replace("/", "_").replace("\\", "_")
                File(annotationsDir, "$sanitized.annotations.json")
                    .writeText(json.encodeToString(FileAnnotations.serializer(), annotation))
            }
        }

        // Phase 3: Final Report
        onProgress("Phase 3: Generating report...")
        val report = generateReport(diffReview, annotations)
        val reviewFile = File(runDir, "review.md")
        reviewFile.writeText(report)
        onDebug("[debug] Report: ${report.length} chars, ${report.lines().size} lines")
        onDebug("[debug] Findings: ${diffReview.findings.size} (${diffReview.findings.groupBy { it.severity }.map { "${it.value.size} ${it.key.name.lowercase()}" }.joinToString(", ")})")
        onDebug("[debug] Run artifacts saved to: $runDir")

        onProgress("Done! Report: ${reviewFile.path}")
        return reviewFile
    }

    private suspend fun executeDiffReview(rules: String, diff: String): DiffReview {
        val systemPrompt = Prompts.phase1System(rules)
        val userPrompt = Prompts.phase1User(diff)
        onDebug("[debug] ── Phase 1 Prompt ──")
        onDebug("[debug] System prompt: ${systemPrompt.length} chars")
        onDebug("[debug] User prompt: ${userPrompt.length} chars (~${(systemPrompt.length + userPrompt.length) / 4} tokens est.)")
        if (verbose) {
            onDebug("[debug] ┌─ System Prompt ─")
            systemPrompt.lines().forEach { onDebug("[debug] │ $it") }
            onDebug("[debug] ├─ User Prompt ─")
            userPrompt.lines().take(50).forEach { onDebug("[debug] │ $it") }
            if (userPrompt.lines().size > 50) {
                onDebug("[debug] │ ... (${userPrompt.lines().size - 50} more lines)")
            }
            onDebug("[debug] └─────────────")
        }

        val (response, duration) = measureTimedValue {
            llmClient.chatCompletion(systemPrompt, userPrompt)
        }
        onDebug("[debug] Phase 1 response: ${response.length} chars in ${duration.toString(DurationUnit.SECONDS, 1)}s")
        if (verbose) {
            onDebug("[debug] ┌─ Raw LLM Response ─")
            response.lines().take(30).forEach { onDebug("[debug] │ $it") }
            if (response.lines().size > 30) {
                onDebug("[debug] │ ... (${response.lines().size - 30} more lines)")
            }
            onDebug("[debug] └─────────────")
        }

        return JsonParser.parse(response)
    }

    private suspend fun executeAnnotations(
        rules: String,
        files: List<DiffFile>,
    ): Map<String, FileAnnotations> = coroutineScope {
        files.mapNotNull { file ->
            val content = gitService.readFileContent(file.path) ?: return@mapNotNull null

            if (maxContextBytes != null && content.toByteArray().size > maxContextBytes) {
                onProgress("  ⚠ Skipping ${file.path} (exceeds max-context-bytes)")
                return@mapNotNull null
            }

            onDebug("[debug] ── Phase 2: ${file.path} (${content.lines().size} lines, ${content.length} chars) ──")

            async {
                val systemPrompt = Prompts.phase2System(rules)
                val userPrompt = Prompts.phase2User(file.path, content)
                onDebug("[debug] Phase 2 prompt for ${file.path}: ~${(systemPrompt.length + userPrompt.length) / 4} tokens est.")

                val (response, duration) = measureTimedValue {
                    llmClient.chatCompletion(systemPrompt, userPrompt)
                }
                onDebug("[debug] Phase 2 response for ${file.path}: ${response.length} chars in ${duration.toString(DurationUnit.SECONDS, 1)}s")

                val annotation: FileAnnotations = JsonParser.parse(response)
                file.path to annotation
            }
        }.awaitAll().toMap()
    }

    private fun generateReport(review: DiffReview, annotations: Map<String, FileAnnotations>?): String {
        val sb = StringBuilder()

        sb.appendLine("# Code Review")
        sb.appendLine()

        // Use pre-generated markdown if available, otherwise build from findings
        if (review.markdown != null) {
            sb.appendLine(review.markdown)
        } else {
            sb.appendLine("## Summary")
            sb.appendLine()
            sb.appendLine(review.summary)
            sb.appendLine()

            if (review.findings.isNotEmpty()) {
                sb.appendLine("## Findings")
                sb.appendLine()
                review.findings.forEach { finding ->
                    val icon = when (finding.severity) {
                        Severity.ERROR -> "🔴"
                        Severity.WARN -> "🟡"
                        Severity.INFO -> "🔵"
                    }
                    sb.appendLine("### $icon ${finding.severity.name}: ${finding.message}")
                    if (finding.file != null) {
                        sb.append("**File:** `${finding.file}`")
                        if (finding.lineHint != null) sb.append(" (${finding.lineHint})")
                        sb.appendLine()
                    }
                    if (finding.hunkContext != null) {
                        sb.appendLine("```")
                        sb.appendLine(finding.hunkContext)
                        sb.appendLine("```")
                    }
                    if (finding.suggestion != null) {
                        sb.appendLine("**Suggestion:** ${finding.suggestion}")
                    }
                    sb.appendLine()
                }
            }
        }

        // Annotation summary (if Phase 2 was run)
        if (annotations != null && annotations.isNotEmpty()) {
            sb.appendLine("---")
            sb.appendLine()
            sb.appendLine("## Annotation Summary")
            sb.appendLine()
            sb.appendLine("${annotations.size} files annotated.")
            sb.appendLine()

            // Highlight lines with rule hints
            val ruleHits = annotations.flatMap { (_, ann) ->
                ann.lines.filter { it.ruleHints.isNotEmpty() }.map { line ->
                    "${ann.filePath}:${line.lineNumber}" to line.ruleHints
                }
            }
            if (ruleHits.isNotEmpty()) {
                sb.appendLine("### Rule-Relevant Lines")
                sb.appendLine()
                ruleHits.forEach { (location, rules) ->
                    sb.appendLine("- `$location`: ${rules.joinToString(", ")}")
                }
                sb.appendLine()
            }
        }

        return sb.toString()
    }
}
