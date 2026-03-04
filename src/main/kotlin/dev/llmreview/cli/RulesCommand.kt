package dev.llmreview.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.int
import java.io.File

class RulesCommand : CliktCommand(
    name = "rules",
) {
    override fun help(context: Context) = "Manage review rules"
    init {
        subcommands(
            RulesAddCommand(),
            RulesRemoveCommand(),
            RulesListCommand(),
        )
    }

    override fun run() = Unit
}

private fun rulesFile(): File = File(".llmreview/rules.txt")

private fun ensureRulesExist(): File {
    val file = rulesFile()
    if (!file.exists()) {
        throw RuntimeException("rules.txt not found. Run 'llmreview init' first.")
    }
    return file
}

/**
 * Load rules as non-empty, non-comment lines with their original line numbers.
 */
private fun loadRules(file: File): List<Pair<Int, String>> {
    return file.readLines().mapIndexedNotNull { index, line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) null
        else (index + 1) to trimmed
    }
}

class RulesAddCommand : CliktCommand(
    name = "add",
) {
    override fun help(context: Context) = "Add a new rule"
    private val ruleText by argument(help = "The rule text to add")

    override fun run() {
        val file = ensureRulesExist()
        file.appendText("$ruleText\n")
        echo("✓ Rule added: $ruleText")
    }
}

class RulesRemoveCommand : CliktCommand(
    name = "remove",
) {
    override fun help(context: Context) = "Remove a rule by line number"
    private val lineNumber by argument(help = "Line number to remove").int()

    override fun run() {
        val file = ensureRulesExist()
        val lines = file.readLines().toMutableList()

        if (lineNumber < 1 || lineNumber > lines.size) {
            echo("✗ Invalid line number: $lineNumber (file has ${lines.size} lines)", err = true)
            throw RuntimeException("Invalid line number")
        }

        val removed = lines.removeAt(lineNumber - 1)
        file.writeText(lines.joinToString("\n") + "\n")
        echo("✓ Removed line $lineNumber: $removed")
    }
}

class RulesListCommand : CliktCommand(
    name = "list",
) {
    override fun help(context: Context) = "List all rules with line numbers"
    override fun run() {
        val file = ensureRulesExist()
        val rules = loadRules(file)

        if (rules.isEmpty()) {
            echo("No rules defined yet. Use 'llmreview rules add' to add rules.")
            return
        }

        echo("Rules (${rules.size}):")
        rules.forEach { (lineNum, text) ->
            echo("  $lineNum: $text")
        }
    }
}
