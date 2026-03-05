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
import java.nio.file.FileSystems
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
    private val perFile: Boolean = false,
    private val excludePatterns: List<String> = emptyList(),
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
        // If excludes are active, filter the diff to remove excluded file hunks
        val filteredDiff = if (excludePatterns.isNotEmpty()) {
            filterDiff(diff, excludePatterns)
        } else {
            diff
        }
        File(runDir, "diff.patch").writeText(filteredDiff)
        onDebug("[debug] Diff size: ${filteredDiff.length} chars, ${filteredDiff.lines().size} lines" +
            if (filteredDiff.length < diff.length) " (filtered from ${diff.length} chars)" else "")
        onDebug("[debug] HEAD: ${gitService.headSha}")
        onDebug("[debug] Dirty: ${gitService.isDirty}")

        // FR-2: Affected files
        val rawFileList = gitService.getAffectedFiles(baseRef, headRef, staged)

        // Apply exclude patterns
        val fileList = if (excludePatterns.isNotEmpty()) {
            val filtered = rawFileList.files.filter { file ->
                val excluded = matchesExclude(file.path, excludePatterns)
                if (excluded) onDebug("[debug] Excluded: ${file.path}")
                !excluded
            }
            DiffFileList(files = filtered)
        } else {
            rawFileList
        }

        File(runDir, "files_in_diff.json").writeText(json.encodeToString(DiffFileList.serializer(), fileList))
        onDebug("[debug] Affected files${if (excludePatterns.isNotEmpty()) " (after excludes)" else ""}:")
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
        val diffReview = if (perFile) {
            executePerFileReview(rules, fileList, baseRef, headRef, staged)
        } else {
            onProgress("Phase 1: Reviewing diff (${fileList.files.size} files affected)...")
            executeDiffReview(rules, filteredDiff)
        }
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

    private suspend fun executePerFileReview(
        rules: String,
        fileList: DiffFileList,
        baseRef: String?,
        headRef: String?,
        staged: Boolean,
    ): DiffReview {
        val reviewableFiles = fileList.files.filter {
            it.operation != FileOperation.DELETE && it.operation != FileOperation.BINARY
        }
        onProgress("Phase 1: Reviewing ${reviewableFiles.size} files individually (per-file mode)...")

        val allFindings = mutableListOf<Finding>()
        val fileSummaries = mutableListOf<String>()
        var reviewed = 0
        var failed = 0

        reviewableFiles.forEachIndexed { index, file ->
            onProgress("  [${index + 1}/${reviewableFiles.size}] Reviewing ${file.path}...")
            val fileDiff = gitService.generateSingleFileDiff(baseRef, headRef, staged, file.path)
            if (fileDiff.isBlank()) {
                onDebug("[debug] Skipping ${file.path} — empty diff")
                return@forEachIndexed
            }

            onDebug("[debug] ── Per-file: ${file.path} (${fileDiff.length} chars, ${fileDiff.lines().size} lines) ──")

            try {
                val systemPrompt = Prompts.phase1System(rules)
                val userPrompt = Prompts.perFileUser(file.path, fileDiff)
                onDebug("[debug] Prompt for ${file.path}: ~${(systemPrompt.length + userPrompt.length) / 4} tokens est.")
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
                onDebug("[debug] Response for ${file.path}: ${response.length} chars in ${duration.toString(DurationUnit.SECONDS, 1)}s")
                if (verbose) {
                    onDebug("[debug] ┌─ Raw Response ─")
                    response.lines().take(30).forEach { onDebug("[debug] │ $it") }
                    if (response.lines().size > 30) {
                        onDebug("[debug] │ ... (${response.lines().size - 30} more lines)")
                    }
                    onDebug("[debug] └─────────────")
                }

                val fileReview: DiffReview = JsonParser.parse(response)
                allFindings.addAll(fileReview.findings)
                fileSummaries.add("**${file.path}**: ${fileReview.summary}")
                reviewed++

                // Save per-file review
                val perFileDir = File(runDir, "per_file")
                perFileDir.mkdirs()
                val sanitized = file.path.replace("/", "_").replace("\\", "_")
                File(perFileDir, "$sanitized.json").writeText(
                    json.encodeToString(DiffReview.serializer(), fileReview)
                )
            } catch (e: Exception) {
                onProgress("  ⚠ Failed to review ${file.path}: ${e.message}")
                onDebug("[debug] Error for ${file.path}: ${e.stackTraceToString()}")
                failed++
            }
        }

        onProgress("  ✓ Reviewed $reviewed files ($failed failed)")

        // Build consolidated review
        val consolidatedSummary = buildString {
            appendLine("Per-file review of $reviewed files ($failed failed).")
            appendLine()
            fileSummaries.forEach { appendLine("- $it") }
        }

        val consolidatedMarkdown = buildString {
            appendLine("# Per-File Code Review")
            appendLine()
            appendLine("Reviewed **$reviewed** files individually" +
                if (failed > 0) " ($failed failed to parse)" else "")
            appendLine()
            if (fileSummaries.isNotEmpty()) {
                appendLine("## File Summaries")
                appendLine()
                fileSummaries.forEach { appendLine("- $it") }
                appendLine()
            }
            if (allFindings.isNotEmpty()) {
                appendLine("## All Findings")
                appendLine()
                val grouped = allFindings.groupBy { it.severity }
                listOf(Severity.ERROR, Severity.WARN, Severity.INFO).forEach { severity ->
                    val findings = grouped[severity] ?: return@forEach
                    val icon = when (severity) {
                        Severity.ERROR -> "🔴"
                        Severity.WARN -> "🟡"
                        Severity.INFO -> "🔵"
                    }
                    appendLine("### $icon ${severity.name} (${findings.size})")
                    appendLine()
                    findings.forEach { finding ->
                        append("- ")
                        if (finding.file != null) append("`${finding.file}`")
                        if (finding.lineHint != null) append(" L${finding.lineHint}")
                        if (finding.file != null) append(": ")
                        appendLine(finding.message)
                        if (finding.suggestion != null) {
                            appendLine("  → ${finding.suggestion}")
                        }
                    }
                    appendLine()
                }
            }
        }

        return DiffReview(
            summary = consolidatedSummary,
            findings = allFindings,
            markdown = consolidatedMarkdown,
        )
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

    /**
     * Check if a file path matches any exclude pattern.
     * Supports both simple globs and path globs with directory wildcards.
     */
    private fun matchesExclude(filePath: String, patterns: List<String>): Boolean {
        val path = java.nio.file.Path.of(filePath)
        val fileName = path.fileName?.toString() ?: return false

        return patterns.any { pattern ->
            matchesPattern(path, fileName, pattern)
        }
    }

    private fun matchesPattern(path: java.nio.file.Path, fileName: String, pattern: String): Boolean {
        // Match against full path
        val fullMatcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        if (fullMatcher.matches(path)) return true

        // Also match pattern against just the filename (e.g. "*.png" matches "dir/file.png")
        if (!pattern.contains("/")) {
            val nameMatcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            if (nameMatcher.matches(java.nio.file.Path.of(fileName))) return true

            // Try with ** prefix (e.g. "*.png" becomes "**/*.png")
            val recursiveMatcher = FileSystems.getDefault().getPathMatcher("glob:**/" + pattern)
            if (recursiveMatcher.matches(path)) return true
        }

        return false
    }

    /**
     * Filter a unified diff to remove hunks for files matching exclude patterns.
     */
    private fun filterDiff(diff: String, patterns: List<String>): String {
        val result = StringBuilder()
        var skip = false

        for (line in diff.lines()) {
            if (line.startsWith("diff --git ")) {
                val path = line.substringAfterLast(" b/")
                skip = matchesExclude(path, patterns)
            }
            if (!skip) {
                result.appendLine(line)
            }
        }

        return result.toString().trimEnd() + "\n"
    }
}
