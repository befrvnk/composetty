# Composetty iOS sample

This SwiftUI application embeds a Compose terminal through the static `ComposettyKit` framework.
It uses the same local echo transport as the Android sample, so keyboard, selection, clipboard,
Unicode, scrolling, and accessory keys can be exercised without an SSH server.

Requirements:

- macOS on Apple silicon
- Xcode with iOS device and simulator SDKs
- `devenv` available on `PATH`

Open `ComposettySample.xcodeproj` in Xcode and run the `ComposettySample` scheme. The first build
phase enters the project devenv and invokes `:samples:ios:embedAndSignAppleFrameworkForXcode`, so
Nix remains responsible for the Ghostty and bridge archives.

The checked-in project intentionally supports only arm64 devices and arm64 simulators, matching the
published Composetty targets. Set your development team in Xcode before installing on a physical
device.

Command-line simulator and unsigned device builds can be checked with:

```shell
samples/ios/check.sh
```

The tool variables are unset because commands launched from the devenv shell otherwise point at
Nix compiler wrappers; Xcode selects its own Apple toolchain for the Swift application. The Gradle
build phase still enters devenv for the Kotlin framework.
