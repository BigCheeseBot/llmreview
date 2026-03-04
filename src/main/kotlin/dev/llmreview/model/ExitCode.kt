package dev.llmreview.model

enum class ExitCode(val code: Int) {
    SUCCESS(0),
    CONFIG_ERROR(1),
    LLM_ERROR(2),
    GIT_ERROR(3),
    IO_ERROR(4),
}
