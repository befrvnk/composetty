# Remote transport sample

This Kotlin Multiplatform sample contains a reusable `TerminalConnection` adapter and a
`LoopbackTerminalConnection` implementation. The loopback connection queues terminal input and
echoes it from a coroutine, rather than re-entering the terminal session from `TerminalTransport.write`.

`RemoteTerminal` is the Compose integration point. `LoopbackTerminalSample` is rendered by the
Android sample. Replace `LoopbackTerminalConnection` with an adapter around an SSH or WebSocket
client that provides ordered output as `Flow<ByteArray>` and queues input and resize requests.
The keyboard accessory is enabled for the Android sample and disabled by the JVM launcher, where a
physical keyboard provides those keys.

Run the interactive JVM desktop sample with:

```shell
devenv shell -- ./gradlew :samples:remote:run
```

## Ktor WebSocket adapter

`KtorWebSocketTerminalConnection` is a concrete adapter for a server that uses binary WebSocket
frames for terminal input and output. It accepts an application-owned `HttpClient`, so an app can
choose the Ktor engine appropriate to its targets. The terminal resize message format is specific to
the server; provide it through `encodeResize`. This sample compiles the adapter for Android and JVM;
add an appropriate Ktor engine and target configuration when adapting it to another platform.

Declare Ktor in the consuming application's `gradle/libs.versions.toml`:

```toml
[versions]
ktor = "3.5.2"

[libraries]
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-websockets = { module = "io.ktor:ktor-client-websockets", version.ref = "ktor" }
ktor-client-android = { module = "io.ktor:ktor-client-android", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
```

Add `libs.ktor.client.core` and `libs.ktor.client.websockets` to `commonMain`, then add one engine
to each platform source set. The sample uses the Android engine for `androidMain` and CIO for
`jvmMain`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        androidMain.dependencies { implementation(libs.ktor.client.android) }
        jvmMain.dependencies { implementation(libs.ktor.client.cio) }
    }
}
```

Create the client with the WebSockets plugin installed. Applications can configure timeouts,
authentication, logging, and the engine in this client:

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.websocket.WebSockets

val client = HttpClient(Android) {
    install(WebSockets) {
        maxFrameSize = 1L * 1024 * 1024
    }
}
```

The 1 MiB limit is an example; choose a limit that matches the service's maximum terminal-output
chunk and configure the server with the same bound.

### Production security

Use `wss://` in production. Configure authentication on the caller-owned `HttpClient`, for example
with an authorization header or a short-lived connection token appropriate to the server. Never send
long-lived credentials as terminal input or embed them in the WebSocket URL. The server must
authenticate the client, authorize access to the requested terminal session, and enforce per-session
resource limits before forwarding process input or output.

### Frame sizes and flow control

Keep terminal output frames reasonably sized rather than buffering an entire command result in one
WebSocket message. Configure Ktor's `WebSockets` plugin `maxFrameSize` to a limit appropriate to
your service, and enforce matching limits on the server. The connection adapter queues outbound
input so its callbacks return promptly; the server remains responsible for applying backpressure,
limiting per-session output, and disconnecting clients that exceed service limits.

```kotlin
import dev.befrvnk.composetty.sample.remote.KtorWebSocketTerminalConnection

val connection = KtorWebSocketTerminalConnection.connect(
    scope = scope,
    client = client,
    url = "wss://terminal.example.com/session/123",
    encodeResize = { size -> encodeResizeMessage(size) },
)
```

Close the connection when the terminal ends, then close `client` when no connection shares it.

### Protocol contract

`KtorWebSocketTerminalConnection` is intentionally narrow. Your server protocol must meet these
requirements:

- Send terminal process output to the client as ordered binary WebSocket frames. Each frame is fed
  unchanged to `TerminalSession.receive`.
- Accept binary frames from the client as terminal process input, in order. The adapter sends bytes
  produced by keyboard input, IME commits, paste, and terminal responses unchanged.
- Define a binary resize message. `encodeResize` is called for every `TerminalSize`; its returned
  bytes are sent as a binary frame in the same order as terminal input.
- Do not use WebSocket text frames for terminal data. The adapter ignores them.
- Treat a closed WebSocket as a disconnected terminal stream. Close the connection and create a new
  one after reconnecting; do not reuse the old `TerminalSession`.

Run its contract test with:

```shell
devenv shell -- ./gradlew :samples:remote:check
```
