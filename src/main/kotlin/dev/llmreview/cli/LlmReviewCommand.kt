package dev.llmreview.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

class LlmReviewCommand : CliktCommand(
    name = "llmreview",
    help = "LLM-powered local code review tool",
) {
    init {
        subcommands(
            InitCommand(),
            RulesCommand(),
            ReviewCommand(),
        )
    }

    override fun run() = Unit
}
