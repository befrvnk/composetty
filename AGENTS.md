# Composetty Agent Context

Composetty is a Compose Multiplatform terminal component backed by `libghostty-vt`.

## Build

Use the project devenv for all development commands:

```shell
devenv shell -- ./gradlew check
devenv build outputs.native
devenv build outputs.androidNative
devenv build outputs.iosNative
```

Nix owns the native dependency and bridge build. Gradle packages Nix outputs from
`COMPOSETTY_NATIVE_RESOURCES`, `COMPOSETTY_ANDROID_JNI_LIBS`, and `COMPOSETTY_IOS_NATIVE`; do not
add Git or Zig invocation to Gradle.

## Boundaries

- `native/ghostty` owns the narrow C ABI only.
- JVM/JNA, Android/JNI, iOS/cinterop, and PTY lifecycle code stays independent of application concerns.
- Compose code must not depend on Smith or another consuming application.
- Public API changes require tests, explicit API declarations, and a reviewed `api/` dump update.
- Keep Ghostty's revision pinned and update it atomically with bridge changes.

## Verification

Run the narrowest relevant checks. Desktop native changes require both
`devenv build outputs.native` and `devenv shell -- ./gradlew test` on a supported host. Android
native changes additionally require `devenv build outputs.androidNative` and
`devenv shell -- ./gradlew assemble assembleAndroidTest :samples:android:assembleDebug`. Changes
to the x86-64 Android slice require `outputs.androidX86Test` on x86-64 Linux with KVM. iOS native
changes require
`devenv build outputs.iosNative`, `devenv shell -- ./gradlew iosSimulatorArm64Test`, a successful
`linkDebugTestIosArm64` device link, and `samples/ios/check.sh`. Publication or variant changes require
`devenv shell -- ./gradlew consumerSmokeTest` against the standalone consumer build. Use
`releasePreflight` for offline signed release validation and `releaseUpload` for a user-managed
Maven Central deployment; GitHub Actions must invoke these same Gradle tasks.
