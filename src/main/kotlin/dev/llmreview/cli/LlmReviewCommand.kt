package dev.llmreview.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

class LlmReviewCommand : CliktCommand(
    name = "llmreview",
) {
    override fun help(context: Context) = "LLM-powered local code review tool"
    init {
        subcommands(
            InitCommand(),
            RulesCommand(),
            ReviewCommand(),
        )
    }

    override fun run() = Unit
}
