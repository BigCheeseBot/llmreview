package dev.llmreview.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import java.io.File

class InitCommand : CliktCommand(
    name = "init",
) {
    override fun help(context: Context) = "Initialize .llmreview/ directory with rules template"
    override fun run() {
        val dir = File(".llmreview")

        if (dir.exists()) {
            echo("✓ .llmreview/ already exists")
            return
        }

        dir.mkdirs()
        File(dir, "runs").mkdirs()

        val rulesFile = File(dir, "rules.txt")
        rulesFile.writeText(
            """
            # llmreview rules — one rule per line
            # Lines starting with # are comments and will be ignored.
            #
            # Examples (uncomment to activate):
            # All public functions must have KDoc comments.
            # Avoid using !! (non-null assertion) in Kotlin.
            # Error messages must be user-friendly and actionable.
            """.trimIndent() + "\n"
        )

        ensureGitignore(
            listOf(
                ".llmreview/runs/",
                ".llmreview/latest",
            )
        )

        echo("✓ Initialized .llmreview/")
        echo("  → Edit .llmreview/rules.txt to add your review rules")
    }

    private fun ensureGitignore(entries: List<String>) {
        val gitignore = File(".gitignore")
        val existingLines = if (gitignore.exists()) {
            gitignore.readLines().map { it.trim() }.toSet()
        } else {
            emptySet()
        }

        val missing = entries.filter { it !in existingLines }

        if (missing.isEmpty()) {
            return
        }

        if (gitignore.exists()) {
            val content = gitignore.readText()
            val needsNewline = content.isNotEmpty() && !content.endsWith("\n")
            val prefix = if (needsNewline) "\n" else ""
            gitignore.appendText(prefix + missing.joinToString("\n") + "\n")
        } else {
            gitignore.writeText(missing.joinToString("\n") + "\n")
            echo("📝 Created .gitignore")
        }

        missing.forEach { echo("📝 Added $it to .gitignore") }
    }
}
