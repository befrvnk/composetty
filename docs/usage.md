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
- [Theming And Text Styling](#theming-and-text-styling)
- [API Responsibilities](#api-responsibilities)
- [Custom Rendering](#custom-rendering)
- [Input, Clipboard, And Focus](#input-clipboard-and-focus)
- [Lifecycle Checklist](#lifecycle-checklist)
- [Replacing Connections And Tabs](#replacing-connections-and-tabs)
- [Disconnecting And Reconnecting](#disconnecting-and-reconnecting)
- [Handling Startup Failures](#handling-startup-failures)
- [Testing Your Integration](#testing-your-integration)
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

### iOS 14 Deployment Target

Kotlin/Native defaults new iOS binaries to iOS 15. If your application supports iOS 14, override
the final binary deployment target for each Composetty target:

```kotlin
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

kotlin {
    iosArm64()
    iosSimulatorArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        val konanProperty = when (name) {
            "iosArm64" -> "osVersionMin.ios_arm64"
            "iosSimulatorArm64" -> "osVersionMin.ios_simulator_arm64"
            else -> error("Unexpected iOS target: $name")
        }
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add(
                    "-Xoverride-konan-properties=$konanProperty=14.0"
                )
            }
        }
    }
}
```

## Remote Terminal

Use `GhosttyTerminalSessionFactory` when input and output come from SSH, a WebSocket, or another
consumer-owned connection. The tested [remote transport sample](../samples/remote) defines an
application-owned `TerminalConnection` boundary and `RemoteTransport` adapter. Adapt your network
client to that shape, then use its `RemoteTerminal` composable.

```kotlin
RemoteTerminal(connection = sshConnection)
```

`TerminalConnection` is an application interface, not a Composetty API. Its implementation should
queue outbound writes for a coroutine or I/O worker. Do not perform suspending or blocking I/O
directly in `write` or `resize`.

### Byte Array Ownership

`session.receive(bytes)` consumes its byte array synchronously and does not retain it, so a network
adapter may reuse its inbound buffer after the call returns. In contrast, a `TerminalTransport.write`
implementation may retain the array it receives. Queue that exact array only when the connection's
outbound writer owns it; otherwise copy it before returning from `write`.

The transport callbacks run while the session serializes terminal state. They must return promptly
and must not synchronously call `TerminalSession` methods, including `receive`. Feed received bytes
from a separate connection callback or coroutine, as above. A session does not close its transport
or remote connection; close that resource separately when the connection ends.

### Resizing A Remote PTY

A new session starts with an 80 by 24 grid. Once `GhosttyTerminal` has a bounded layout, it measures
its terminal cells and calls `session.resize` when the number of visible columns or rows changes.
The session updates its emulator and invokes `TerminalTransport.resize` with the new cell grid and
pixel dimensions. Forward `columns` and `rows` to the remote PTY; forward `pixelWidth` and
`pixelHeight` only when the remote protocol supports them.

Do not call `session.resize` from a parent layout when using `GhosttyTerminal`, because the
composable already owns resize measurement. Call it yourself only when rendering a `TerminalSession`
with a custom terminal view.

## Local Shell On JVM Desktop

On supported JVM desktop hosts, `LocalPtyTerminalSessionFactory` starts the user's login shell in a
working directory and connects it to a Pty4J pseudo-terminal. Because the factory is JVM-only, put
this code in your application's `jvmMain` source set:

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.befrvnk.composetty.GhosttyTerminal
import dev.befrvnk.composetty.LocalPtyTerminalSessionFactory
import dev.befrvnk.composetty.TerminalRgb
import dev.befrvnk.composetty.TerminalTheme

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

## Theming And Text Styling

Create `TerminalTheme` from the default foreground, background, and cursor colors for your app, and
pass the same value when creating the session and rendering `GhosttyTerminal`. When the value passed
to `GhosttyTerminal` changes, the composable updates the session's default colors. ANSI colors and
explicit true-color output still come from the process output.

```kotlin
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.befrvnk.composetty.TerminalTextStyle

GhosttyTerminal(
    session = session,
    theme = if (isDarkTheme) darkTheme else lightTheme,
    textStyle = TerminalTextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
    ),
    modifier = Modifier.fillMaxSize(),
)
```

`TerminalTextStyle` controls cell measurement as well as rendering. Use a monospace font with the
glyph coverage required by your terminal workload. Changing the font family or size recalculates
the cell dimensions and can trigger a remote PTY resize.

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

## Custom Rendering

`TerminalSession.snapshot` is a `StateFlow<TerminalSnapshot>`, so applications can render a
terminal with another UI toolkit or a specialized Compose presentation. A snapshot contains a
row-major `TerminalCells` grid, cursor state, and default colors. For a cell at `(column, row)`,
use the index `row * snapshot.columns + column`; skip `WideSpacerTail` and `WideSpacerHead` cells
when drawing double-width graphemes.

Custom renderers own layout measurement and must call `session.resize` with positive column, row,
cell-width, and cell-height values. They also own input, selection, scrolling, focus, and clipboard
behavior. Send committed text through `sendText`, clipboard content through `paste`, and mapped
physical keys through `sendKey`. Use `GhosttyTerminal` when its built-in Compose input and gesture
handling meets your application's needs.

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

## Replacing Connections And Tabs

The remote example keys its transport with `connection` and its session with `transport`. When the
connection instance changes, Compose cancels the old output collector, disposes and closes the old
session, and creates a session for the replacement connection. Keep those keys aligned with the
identity of the remote terminal, rather than with changing UI state such as a title or theme.

For terminal tabs, render one `RemoteTerminal` per live connection and use a stable terminal ID as
the item key. A tab that remains composed keeps its session and scrollback. Closing a tab should
remove its composable, close the associated remote connection in application code, and allow the
`DisposableEffect` to close the session.

## Disconnecting And Reconnecting

One `TerminalSession` represents one terminal stream. When a remote connection ends, stop feeding
its output, close the session, and update application state so its terminal composable leaves
composition. Calls made after `close` return without changing terminal or transport state.

Create a new transport and session after reconnecting, even when the remote host and terminal
settings are unchanged. Do not reuse a closed session or feed a new connection into an existing
session: its terminal state, including cursor modes and scrollback, belongs to the old stream.

## Handling Startup Failures

Creating a session loads the platform-native terminal library and can throw when the host is
unsupported, a native library is missing, or native initialization fails. Create the session at an
application boundary that can show a useful error state, rather than assuming terminal creation
always succeeds:

```kotlin
val sessionResult = runCatching {
    GhosttyTerminalSessionFactory().create(theme, transport)
}
```

When creation fails, retain the error for diagnostics, close the application-owned connection, and
offer an appropriate fallback or retry action. Do not retry in a composition loop; retry only after
the underlying environment or connection has changed. See [Troubleshooting](#troubleshooting) for
the supported platforms and native-loading checks.

## Testing Your Integration

Test the connection adapter separately from the network client. Use a recording `TerminalTransport`
to assert that terminal input, paste operations, and `TerminalSize` changes are forwarded to the
remote protocol. Feed representative output byte chunks into `session.receive` and assert against
`session.snapshot.value`, including ANSI formatting, Unicode graphemes, and resize behavior.

Keep the adapter test independent of an SSH server or WebSocket endpoint. Test the actual network
client separately for authentication, reconnects, flow control, and its own ordering guarantees.
The [Android sample](../samples/android) is useful for manual verification of software-keyboard
input, scrolling, selection, clipboard actions, and the keyboard accessory.

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

Browse the generated [API reference](https://befrvnk.github.io/composetty/) for the full public API
and its KDoc. Every published artifact also includes the same documentation in its `*-javadoc.jar`.
