# Native Ghostty bridge

This directory contains Composetty's narrow C ABI around `libghostty-vt`.
The bridge is built by the `outputs.native` derivation in `devenv.nix`; it
does not fetch source code or invoke Zig directly.

Ghostty is pinned as a flake input in `devenv.yaml`. Its Nix package supplies
the headers, static archive, and pkg-config metadata used by the bridge.

Build the desktop and Android artifacts with:

```shell
devenv build outputs.native
devenv build outputs.androidNative
devenv build outputs.iosNative
```

Desktop supports macOS and Linux on arm64 and x86-64. Android supports arm64-v8a and x86_64 through
a small registered-JNI adapter. iOS supports arm64 devices and arm64 simulators through static
archives consumed by Kotlin/Native cinterop. Symbol export files ensure that desktop exposes only
the `composetty_terminal_*` ABI and Android exposes only `JNI_OnLoad`. Linux release libraries are
stripped of debug information and unneeded symbols before packaging.
