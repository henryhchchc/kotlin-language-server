package org.javacs.kt

import org.javacs.kt.util.filePath
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

// TODO: Read exclusions from gitignore/settings.json/... instead of
// hardcoding them
class SourceExclusions(private val workspaceRoots: Collection<Path>) {
	private val excludedFolders = listOf(".git", "bin", "build", "target", "node_modules")

    constructor(workspaceRoot: Path) : this(listOf(workspaceRoot)) {}

    /** Finds all non-excluded files recursively. */
    fun walkIncluded(): Sequence<Path> = workspaceRoots.asSequence().flatMap { root ->
        root.toFile()
            .walk()
            .onEnter { isPathIncluded(it.toPath()) }
            .map { it.toPath() }
    }

    /** Tests whether the given URI is not excluded. */
    fun isURIIncluded(uri: URI) = uri.filePath?.let(this::isPathIncluded) ?: false

    /** Tests whether the given path is not excluded. */
    fun isPathIncluded(file: Path): Boolean = workspaceRoots.any { file.startsWith(it) }
        && excludedFolders.none {
            workspaceRoots
                .mapNotNull { if (file.startsWith(it)) it.relativize(file) else null }
                .flatMap { it } // Extract path segments
                .any { segment -> segment.toString() == it }
        }
}
