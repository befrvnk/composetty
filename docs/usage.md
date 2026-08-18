# Using Composetty

Composetty is a Compose Multiplatform terminal view backed by `libghostty-vt`. It separates terminal
emulation and rendering from process or network I/O:

```text
remote process or local shell -> session.receive(bytes) -> GhosttyTerminal
GhosttyTerminal -> TerminalSession -> TerminalTransport -> remote process or local shell
```

The library renders the terminal, translates keyboard and IME input, sizes the grid, manages
scrollback and selection, and encodes terminal input. Your app owns the remote connection or local
process lifetime.

## Contents

- [Add The Dependency](#add-the-dependency)
- [Compatibility](#compatibility)
- [Remote Terminal](#remote-terminal)
- [Local Shell On JVM Desktop](#local-shell-on-jvm-desktop)
- [API Responsibilities](#api-responsibilities)
- [Input, Clipboard, And Focus](#input-clipboard-and-focus)
- [Lifecycle Checklist](#lifecycle-checklist)
- [Troubleshooting](#troubleshooting)
- [Current Limitations](#current-limitations)
- [Samples And API Reference](#samples-and-api-reference)

## Add The Dependency

Composetty will be published as a Kotlin Multiplatform library. Declare it in
`gradle/libs.versions.toml`:

```toml
[versions]
composetty = "<version>"

[libraries]
composetty = { module = "dev.befrvnk.composetty:ghostty-compose", version.ref = "composetty" }
```

Then add it to `commonMain`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.composetty)
        }
    }
}
```

The published artifacts package their required native libraries. Applications do not need a local
Ghostty installation, Nix, Zig, or a C compiler.

## Compatibility

| Target | Supported environment | Local shell | Remote transport |
| --- | --- | --- | --- |
| Android | API 26+ on arm64-v8a and x86_64 | No | Yes |
| iOS | iOS 14+ on arm64 devices and arm64 simulators | No | Yes |
| JVM desktop | macOS and Linux on arm64 and x86-64 | Yes | Yes |

Windows is not supported. `LocalPtyTerminalSessionFactory` is JVM-only; use
`GhosttyTerminalSessionFactory` with a transport on every target.

## Remote Terminal

Use `GhosttyTerminalSessionFactory` when input and output come from SSH, a WebSocket, or another
consumer-owned connection. The following application-owned interface represents the small part of a
connection that Composetty needs. Adapt your network client's API to this shape, then implement
`TerminalTransport` as the outbound half:

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.weight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.befrvnk.composetty.GhosttyTerminal
import dev.befrvnk.composetty.GhosttyTerminalSessionFactory
import dev.befrvnk.composetty.TerminalKeyboardAccessory
import dev.befrvnk.composetty.TerminalRgb
import dev.befrvnk.composetty.TerminalSize
import dev.befrvnk.composetty.TerminalTheme
import dev.befrvnk.composetty.TerminalTransport
import kotlinx.coroutines.flow.Flow

interface TerminalConnection {
    val output: Flow<ByteArray>

    fun enqueueInput(bytes: ByteArray)

    fun enqueueResize(columns: Int, rows: Int, pixelWidth: Int, pixelHeight: Int)
}

class RemoteTransport(private val connection: TerminalConnection) : TerminalTransport {
    override fun write(bytes: ByteArray) {
        connection.enqueueInput(bytes)
    }

    override fun resize(size: TerminalSize) {
        connection.enqueueResize(
            columns = size.columns,
            rows = size.rows,
            pixelWidth = size.columns * size.cellWidth,
            pixelHeight = size.rows * size.cellHeight,
        )
    }
}
```

`TerminalConnection` is an example application interface, not a Composetty API. Its implementation
should queue outbound writes for a coroutine or I/O worker. Do not perform suspending or blocking
I/O directly in `write` or `resize`.

Create the session and connect the inbound output flow in a composable. Keep the session stable for
the lifetime of its transport, and always close it when it leaves composition:

```kotlin
@Composable
fun RemoteTerminal(connection: TerminalConnection) {
    val theme = remember {
        TerminalTheme(
            foreground = TerminalRgb(230, 230, 230),
            background = TerminalRgb(25, 25, 25),
            cursor = TerminalRgb(230, 230, 230),
        )
    }
    val transport = remember(connection) { RemoteTransport(connection) }
    val session = remember(transport) {
        GhosttyTerminalSessionFactory().create(theme, transport)
    }

    LaunchedEffect(session, connection) {
        connection.output.collect { bytes -> session.receive(bytes) }
    }
    DisposableEffect(session) {
        onDispose(session::close)
    }

    Column(Modifier.fillMaxSize()) {
        GhosttyTerminal(
            session = session,
            theme = theme,
            modifier = Modifier.weight(1f),
        )
        TerminalKeyboardAccessory(session)
    }
}
```

The transport callbacks run while the session serializes terminal state. They must return promptly
and must not synchronously call `TerminalSession` methods, including `receive`. Feed received bytes
from a separate connection callback or coroutine, as above. A session does not close its transport
or remote connection; close that resource separately when the connection ends.

## Local Shell On JVM Desktop

On supported JVM desktop hosts, `LocalPtyTerminalSessionFactory` starts the user's login shell in a
working directory and connects it to a Pty4J pseudo-terminal:

```kotlin
@Composable
fun LocalTerminal(workingDirectory: String) {
    val theme = remember { darkTerminalTheme() }
    val session = remember(workingDirectory) {
        LocalPtyTerminalSessionFactory().create(workingDirectory, theme)
    }
    DisposableEffect(session) {
        onDispose(session::close)
    }

    GhosttyTerminal(
        session = session,
        theme = theme,
        modifier = Modifier.fillMaxSize(),
    )
}

private fun darkTerminalTheme() = TerminalTheme(
    foreground = TerminalRgb(230, 230, 230),
    background = TerminalRgb(25, 25, 25),
    cursor = TerminalRgb(230, 230, 230),
)
```

The directory must exist. The factory uses the executable path in `SHELL` when available, otherwise
it chooses `/bin/zsh`, `/bin/bash`, or `/bin/sh`. Closing the session terminates this local process.

## API Responsibilities

`TerminalSession` is the primary imperative API:

| API | Use it for |
| --- | --- |
| `receive(bytes)` | Feed exact bytes received from the process or remote connection. |
| `sendText(text)` | Send ordinary typed text. |
| `sendKey(event)` | Send a physical or synthetic key such as Ctrl-C or an arrow key. |
| `paste(text)` | Send clipboard text. It applies control-byte filtering and bracketed-paste mode. |
| `resize(size)` | Change the terminal grid. `GhosttyTerminal` does this automatically. |
| `scroll(rows)` | Move through scrollback. Positive values move toward older output. |
| `select`, `selectedText`, `clearSelection` | Control the visible-cell selection. |
| `updateTheme(theme)` | Change default foreground, background, and cursor colors. |
| `close()` | Release terminal state; local PTY sessions also stop their shell. |

`GhosttyTerminal` observes `session.snapshot`, renders it, and automatically forwards desktop
keyboard events, IME committed text, pointer scrolling, touch scrolling, and long-press selection.
It also calculates a `TerminalSize` from its layout bounds and its `TerminalTextStyle`. Give it a
bounded size, typically `Modifier.fillMaxSize()` or `Modifier.weight(1f)`.

The composable calls `updateTheme` when its `theme` changes. Pass the same theme used to create the
session initially, then pass an updated value whenever your application changes themes. Customize
the terminal font with `TerminalTextStyle(fontFamily = ..., fontSize = ...)`.

## Input, Clipboard, And Focus

Use `sendText` only for normal typed input. Use `paste` for clipboard content; it honors the
terminal's current bracketed-paste mode and filters unsafe control bytes. `TerminalKeyboardAccessory`
is optional, but is useful on mobile because it provides navigation and modifier keys commonly
absent from software keyboards.

The terminal requests focus by default. Set `requestFocus = false` when another element should own
initial focus, then let the user tap the terminal to focus it. A long press and drag selects cells;
the completed selection is copied to the platform clipboard. Tapping clears an existing selection.

## Lifecycle Checklist

- Create one session per terminal connection or local shell, not per recomposition.
- Collect connection output and call `session.receive` in order.
- Queue work from `TerminalTransport.write` and `resize`; return immediately.
- Close the session with `DisposableEffect` or the owner lifecycle.
- Close remote connections independently, because a transport-neutral session does not own them.
- Use `paste`, not `sendText`, for clipboard data.

## Troubleshooting

### Native Library Does Not Load On JVM Desktop

The published JVM artifact contains native libraries for macOS and Linux on arm64 and x86-64. Check
that the application is running on one of those operating system and architecture combinations, and
that the `ghostty-compose` dependency is present in the runtime classpath. Windows is unsupported.

During native bridge development only, set `COMPOSETTY_GHOSTTY_LIBRARY` to the absolute path of a
compatible `libcomposetty-ghostty` library to override loading the bundled resource. Do not set it
in a packaged application.

### Android Fails On A Device Or Emulator

Composetty requires Android API 26 or later and packages `arm64-v8a` and `x86_64` JNI libraries.
Use an arm64 physical device or an x86_64 emulator. An `armeabi-v7a` device or emulator is not
supported.

### iOS Fails To Link

The published library supports arm64 iPhones and iPads, plus arm64 simulators, with iOS 14 or later.
An Intel simulator is not supported. Ensure that the consuming Kotlin Multiplatform project declares
only supported iOS targets and that its final binaries use an iOS 14 deployment target or newer.

### Input Or Output Stops Updating

Call `session.receive` for every received process-output chunk, in the order it arrives. Do not call
session methods synchronously from `TerminalTransport.write` or `resize`; those callbacks execute
while terminal state is locked. Instead, enqueue their work for the connection's I/O coroutine or
worker. Also ensure the session remains alive for the full connection lifetime and is not recreated
by routine recomposition.

## Current Limitations

Composetty supports terminal output, ANSI and true-color rendering, scrollback, keyboard and IME
input, selection, clipboard copy and paste, and double-width graphemes. The following features are
not implemented yet:

- Terminal hyperlinks
- Kitty graphics protocol images
- Marked-text rendering during IME composition
- Selection handles and selection autoscroll

## Samples And API Reference

The [Android sample](../samples/android) is a complete interactive Compose example using an echo
transport. The [iOS sample](../samples/ios) embeds the same terminal experience in a SwiftUI
application. Replace their echo transports with the remote adapter used by your app.

Every published artifact includes generated Dokka API documentation. Open the `*-javadoc.jar` from
your dependency cache or Maven repository to browse the full public API and its KDoc in a browser.
