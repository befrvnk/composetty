# Composetty

A terminal component for Compose Multiplatform powered by
[`libghostty-vt`](https://github.com/ghostty-org/ghostty).

Composetty combines Ghostty's terminal parser and state model with a Compose renderer and a
transport-neutral session API for remote terminals. Desktop applications can additionally use a
Pty4J-managed local shell. Consumers do not need to ship a separate Ghostty installation.

> [!IMPORTANT]
> Composetty is an independent project. It is not affiliated with or endorsed by the Ghostty
> project. “Ghostty” is used only to identify the upstream project and library.

## Status

Composetty is under initial development and has not been published to Maven Central yet. The first
release is planned for Compose Multiplatform on Android, iOS, and JVM desktop. Desktop currently
supports macOS and Linux on arm64 and x86-64. The `libghostty` API is still evolving, so Composetty
pins and tests one exact Ghostty revision per release.

Current functionality includes:

- transport-neutral sessions for SSH, WebSocket, and other remote terminal connections
- local PTY-backed shell sessions on desktop
- VT parsing and scrollback through `libghostty-vt`
- ANSI and true-color rendering
- bold, italic, faint, underline, and strikethrough text
- cursor styles, resizing, keyboard encoding, focus, and scrolling

Software-keyboard input, committed IME text, touch scrolling, complete grapheme clusters,
double-width cells, long-press selection, clipboard copy/paste, and an optional mobile keyboard
accessory are supported. Selection handles and autoscroll, marked-text rendering during IME
composition, hyperlinks, and Kitty images are not implemented yet.

## Dependency

After the first release:

```kotlin
dependencies {
    implementation("dev.befrvnk.composetty:ghostty-compose:<version>")
}
```

Published artifacts will contain the native libraries. Consumers do not need Nix, Zig, a C
compiler, or a locally installed Ghostty application.

## Usage

```kotlin
val factory = remember { GhosttyTerminalSessionFactory() }
val transport = remember(connection) {
    object : TerminalTransport {
        override fun write(bytes: ByteArray) {
            connection.enqueueInput(bytes)
        }

        override fun resize(size: TerminalSize) {
            connection.enqueueResize(size.columns, size.rows)
        }
    }
}
val session = remember(transport) {
    factory.create(
        initialTheme = TerminalTheme(
            foreground = TerminalRgb(230, 230, 230),
            background = TerminalRgb(25, 25, 25),
            cursor = TerminalRgb(230, 230, 230),
        ),
        transport = transport,
    )
}

LaunchedEffect(session, connection) {
    connection.output.collect(session::receive)
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
```

`TerminalKeyboardAccessory` is optional and provides Escape, Tab, Ctrl-C, Ctrl-D, arrow, Copy,
and Paste buttons. Clipboard text should be sent with `session.paste(text)`, which applies Ghostty's
control-byte filtering and current bracketed-paste mode, rather than `sendText`.

Transport callbacks must return promptly; enqueue data when the underlying connection uses
suspending I/O. A session owns native terminal state, but it does not own or close the remote
connection.

Desktop applications can instead create a local shell with
`LocalPtyTerminalSessionFactory().create(workingDirectory, theme)`.

The Android sample in `samples/android` uses an echo transport to exercise the software keyboard,
selection, clipboard actions, accessory keys, and touch scrollback without requiring an SSH server.
The SwiftUI application in `samples/ios` exercises the same functionality through an embedded
Compose framework. The standalone build in `samples/consumer` resolves only the published Maven
artifacts and verifies JVM native loading, Android APK native packaging, and iOS device/simulator
linking. Build the Android sample with `gw :samples:android:assembleDebug`; see
`samples/ios/README.md` for the Xcode workflow.

## Development

[Nix](https://nixos.org/) owns the native build and
[devenv](https://devenv.sh/) owns the development environment.

```shell
# Enter the complete environment and realize the host native bridge
devenv shell

# Kotlin, native integration, and ABI compatibility tests
gw check

# Publish to an isolated repository and test a standalone KMP consumer
gw consumerSmokeTest

# Generate the API documentation published in documentation JARs
gw dokkaGenerate

# Build native outputs
devenv build outputs.native
devenv build outputs.androidNative
devenv build outputs.iosNative

# Assemble the Android AAR, device-test APK, and interactive sample
gw assemble assembleAndroidTest :samples:android:assembleDebug

# On x86-64 Linux with KVM, accept the Android SDK license for this Nix build,
# then run device tests against the packaged x86-64 library
export NIXPKGS_ACCEPT_ANDROID_SDK_LICENSE=1
runner="$(devenv build -q outputs.androidX86Test | sed -n 's/.*: "\(.*\)"/\1/p')"
"$runner/bin/composetty-android-x86-test"
```

Reference ABI declarations are stored in `api/`. The regular `check` task rejects incompatible
or unreviewed public API changes; run `gw updateKotlinAbi` only after reviewing an intentional API
change.

Ghostty is an exact flake input in `devenv.yaml` and `devenv.lock`. Its `libghostty-vt-releasefast`
package supplies headers, a static archive, and pkg-config metadata. Composetty's Nix derivation
links that package into a narrow platform bridge. Desktop uses a dynamic C ABI loaded through JNA;
Android uses a JNI library containing the statically linked Ghostty VT implementation. iOS uses
Kotlin/Native cinterop with the bridge and Ghostty embedded as a static library in each published
KLIB.

The native output is arranged exactly as the Maven resource layout:

```text
native/ghostty/macos-arm64/libcomposetty-ghostty.dylib
native/ghostty/macos-x86_64/libcomposetty-ghostty.dylib
native/ghostty/linux-arm64/libcomposetty-ghostty.so
native/ghostty/linux-x86_64/libcomposetty-ghostty.so
```

The Android output is packaged into the AAR from:

```text
jniLibs/arm64-v8a/libcomposetty-ghostty.so
jniLibs/x86_64/libcomposetty-ghostty.so
```

The iOS output contains device and arm64 simulator archives:

```text
iosArm64/lib/libcomposetty-ghostty.a
iosSimulatorArm64/lib/libcomposetty-ghostty.a
```

Kotlin/Native 2.4 defaults new iOS binaries to iOS 15. Applications retaining Composetty's iOS 14
minimum must override the final binary's Konan property for each configured target, as demonstrated
in `samples/ios/build.gradle.kts`. Composetty applies the same override to its own published target
compilations and verifies the sample framework's `MinimumOSVersion`.

A full Xcode installation with the iOS device and simulator SDKs is required to build or test the
iOS target. `devenv` selects `/Applications/Xcode.app` for these tasks. Composetty overrides
Kotlin/Native's current default deployment target so the published KLIBs and sample framework retain
the documented iOS 14 minimum.

Set `COMPOSETTY_GHOSTTY_LIBRARY` to an absolute library path to override bundled-resource loading
during native development.

## Publishing

The Maven coordinates are:

```text
dev.befrvnk.composetty:ghostty-compose
```

A release aggregates and tests the desktop outputs, packages both Android ABI libraries, and embeds
the iOS device and simulator archives before invoking `publishAndReleaseToMavenCentral`. The
`verifyReleaseNativeResources`, `verifyAndroidNativeResources`, and `verifyIosNativeResources`
tasks prevent an incomplete platform set from reaching Maven Central. The canonical Gradle tasks
are `releasePreflight` for an offline signed check and `releaseUpload` for a user-managed Central
deployment; both are usable locally and the latter is also invoked by GitHub Actions. Release
versions are derived from signed `vVERSION` tags and must have a matching `CHANGELOG.md` section. The protected
`maven-central` GitHub environment resolves Central and in-memory OpenPGP credentials from
1Password, uploads a deployment for manual inspection, and deliberately leaves final publication to
a maintainer in the Central Portal. Pushing a signed release tag triggers the same locally runnable
Gradle task after approval of the protected GitHub environment. See
[`docs/releasing.md`](docs/releasing.md).

## License

Composetty is licensed under the Apache License 2.0. The statically linked Ghostty code is licensed
under the MIT License; its copyright and license are included in `NOTICE` and the published
artifacts.
