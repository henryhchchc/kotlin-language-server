package org.javacs.kt

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.javacs.kt.completion.*
import org.javacs.kt.definition.goToDefinition
import org.javacs.kt.diagnostic.convertDiagnostic
import org.javacs.kt.externalsources.JarClassContentProvider
import org.javacs.kt.formatting.formatKotlinCode
import org.javacs.kt.hover.hoverAt
import org.javacs.kt.position.offset
import org.javacs.kt.position.extractRange
import org.javacs.kt.position.position
import org.javacs.kt.references.findReferences
import org.javacs.kt.signaturehelp.fetchSignatureHelpAt
import org.javacs.kt.symbols.documentSymbols
import org.javacs.kt.util.noResult
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.Debouncer
import org.javacs.kt.util.filePath
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.parseURI
import org.javacs.kt.commands.JAVA_TO_KOTLIN_COMMAND
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import java.net.URI
import java.io.Closeable
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture

class KotlinTextDocumentService(
    private val sf: SourceFiles,
    private val sp: SourcePath,
    private val config: Configuration,
    private val tempDirectory: TemporaryDirectory,
    private val uriContentProvider: URIContentProvider
) : TextDocumentService, Closeable {
    private lateinit var client: LanguageClient
    private val async = AsyncExecutor()
    private var linting = false

    var debounceLint = Debouncer(Duration.ofMillis(config.linting.debounceTime))
    val lintTodo = mutableSetOf<URI>()
    var lintCount = 0

    fun connect(client: LanguageClient) {
        this.client = client
    }

    private val TextDocumentItem.filePath: Path?
        get() = parseURI(uri).filePath

    private val TextDocumentIdentifier.filePath: Path?
        get() = parseURI(uri).filePath

    private val TextDocumentIdentifier.isKotlinScript: Boolean
        get() = uri.endsWith(".kts")

    private val TextDocumentIdentifier.content: String
        get() = uriContentProvider.contentOf(parseURI(uri))

    private enum class Recompile {
        ALWAYS, AFTER_DOT, WAIT_FOR_LINT, NEVER
    }

    private fun recover(position: TextDocumentPositionParams, recompile: Recompile): Pair<CompiledFile, Int> {
        val uri = parseURI(position.textDocument.uri) // TODO
        val content = sp.content(uri)
        val offset = offset(content, position.position.line, position.position.character)
        val shouldRecompile = when (recompile) {
            Recompile.ALWAYS -> true
            Recompile.AFTER_DOT -> offset > 0 && content[offset - 1] == '.'
            Recompile.WAIT_FOR_LINT -> {
                debounceLint.waitForPendingTask()
                false
            }
            Recompile.NEVER -> false
        }
        val compiled = if (shouldRecompile) sp.currentVersion(uri) else sp.latestCompiledVersion(uri)
        return Pair(compiled, offset)
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> = async.compute {
        val start = params.range.start
        val end = params.range.end
        val hasSelection = (end.character - start.character) != 0 || (end.line - start.line) != 0
        if (hasSelection) {
            listOf(
                Either.forLeft<Command, CodeAction>(
                    Command("Convert Java to Kotlin", JAVA_TO_KOTLIN_COMMAND, listOf(
                        params.textDocument.uri,
                        params.range
                    ))
                )
            )
        } else {
            emptyList()
        }
    }

    override fun hover(position: TextDocumentPositionParams): CompletableFuture<Hover?> = async.compute {
        reportTime {
            LOG.info("Hovering at {} {}:{}", position.textDocument.uri, position.position.line, position.position.character)

            val (file, cursor) = recover(position, Recompile.NEVER)
            hoverAt(file, cursor) ?: noResult("No hover found at ${describePosition(position)}", null)
        }
    }

    override fun documentHighlight(position: TextDocumentPositionParams): CompletableFuture<List<DocumentHighlight>> {
        TODO("not implemented")
    }

    override fun onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture<List<TextEdit>> {
        TODO("not implemented")
    }

    override fun definition(position: TextDocumentPositionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> = async.compute {
        reportTime {
            LOG.info("Go-to-definition at {}", describePosition(position))

            val (file, cursor) = recover(position, Recompile.NEVER)
            goToDefinition(file, cursor, uriContentProvider.jarClassContentProvider, tempDirectory, config.externalSources)
                ?.let(::listOf)
                ?.let { Either.forLeft<List<Location>, List<LocationLink>>(it) }
                ?: noResult("Couldn't find definition at ${describePosition(position)}", Either.forLeft(emptyList()))
        }
    }

    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> = async.compute {
        val code = extractRange(params.textDocument.content, params.range)
        listOf(TextEdit(
            params.range,
            formatKotlinCode(
                code,
                isScript = params.textDocument.isKotlinScript,
                options = params.options
            )
        ))
    }

    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> {
        TODO("not implemented")
    }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> {
        TODO("not implemented")
    }

    override fun completion(position: CompletionParams) = async.compute {
        reportTime {
            LOG.info("Completing at {}", describePosition(position))

            val (file, cursor) = recover(position, Recompile.NEVER) // TODO: Investigate when to recompile
            val completions = completions(file, cursor, config.completion)
            LOG.info("Found {} items", completions.items.size)

            Either.forRight<List<CompletionItem>, CompletionList>(completions)
        }
    }

    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> {
        TODO("not implemented")
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> = async.compute {
        LOG.info("Find symbols in {}", params.textDocument.uri)

        reportTime {
            val uri = parseURI(params.textDocument.uri)
            val parsed = sp.parsedFile(uri)

            documentSymbols(parsed)
        }
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.open(uri, params.textDocument.text, params.textDocument.version)
        lintNow(uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // Lint after saving to prevent inconsistent diagnostics
        val uri = parseURI(params.textDocument.uri)
        lintNow(uri)
    }

    override fun signatureHelp(position: TextDocumentPositionParams): CompletableFuture<SignatureHelp?> = async.compute {
        reportTime {
            LOG.info("Signature help at {}", describePosition(position))

            val (file, cursor) = recover(position, Recompile.NEVER)
            fetchSignatureHelpAt(file, cursor) ?: noResult("No function call around ${describePosition(position)}", null)
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.close(uri)
        clearDiagnostics(uri)
    }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> = async.compute {
        val code = params.textDocument.content
        LOG.info("{}", params.textDocument.uri)
        listOf(TextEdit(
            Range(Position(0, 0), position(code, code.length)),
            formatKotlinCode(
                code,
                isScript = params.textDocument.isKotlinScript,
                options = params.options
            )
        ))
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.edit(uri, params.textDocument.version, params.contentChanges)
        lintLater(uri)
    }

    override fun references(position: ReferenceParams) = async.compute {
        position.textDocument.filePath
            ?.let { file ->
                val content = sp.content(parseURI(position.textDocument.uri))
                val offset = offset(content, position.position.line, position.position.character)
                findReferences(file, offset, sp)
            }
        }

    override fun resolveCodeLens(unresolved: CodeLens): CompletableFuture<CodeLens> {
        TODO("not implemented")
    }

    private fun describePosition(position: TextDocumentPositionParams): String {
        val path = position.textDocument.filePath
        return "${path?.fileName} ${position.position.line + 1}:${position.position.character + 1}"
    }

    public fun updateDebouncer() {
        debounceLint = Debouncer(Duration.ofMillis(config.linting.debounceTime))
    }

    private fun clearLint(): List<URI> {
        val result = lintTodo.toList()
        lintTodo.clear()
        return result
    }

    private fun lintLater(uri: URI) {
        lintTodo.add(uri)
        if (!linting) {
            debounceLint.submit(::doLint)
        }
    }

    private fun lintNow(file: URI) {
        lintTodo.add(file)
        debounceLint.submitImmediately(::doLint)
    }

    private fun doLint() {
        linting = true
        try {
            LOG.info("Linting {}", describeURIs(lintTodo))
            val files = clearLint()
            val context = sp.compileFiles(files)
            reportDiagnostics(files, context.diagnostics)
            lintCount++
        } finally {
            linting = false
        }
    }

    private fun reportDiagnostics(compiled: Collection<URI>, kotlinDiagnostics: Diagnostics) {
        val langServerDiagnostics = kotlinDiagnostics.flatMap(::convertDiagnostic)
        val byFile = langServerDiagnostics.groupBy({ it.first }, { it.second })

        for ((uri, diagnostics) in byFile) {
            if (sf.isOpen(uri)) {
                client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), diagnostics))

                LOG.info("Reported {} diagnostics in {}", diagnostics.size, uri)
            }
            else LOG.info("Ignore {} diagnostics in {} because it's not open", diagnostics.size, uri)
        }

        val noErrors = compiled - byFile.keys
        for (file in noErrors) {
            clearDiagnostics(file)

            LOG.info("No diagnostics in {}", file)
        }

        lintCount++
    }

    private fun clearDiagnostics(uri: URI) {
        client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), listOf()))
    }

    private fun shutdownExecutors(awaitTermination: Boolean) {
        async.shutdown(awaitTermination)
        debounceLint.shutdown(awaitTermination)
    }

    override fun close() {
        shutdownExecutors(awaitTermination = true)
    }
}

private inline fun<T> reportTime(block: () -> T): T {
    val started = System.currentTimeMillis()
    try {
        return block()
    } finally {
        val finished = System.currentTimeMillis()
        LOG.info("Finished in {} ms", finished - started)
    }
}
