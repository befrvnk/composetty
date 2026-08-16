package dev.befrvnk.composetty

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.StateFlow

/** Creates desktop local-shell sessions backed by libghostty-vt and Pty4J. */
public class LocalPtyTerminalSessionFactory {
    private val native: GhosttyNativeLibrary by lazy { loadGhosttyNativeLibrary().getOrThrow() }

    /** Starts the user's default shell with [workingDirectory] as its current directory. */
    public fun create(workingDirectory: String, initialTheme: TerminalTheme): TerminalSession =
        LocalPtyTerminalSession(native, workingDirectory, initialTheme)
}

internal class LocalPtyTerminalSession(
    native: GhosttyNativeLibrary,
    workingDirectory: String,
    initialTheme: TerminalTheme,
) : TerminalSession {
    private val process = createGhosttyPtyProcess(workingDirectory)
    private val closed = AtomicBoolean(false)
    private val transport =
        object : TerminalTransport {
            override fun write(bytes: ByteArray) {
                writeProcessInput(bytes)
            }

            override fun resize(size: TerminalSize) {
                if (!closed.get()) process.setWinSize(WinSize(size.columns, size.rows))
            }
        }
    private val delegate =
        runCatching { GhosttyTerminalSession(native, initialTheme, transport) }
            .getOrElse { error ->
                closed.set(true)
                process.destroyForcibly()
                throw error
            }
    private val reader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "composetty-ghostty-reader").apply { isDaemon = true }
    }
    override val snapshot: StateFlow<TerminalSnapshot> = delegate.snapshot

    init {
        reader.execute(::readProcessOutput)
    }

    override fun receive(bytes: ByteArray) {
        delegate.receive(bytes)
    }

    override fun sendKey(event: TerminalKeyEvent) {
        delegate.sendKey(event)
    }

    override fun sendText(text: String) {
        delegate.sendText(text)
    }

    override fun paste(text: String) {
        delegate.paste(text)
    }

    override fun select(start: TerminalCellPosition, end: TerminalCellPosition) {
        delegate.select(start, end)
    }

    override fun clearSelection() {
        delegate.clearSelection()
    }

    override fun selectedText(): String? = delegate.selectedText()

    override fun resize(size: TerminalSize) {
        delegate.resize(size)
    }

    override fun scroll(rows: Int) {
        delegate.scroll(rows)
    }

    override fun updateTheme(theme: TerminalTheme) {
        delegate.updateTheme(theme)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            process.destroy()
            if (!process.waitFor(ProcessShutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } finally {
            reader.shutdownNow()
            delegate.close()
        }
    }

    private fun readProcessOutput() {
        val buffer = ByteArray(ProcessReadBufferBytes)
        try {
            while (!closed.get()) {
                val count = process.inputStream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                delegate.receive(buffer.copyOf(count))
            }
        } catch (_: Exception) {
            // Closing the PTY interrupts its blocking read. Keep the last rendered frame.
        }
    }

    private fun writeProcessInput(bytes: ByteArray) {
        if (bytes.isEmpty() || closed.get() || !process.isAlive) return
        process.outputStream.write(bytes)
        process.outputStream.flush()
    }
}

internal actual fun loadGhosttyNativeLibrary(): Result<GhosttyNativeLibrary> = runCatching {
    val configuredPath = System.getenv(GhosttyLibraryEnvironment)?.let(Path::of)
    val path = configuredPath ?: extractBundledLibrary()
    JvmGhosttyNativeLibrary.load(path.toString())
}

private fun extractBundledLibrary(): Path {
    val resourcePath = "/native/ghostty/${platform.directory}/${platform.libraryFileName}"
    val input =
        LocalPtyTerminalSessionFactory::class.java.getResourceAsStream(resourcePath)
            ?: error("Bundled Ghostty library not found for ${platform.directory}")
    val directory = Files.createTempDirectory("composetty-ghostty-")
    val library = directory.resolve(platform.libraryFileName)
    input.use { stream -> Files.copy(stream, library, StandardCopyOption.REPLACE_EXISTING) }
    library.toFile().deleteOnExit()
    directory.toFile().deleteOnExit()
    return library
}

private val platform: NativePlatform
    get() {
        val architecture = architectureName()
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("mac") ->
                NativePlatform("macos-$architecture", "libcomposetty-ghostty.dylib")
            os.contains("linux") ->
                NativePlatform("linux-$architecture", "libcomposetty-ghostty.so")
            else -> error("Composetty supports macOS and Linux only")
        }
    }

private fun architectureName(): String =
    when (System.getProperty("os.arch", "").lowercase()) {
        "aarch64",
        "arm64" -> "arm64"
        "x86_64",
        "amd64" -> "x86_64"
        else -> error("Unsupported native architecture")
    }

private data class NativePlatform(val directory: String, val libraryFileName: String)

private fun createGhosttyPtyProcess(workingDirectory: String): PtyProcess {
    require(Files.isDirectory(Path.of(workingDirectory)))
    return PtyProcessBuilder()
        .setCommand(terminalCommand().toTypedArray())
        .setEnvironment(terminalEnvironment())
        .setDirectory(workingDirectory)
        .setConsole(false)
        .setRedirectErrorStream(true)
        .setInitialColumns(InitialTerminalColumns)
        .setInitialRows(InitialTerminalRows)
        .setUseWinConPty(false)
        .start()
}

private fun terminalCommand(): List<String> {
    val configuredShell = System.getenv("SHELL")?.takeIf(String::isNotBlank)
    val shell =
        configuredShell?.takeIf { candidate -> Files.isExecutable(Path.of(candidate)) }
            ?: listOf("/bin/zsh", "/bin/bash", "/bin/sh").first { candidate ->
                Files.isExecutable(Path.of(candidate))
            }
    return listOf(shell, "-l")
}

private fun terminalEnvironment(): Map<String, String> =
    System.getenv()
        .filterKeys { key -> key != "PWD" && key != "OLDPWD" }
        .plus(
            mapOf(
                "TERM" to "xterm-256color",
                "COLORTERM" to "truecolor",
                "TERM_PROGRAM" to "Composetty",
            )
        )

private const val GhosttyLibraryEnvironment = "COMPOSETTY_GHOSTTY_LIBRARY"
private const val InitialTerminalColumns = 80
private const val InitialTerminalRows = 24
private const val ProcessReadBufferBytes = 16 * 1024
private const val ProcessShutdownTimeoutSeconds = 2L
