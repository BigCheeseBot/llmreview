package dev.llmreview.cli

import com.github.ajalt.clikt.core.CliktCommand
import dev.llmreview.model.ExitCode
import java.io.File

class InitCommand : CliktCommand(
    name = "init",
    help = "Initialize .llmreview/ directory with rules template",
) {
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

        // Auto-add .llmreview/runs/ to .gitignore
        val gitignore = File(".gitignore")
        val ignoreEntry = ".llmreview/runs/"
        if (gitignore.exists()) {
            val content = gitignore.readText()
            if (ignoreEntry !in content) {
                gitignore.appendText("\n$ignoreEntry\n")
                echo("📝 Added $ignoreEntry to .gitignore")
            }
        } else {
            gitignore.writeText("$ignoreEntry\n")
            echo("📝 Created .gitignore with $ignoreEntry")
        }

        echo("✓ Initialized .llmreview/")
        echo("  → Edit .llmreview/rules.txt to add your review rules")
    }
}
