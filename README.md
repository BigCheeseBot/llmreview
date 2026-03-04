# llmreview

LLM-powered local code review tool. Run reproducible, multi-phase code reviews from your terminal.

## Features

- **Local & private** — runs entirely on your machine, talks to any OpenAI-compatible API
- **Multi-phase pipeline** — diff review → file annotations → markdown report
- **Rule-driven** — define review rules as plain English sentences in your repo
- **Reproducible** — every run saves artifacts (diff, rules snapshot, manifest, JSON outputs)
- **Git-native** — supports unstaged, staged, and merge-base (PR-style) diffs

## Quick Start

```bash
# Initialize in your repo
llmreview init

# Add review rules
llmreview rules add "All public functions must have KDoc comments."
llmreview rules add "Avoid using !! (non-null assertion)."

# Review unstaged changes (against a local Ollama/LM Studio)
llmreview review --endpoint http://localhost:11434 --model llama3

# Review a PR-style diff
llmreview review --base main --head feature/my-branch

# Review staged changes with file annotations
llmreview review --staged --annotate

# Use a remote API
llmreview review --endpoint https://api.openai.com --model gpt-4 --env-file .env
```

## Commands

| Command | Description |
|---------|-------------|
| `llmreview init` | Initialize `.llmreview/` directory |
| `llmreview rules add "<text>"` | Add a review rule |
| `llmreview rules remove <line>` | Remove a rule by line number |
| `llmreview rules list` | List all rules |
| `llmreview review` | Run a review (see flags below) |

## Review Flags

| Flag | Description | Default |
|------|-------------|---------|
| `--base <ref>` | Base ref for PR-style diff | — |
| `--head <ref>` | Head ref for PR-style diff | — |
| `--staged` | Review staged changes | `false` |
| `--annotate` | Enable Phase 2 file annotations | `false` |
| `--model <name>` | LLM model identifier | `gpt-4` |
| `--endpoint <url>` | OpenAI-compatible API URL | `http://localhost:11434` |
| `--env-file <path>` | Load API key from .env file | — |
| `--temperature <float>` | LLM temperature | `0.1` |
| `--max-context-bytes <n>` | Max file size for annotations | unlimited |
| `--out <path>` | Custom output path for review.md | `.llmreview/latest/review.md` |
| `--run-id <id>` | Custom run ID | timestamp |
| `--verbose` / `-v` | Verbose output | `false` |
| `--quiet` / `-q` | Suppress progress | `false` |

## Pipeline Phases

1. **Diff Review** — Classic code review of the git diff, producing structured findings with severity levels
2. **File Annotation** *(opt-in)* — Line-by-line annotation of affected files with categories, metadata, and rule hints
3. **Report Generation** — Markdown report combining review findings and annotation summaries

## Run Artifacts

Each run creates `.llmreview/runs/<run-id>/` containing:

- `manifest.json` — run metadata (git state, LLM params, tool version)
- `rules_used.txt` — snapshot of rules at review time
- `diff.patch` — the exact diff reviewed
- `phase1_diff_review.json` — structured review findings
- `files_in_diff.json` — affected files list
- `phase2_file_annotations/` — per-file annotations (if `--annotate`)
- `review.md` — final markdown report

## Building

### JVM (development)

```bash
./gradlew build          # Build + test
./gradlew installDist    # Create runnable distribution
./build/install/llmreview/bin/llmreview --help
```

### Native Binary (recommended for distribution)

Produces a single ~45MB statically linked executable — no JVM required.

**Prerequisites:**

- [GraalVM CE 21+](https://github.com/graalvm/graalvm-ce-builds/releases) with `native-image`
- `musl-tools` (`sudo apt install musl-tools`)
- Static zlib built for musl:

```bash
curl -L https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz | tar xz
cd zlib-1.3.1
CC=musl-gcc ./configure --static --prefix=/usr/local/musl
make -j$(nproc) && sudo make install
sudo cp /usr/local/musl/lib/libz.a /usr/lib/x86_64-linux-musl/
```

**Build:**

```bash
GRAALVM_HOME=/path/to/graalvm JAVA_HOME=/path/to/graalvm ./gradlew nativeCompile
```

The binary is at `build/native/nativeCompile/llmreview`:

```bash
$ file build/native/nativeCompile/llmreview
ELF 64-bit LSB executable, x86-64, statically linked

$ ./build/native/nativeCompile/llmreview --help
```

### Fat JAR

```bash
./gradlew fatJar
java -jar build/libs/llmreview-0.1.0-SNAPSHOT-all.jar review
```

## License

MIT
