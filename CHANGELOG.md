# Changelog

All notable changes to Composetty are documented in this file. The project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once a release is published.

## [Unreleased]

### Fixed

- Include the Composetty and Ghostty licenses and notice in published iOS native artifacts.

## [0.1.0-alpha01] - 2026-08-12

Initial multiplatform preview release.

### Added

- Transport-neutral terminal sessions for consumer-owned SSH, WebSocket, and other byte streams.
- Compose terminal renderer for JVM desktop, Android, and iOS.
- Bundled `libghostty-vt` native libraries for macOS and Linux on arm64 and x86-64.
- Android native libraries for arm64-v8a and x86_64, with Android 8.0 (API 26) as the minimum.
- iOS arm64 device and arm64 simulator artifacts, with iOS 14 as the minimum.
- Optional Pty4J local-shell sessions on JVM desktop.
- ANSI and true-color rendering, cursor styles, resizing, keyboard encoding, and scrollback.
- Complete Unicode grapheme clusters, combining marks, emoji sequences, and double-width cells.
- Mobile software-keyboard input, committed IME text, touch scrolling, selection, and clipboard
  integration.
- Safe bracketed-paste encoding and an optional mobile accessory row with terminal control keys.
- Android, iOS, and standalone published-consumer samples.
- ABI compatibility validation and cross-platform native, lifecycle, concurrency, and replay tests.

### Known limitations

- Marked-text rendering during IME composition is not implemented.
- Selection handles and drag autoscroll are not implemented.
- Hyperlinks, terminal mouse reporting, and Kitty graphics are not rendered.
- Windows desktop is not supported.

[Unreleased]: https://github.com/befrvnk/composetty/compare/v0.1.0-alpha01...HEAD
[0.1.0-alpha01]: https://github.com/befrvnk/composetty/releases/tag/v0.1.0-alpha01
