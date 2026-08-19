# Remote transport sample

This Kotlin Multiplatform sample contains a reusable `TerminalConnection` adapter and a
`LoopbackTerminalConnection` implementation. The loopback connection queues terminal input and
echoes it from a coroutine, rather than re-entering the terminal session from `TerminalTransport.write`.

`RemoteTerminal` is the Compose integration point. `LoopbackTerminalSample` is rendered by the
Android sample. Replace `LoopbackTerminalConnection` with an adapter around an SSH or WebSocket
client that provides ordered output as `Flow<ByteArray>` and queues input and resize requests.

## Ktor WebSocket adapter

`KtorWebSocketTerminalConnection` is a concrete adapter for a server that uses binary WebSocket
frames for terminal input and output. It accepts an application-owned `HttpClient`, so an app can
choose the Ktor engine appropriate to its targets. The terminal resize message format is specific to
the server; provide it through `encodeResize`.

```kotlin
val connection = KtorWebSocketTerminalConnection.connect(
    scope = scope,
    client = client,
    url = "wss://terminal.example.com/session/123",
    encodeResize = { size -> encodeResizeMessage(size) },
)
```

Close the connection when the terminal ends, then close `client` when no connection shares it.

Run its contract test with:

```shell
devenv shell -- ./gradlew :samples:remote:check
```
