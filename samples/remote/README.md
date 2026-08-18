# Remote transport sample

This Kotlin Multiplatform sample contains a reusable `TerminalConnection` adapter and a
`LoopbackTerminalConnection` implementation. The loopback connection queues terminal input and
echoes it from a coroutine, rather than re-entering the terminal session from `TerminalTransport.write`.

`RemoteTerminal` is the Compose integration point. Replace `LoopbackTerminalConnection` with an
adapter around an SSH or WebSocket client that provides ordered output as `Flow<ByteArray>` and queues
input and resize requests.

Run its contract test with:

```shell
devenv shell -- ./gradlew :samples:remote:check
```
