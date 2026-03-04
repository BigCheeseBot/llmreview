package dev.llmreview.git

import dev.llmreview.model.DiffFile
import dev.llmreview.model.DiffFileList
import dev.llmreview.model.FileOperation
import dev.llmreview.model.GitInfo
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import java.io.ByteArrayOutputStream
import java.io.File

class GitService(workDir: File) {

    val repository: Repository = FileRepositoryBuilder()
        .readEnvironment()
        .findGitDir(workDir)
        .setMustExist(true)
        .build()

    private val git = Git(repository)

    val headSha: String
        get() = repository.resolve("HEAD")?.name ?: "unknown"

    val isDirty: Boolean
        get() = git.status().call().let {
            it.hasUncommittedChanges() || it.untracked.isNotEmpty()
        }

    /**
     * Generate a unified diff string based on the given mode.
     */
    fun generateDiff(baseRef: String?, headRef: String?, staged: Boolean): String {
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { formatter ->
            formatter.setRepository(repository)
            formatter.isDetectRenames = true

            val entries = when {
                baseRef != null && headRef != null -> diffMergeBase(formatter, baseRef, headRef)
                staged -> diffStaged(formatter)
                else -> diffUnstaged(formatter)
            }

            entries.forEach { formatter.format(it) }
        }
        return out.toString(Charsets.UTF_8)
    }

    /**
     * Generate a unified diff for a single file.
     */
    fun generateSingleFileDiff(baseRef: String?, headRef: String?, staged: Boolean, filePath: String): String {
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { formatter ->
            formatter.setRepository(repository)
            formatter.isDetectRenames = true
            formatter.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(filePath))

            val entries = when {
                baseRef != null && headRef != null -> diffMergeBase(formatter, baseRef, headRef)
                staged -> diffStaged(formatter)
                else -> diffUnstaged(formatter)
            }

            entries.forEach { formatter.format(it) }
        }
        return out.toString(Charsets.UTF_8)
    }

    /**
     * Extract the list of affected files from a diff.
     */
    fun getAffectedFiles(baseRef: String?, headRef: String?, staged: Boolean): DiffFileList {
        DiffFormatter(ByteArrayOutputStream()).use { formatter ->
            formatter.setRepository(repository)
            formatter.isDetectRenames = true

            val entries = when {
                baseRef != null && headRef != null -> diffMergeBase(formatter, baseRef, headRef)
                staged -> diffStaged(formatter)
                else -> diffUnstaged(formatter)
            }

            val files = entries.map { entry ->
                DiffFile(
                    path = entry.newPath.takeIf { it != "/dev/null" } ?: entry.oldPath,
                    operation = mapOperation(entry),
                    oldPath = if (entry.changeType == DiffEntry.ChangeType.RENAME) entry.oldPath else null,
                )
            }

            return DiffFileList(files = files)
        }
    }

    /**
     * Read a file's content from the working tree.
     */
    fun readFileContent(relativePath: String): String? {
        val file = File(repository.workTree, relativePath)
        return if (file.exists() && file.isFile) file.readText() else null
    }

    /**
     * Build GitInfo for the manifest.
     */
    fun buildGitInfo(baseRef: String?, headRef: String?, staged: Boolean): GitInfo {
        val diffMode = when {
            baseRef != null && headRef != null -> "merge-base"
            staged -> "staged"
            else -> "unstaged"
        }

        var baseSha: String? = null
        if (baseRef != null && headRef != null) {
            baseSha = repository.resolve(baseRef)?.name
        }

        return GitInfo(
            headSha = headSha,
            isDirty = isDirty,
            baseRef = baseRef,
            headRef = headRef,
            baseSha = baseSha,
            mergeBaseSha = null,
            diffMode = diffMode,
        )
    }

    fun close() {
        git.close()
        repository.close()
    }

    // --- Private helpers ---

    private fun diffUnstaged(formatter: DiffFormatter): List<DiffEntry> {
        val workTreeIterator = FileTreeIterator(repository)
        return formatter.scan(prepareTreeParser(repository.resolve("HEAD")), workTreeIterator)
    }

    private fun diffStaged(formatter: DiffFormatter): List<DiffEntry> {
        val headTree = prepareTreeParser(repository.resolve("HEAD"))
        val indexIterator = org.eclipse.jgit.dircache.DirCacheIterator(repository.readDirCache())
        return formatter.scan(headTree, indexIterator)
    }

    private fun diffMergeBase(formatter: DiffFormatter, baseRef: String, headRef: String): List<DiffEntry> {
        val baseId = repository.resolve(baseRef)
            ?: throw IllegalArgumentException("Cannot resolve ref: $baseRef")
        val headId = repository.resolve(headRef)
            ?: throw IllegalArgumentException("Cannot resolve ref: $headRef")

        // Direct tree diff: base..head
        val baseTree = prepareTreeParser(baseId)
        val headTree = prepareTreeParser(headId)
        return formatter.scan(baseTree, headTree)
    }

    private fun prepareTreeParser(objectId: ObjectId?): AbstractTreeIterator {
        if (objectId == null) return CanonicalTreeParser().apply { reset() }

        RevWalk(repository).use { walk ->
            val commit = walk.parseCommit(objectId)
            val tree = walk.parseTree(commit.tree.id)
            val parser = CanonicalTreeParser()
            repository.newObjectReader().use { reader ->
                parser.reset(reader, tree.id)
            }
            return parser
        }
    }

    private fun mapOperation(entry: DiffEntry): FileOperation = when (entry.changeType) {
        DiffEntry.ChangeType.ADD -> FileOperation.ADD
        DiffEntry.ChangeType.MODIFY -> FileOperation.MODIFY
        DiffEntry.ChangeType.DELETE -> FileOperation.DELETE
        DiffEntry.ChangeType.RENAME -> FileOperation.RENAME
        DiffEntry.ChangeType.COPY -> FileOperation.ADD
    }
}
