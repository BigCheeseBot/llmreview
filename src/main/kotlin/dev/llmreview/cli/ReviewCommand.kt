package dev.llmreview.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.long
import dev.llmreview.git.GitService
import dev.llmreview.llm.LlmClient
import dev.llmreview.llm.LlmException
import dev.llmreview.model.ExitCode
import dev.llmreview.model.LlmParams
import dev.llmreview.pipeline.ReviewPipeline
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

class ReviewCommand : CliktCommand(
    name = "review",
    help = "Run a code review on the current diff",
) {
    private val baseRef by option("--base", help = "Base ref for merge-base diff")
    private val headRef by option("--head", help = "Head ref for merge-base diff")
    private val staged by option("--staged", help = "Review staged changes").flag()
    private val annotate by option("--annotate", help = "Enable Phase 2 file annotations").flag()
    private val outPath by option("--out", help = "Output path for review.md")
    private val runId by option("--run-id", help = "Custom run ID (default: timestamp)")
    private val model by option("--model", help = "LLM model name").default("gpt-4")
    private val endpoint by option("--endpoint", help = "OpenAI-compatible API base URL").default("http://localhost:11434")
    private val envFile by option("--env-file", help = "Path to .env file for API key")
    private val temperature by option("--temperature", help = "LLM temperature (0.0-2.0)").double().default(0.1)
    private val maxContextBytes by option("--max-context-bytes", help = "Max file size in bytes for annotation").long()
    private val verbose by option("--verbose", "-v", help = "Verbose output").flag()
    private val quiet by option("--quiet", "-q", help = "Suppress progress output").flag()

    override fun run() {
        // Validate ref args
        if ((baseRef != null) != (headRef != null)) {
            echo("✗ Both --base and --head must be specified together", err = true)
            throw SystemExitException(ExitCode.CONFIG_ERROR.code)
        }

        // Load env file if specified
        val apiKey = loadApiKey()

        // Load rules
        val rulesFile = File(".llmreview/rules.txt")
        if (!rulesFile.exists()) {
            echo("✗ rules.txt not found. Run 'llmreview init' first.", err = true)
            throw SystemExitException(ExitCode.CONFIG_ERROR.code)
        }

        val rules = rulesFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("\n")

        if (rules.isEmpty()) {
            echo("✗ No rules defined. Add rules with 'llmreview rules add'.", err = true)
            throw SystemExitException(ExitCode.CONFIG_ERROR.code)
        }

        // Initialize services
        val gitService = try {
            GitService(File("."))
        } catch (e: Exception) {
            echo("✗ Not a git repository: ${e.message}", err = true)
            throw SystemExitException(ExitCode.GIT_ERROR.code)
        }

        val actualRunId = runId ?: DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val runDir = File(".llmreview/runs/$actualRunId")

        val llmClient = LlmClient(
            endpoint = endpoint,
            model = model,
            temperature = temperature,
            apiKey = apiKey,
        )

        val llmParams = LlmParams(
            model = model,
            endpoint = endpoint,
            temperature = temperature,
        )

        val pipeline = ReviewPipeline(
            gitService = gitService,
            llmClient = llmClient,
            runDir = runDir,
            runId = actualRunId,
            annotate = annotate,
            maxContextBytes = maxContextBytes,
            onProgress = { msg ->
                if (!quiet) echo(msg)
            },
        )

        try {
            val reviewFile = runBlocking {
                pipeline.execute(
                    baseRef = baseRef,
                    headRef = headRef,
                    staged = staged,
                    rules = rules,
                    llmParams = llmParams,
                )
            }

            // Copy to custom output path if specified
            if (outPath != null) {
                val outFile = File(outPath!!)
                outFile.parentFile?.mkdirs()
                reviewFile.copyTo(outFile, overwrite = true)
                if (!quiet) echo("Report copied to: $outPath")
            }

            // Update latest symlink
            updateLatestLink(runDir)

        } catch (e: LlmException) {
            echo("✗ LLM error: ${e.message}", err = true)
            throw SystemExitException(ExitCode.LLM_ERROR.code)
        } catch (e: SystemExitException) {
            throw e
        } catch (e: Exception) {
            echo("✗ Error: ${e.message}", err = true)
            if (verbose) e.printStackTrace()
            throw SystemExitException(ExitCode.IO_ERROR.code)
        } finally {
            llmClient.close()
            gitService.close()
        }
    }

    private fun loadApiKey(): String? {
        if (envFile != null) {
            val file = File(envFile!!)
            if (file.exists()) {
                val props = Properties()
                file.inputStream().use { props.load(it) }
                return props.getProperty("API_KEY")
                    ?: props.getProperty("OPENAI_API_KEY")
            }
        }

        // Fall back to environment
        return System.getenv("OPENAI_API_KEY")
            ?: System.getenv("API_KEY")
    }

    private fun updateLatestLink(runDir: File) {
        try {
            val latestLink = File(".llmreview/latest")
            if (latestLink.exists()) latestLink.delete()
            // Use relative symlink
            java.nio.file.Files.createSymbolicLink(
                latestLink.toPath(),
                File("runs/${runDir.name}").toPath(),
            )
        } catch (_: Exception) {
            // Symlinks may not work on all platforms — ignore
        }
    }
}

class SystemExitException(val exitCode: Int) : RuntimeException()
