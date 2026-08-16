{ inputs, lib, pkgs, ... }:

let
  system = pkgs.stdenv.hostPlatform.system;
  androidSupportedHost = system != "aarch64-linux";
  androidX86EmulatorSupportedHost = system == "x86_64-linux";
  xcodeDeveloperDir = "/Applications/Xcode.app/Contents/Developer";
  iosSupportedHost = system == "aarch64-darwin" && builtins.pathExists xcodeDeveloperDir;
  ghosttyVt = inputs.ghostty.packages.${system}.libghostty-vt-releasefast;
  platform =
    {
      aarch64-darwin = "macos-arm64";
      x86_64-darwin = "macos-x86_64";
      aarch64-linux = "linux-arm64";
      x86_64-linux = "linux-x86_64";
    }.${system} or (throw "Composetty does not support ${system}");
  libraryName = if pkgs.stdenv.hostPlatform.isDarwin then "libcomposetty-ghostty.dylib" else "libcomposetty-ghostty.so";
  nativeBridge = pkgs.stdenv.mkDerivation {
    pname = "composetty-ghostty";
    version = "0.1.0-dev+22d1317";
    src = lib.cleanSource ./native/ghostty;

    nativeBuildInputs =
      [ pkgs.pkg-config ]
      ++ lib.optionals pkgs.stdenv.hostPlatform.isDarwin [ pkgs.darwin.sigtool ];
    buildInputs = [ ghosttyVt.dev ];

    dontConfigure = true;

    buildPhase =
      if pkgs.stdenv.hostPlatform.isDarwin then
        ''
          runHook preBuild
          export PKG_CONFIG_PATH="${ghosttyVt.dev}/share/pkgconfig"
          $CC \
            -dynamiclib \
            -O2 \
            -fPIC \
            -fvisibility=hidden \
            "$src/bridge.c" \
            $(pkg-config --cflags --libs --static libghostty-vt-static) \
            -Wl,-dead_strip \
            -Wl,-headerpad_max_install_names \
            -Wl,-exported_symbols_list,"$src/exports.macos" \
            -Wl,-install_name,@rpath/${libraryName} \
            -o ${libraryName}
          codesign --force --sign - ${libraryName}
          runHook postBuild
        ''
      else
        ''
          runHook preBuild
          export PKG_CONFIG_PATH="${ghosttyVt.dev}/share/pkgconfig"
          $CC \
            -shared \
            -O2 \
            -fPIC \
            -fvisibility=hidden \
            -ffunction-sections \
            -fdata-sections \
            "$src/bridge.c" \
            $(pkg-config --cflags --libs --static libghostty-vt-static) \
            -Wl,--gc-sections \
            -Wl,--exclude-libs,ALL \
            -Wl,--version-script,"$src/exports.linux.map" \
            -Wl,-soname,${libraryName} \
            -Wl,-z,defs \
            -Wl,-z,relro \
            -Wl,-z,now \
            -o ${libraryName}
          runHook postBuild
        '';

    installPhase = ''
      runHook preInstall
      resourceDirectory="$out/share/composetty/native/ghostty/${platform}"
      mkdir -p "$resourceDirectory"
      cp ${libraryName} "$resourceDirectory/"
      runHook postInstall
    '';
  };

  # Compose against the up-to-date, pinned metadata maintained by devenv-android-sdk.
  androidComposition = (pkgs.androidenv.override { licenseAccepted = true; }).composeAndroidPackages {
    repoJson = "${inputs.android-sdk}/repo.json";
    cmdLineToolsVersion = "20.0";
    platformToolsVersion = "37.0.0";
    buildToolsVersions = [ "36.0.0" ];
    platformVersions = [ "36" ];
    includeEmulator = false;
    includeSources = false;
    includeSystemImages = false;
    includeNDK = true;
    ndkVersions = [ "28.2.13676358" ];
  };
  androidSdkPackage = androidComposition.androidsdk;
  androidNdk = "${androidSdkPackage}/libexec/android-sdk/ndk-bundle";
  androidNdkHostTag = if pkgs.stdenv.hostPlatform.isDarwin then "darwin-x86_64" else "linux-x86_64";
  ghosttyVtAndroidBase = inputs.ghostty.packages.${system}.libghostty-vt-releasefast-no-simd;
  mkGhosttyVtAndroid = target:
    ghosttyVtAndroidBase.overrideAttrs (old: {
      pname = "libghostty-vt-${target}";
      ANDROID_NDK_HOME = androidNdk;
      dontStrip = true;
      # Zig 0.16 emits ELF TLS references for x86_64 Android that the NDK cannot
      # resolve for our minSdk. Build that slice single-threaded and serialize all
      # Android bridge calls in Kotlin; the arm64 slice keeps normal threading.
      postPatch = (old.postPatch or "") + lib.optionalString (target == "x86_64-linux-android") ''
        substituteInPlace src/build/GhosttyLibVt.zig \
          --replace-fail \
            'if (lib.rootModuleTarget().abi.isAndroid()) {' \
            $'if (lib.rootModuleTarget().abi.isAndroid()) {\n        lib.root_module.single_threaded = true;'
        substituteInPlace src/terminal/c/sys.zig \
          --replace-fail \
            'if (comptime builtin.target.cpu.arch.isWasm()) return;' \
            $'if (comptime builtin.target.cpu.arch.isWasm()) return;\n    if (comptime builtin.single_threaded) return;'
        substituteInPlace src/lib/allocator.zig \
          --replace-fail \
            'return std.heap.smp_allocator;' \
            $'if (comptime builtin.single_threaded) return std.heap.page_allocator;\n    return std.heap.smp_allocator;'
      '';
      zigBuildFlags = old.zigBuildFlags ++ [ "-Dtarget=${target}" ];
    });
  mkAndroidBridge = { abi, target, clang }:
    let ghosttyVtAndroid = mkGhosttyVtAndroid target;
    in pkgs.stdenvNoCC.mkDerivation {
      pname = "composetty-ghostty-android-${abi}";
      version = "0.1.0-dev+22d1317";
      src = lib.cleanSource ./native/ghostty;

      dontConfigure = true;

      buildPhase = ''
        runHook preBuild
        compiler="${androidNdk}/toolchains/llvm/prebuilt/${androidNdkHostTag}/bin/${clang}26-clang"
        "$compiler" \
          -shared \
          -O2 \
          -fPIC \
          -fvisibility=hidden \
          -ffunction-sections \
          -fdata-sections \
          -I${ghosttyVtAndroid.dev}/include \
          -I${androidNdk}/toolchains/llvm/prebuilt/${androidNdkHostTag}/sysroot/usr/include \
          "$src/bridge.c" \
          "$src/jni.c" \
          ${ghosttyVtAndroid.dev}/lib/libghostty-vt.a \
          -Wl,--gc-sections \
          -Wl,--exclude-libs,ALL \
          -Wl,--version-script,"$src/exports.android.map" \
          -Wl,-soname,libcomposetty-ghostty.so \
          -Wl,-z,defs \
          -Wl,-z,relro \
          -Wl,-z,now \
          -Wl,-z,max-page-size=16384 \
          -o libcomposetty-ghostty.so
        "${androidNdk}/toolchains/llvm/prebuilt/${androidNdkHostTag}/bin/llvm-strip" \
          --strip-unneeded libcomposetty-ghostty.so
        runHook postBuild
      '';

      installPhase = ''
        runHook preInstall
        mkdir -p "$out/jniLibs/${abi}"
        cp libcomposetty-ghostty.so "$out/jniLibs/${abi}/"
        runHook postInstall
      '';
    };
  androidArm64 = mkAndroidBridge {
    abi = "arm64-v8a";
    target = "aarch64-linux-android";
    clang = "aarch64-linux-android";
  };
  androidX86_64 = mkAndroidBridge {
    abi = "x86_64";
    target = "x86_64-linux-android";
    clang = "x86_64-linux-android";
  };
  androidNative = pkgs.symlinkJoin {
    name = "composetty-ghostty-android";
    paths = [ androidArm64 androidX86_64 ];
  };
  androidX86EmulatorSdk =
    if androidX86EmulatorSupportedHost then
      ((pkgs.androidenv.override { licenseAccepted = true; }).composeAndroidPackages {
        repoJson = "${inputs.android-sdk}/repo.json";
        cmdLineToolsVersion = "20.0";
        platformToolsVersion = "37.0.0";
        buildToolsVersions = [ "36.0.0" ];
        platformVersions = [ "35" "36" ];
        includeEmulator = true;
        includeSources = false;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis_playstore" ];
        abiVersions = [ "x86_64" ];
        includeNDK = false;
      }).androidsdk
    else
      null;
  androidX86Test =
    if androidX86EmulatorSupportedHost then
      pkgs.writeShellApplication {
        name = "composetty-android-x86-test";
        runtimeInputs = [ androidX86EmulatorSdk pkgs.coreutils pkgs.zulu17 ];
        text = ''
          workspace="$PWD"
          if [ ! -x "$workspace/gradlew" ]; then
            echo "Run composetty-android-x86-test from the repository root" >&2
            exit 1
          fi

          sdk="${androidX86EmulatorSdk}/libexec/android-sdk"
          state="$(mktemp -d -t composetty-android-x86.XXXXXX)"
          emulator_pid=""
          cleanup() {
            if [ -n "$emulator_pid" ]; then
              kill "$emulator_pid" 2>/dev/null || true
              wait "$emulator_pid" 2>/dev/null || true
            fi
            rm -rf "$state"
          }
          trap cleanup EXIT INT TERM

          export ANDROID_HOME="$sdk"
          export ANDROID_SDK_ROOT="$sdk"
          export ANDROID_USER_HOME="$state/user"
          export ANDROID_AVD_HOME="$state/avd"
          export JAVA_HOME="${pkgs.zulu17}"
          export COMPOSETTY_NATIVE_RESOURCES="${nativeBridge}/share/composetty"
          export COMPOSETTY_ANDROID_JNI_LIBS="${androidNative}/jniLibs"
          export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$sdk/build-tools/36.0.0/aapt2"
          mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"

          echo no | "$sdk/cmdline-tools/20.0/bin/avdmanager" create avd \
            --force \
            --name composetty-x86_64 \
            --package 'system-images;android-35;google_apis_playstore;x86_64'
          "$sdk/emulator/emulator" \
            -avd composetty-x86_64 \
            -no-window \
            -no-audio \
            -no-boot-anim \
            -no-snapshot \
            -wipe-data \
            -gpu swiftshader_indirect \
            -accel on \
            -cores 2 \
            -memory 2048 &
          emulator_pid=$!

          "$sdk/platform-tools/adb" wait-for-device
          for _ in $(seq 1 180); do
            if [ "$("$sdk/platform-tools/adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
              break
            fi
            if ! kill -0 "$emulator_pid" 2>/dev/null; then
              echo "Android emulator exited before boot completed" >&2
              exit 1
            fi
            sleep 1
          done
          if [ "$("$sdk/platform-tools/adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != 1 ]; then
            echo "Timed out waiting for the Android x86_64 emulator" >&2
            exit 1
          fi

          "$workspace/gradlew" androidConnectedCheck --no-daemon
        '';
      }
    else
      null;

  mkGhosttyVtIos = target:
    ghosttyVtAndroidBase.overrideAttrs (old: {
      pname = "libghostty-vt-${target}";
      DEVELOPER_DIR = xcodeDeveloperDir;
      SDKROOT = "";
      dontStrip = true;
      preBuild = (old.preBuild or "") + ''
        export DEVELOPER_DIR=${xcodeDeveloperDir}
        unset SDKROOT
        /usr/bin/xcrun --sdk iphoneos --show-sdk-path >/dev/null
      '';
      zigBuildFlags = old.zigBuildFlags ++ [ "-Dtarget=${target}" ];
    });
  mkIosBridge = { name, sdk, clangTarget, zigTarget }:
    let ghosttyVtIos = mkGhosttyVtIos zigTarget;
    in pkgs.stdenvNoCC.mkDerivation {
      pname = "composetty-ghostty-${name}";
      version = "0.1.0-dev+22d1317";
      src = lib.cleanSource ./native/ghostty;
      DEVELOPER_DIR = xcodeDeveloperDir;
      SDKROOT = "";

      dontConfigure = true;

      buildPhase = ''
        runHook preBuild
        sdkPath=$(/usr/bin/xcrun --sdk ${sdk} --show-sdk-path)
        /usr/bin/xcrun --sdk ${sdk} clang \
          -target ${clangTarget} \
          -isysroot "$sdkPath" \
          -O2 \
          -fPIC \
          -fvisibility=hidden \
          -I${ghosttyVtIos.dev}/include \
          -c "$src/bridge.c" \
          -o bridge.o
        /usr/bin/xcrun --sdk ${sdk} libtool \
          -static \
          -o libcomposetty-ghostty.a \
          bridge.o \
          ${ghosttyVtIos.dev}/lib/libghostty-vt.a
        runHook postBuild
      '';

      installPhase = ''
        runHook preInstall
        mkdir -p "$out/${name}/include" "$out/${name}/lib"
        cp "$src/bridge.h" "$out/${name}/include/"
        cp libcomposetty-ghostty.a "$out/${name}/lib/"
        runHook postInstall
      '';
    };
  iosArm64 = mkIosBridge {
    name = "iosArm64";
    sdk = "iphoneos";
    clangTarget = "arm64-apple-ios14.0";
    zigTarget = "aarch64-ios";
  };
  iosSimulatorArm64 = mkIosBridge {
    name = "iosSimulatorArm64";
    sdk = "iphonesimulator";
    clangTarget = "arm64-apple-ios14.0-simulator";
    zigTarget = "aarch64-ios-simulator";
  };
  iosNative = pkgs.symlinkJoin {
    name = "composetty-ghostty-ios";
    paths = [ iosArm64 iosSimulatorArm64 ];
  };
in
{
  languages.java = {
    enable = true;
    jdk.package = pkgs.zulu17;
  };

  packages =
    [
      pkgs.gradle_9
      pkgs.pkg-config
    ]
    ++ lib.optionals androidSupportedHost [
      androidSdkPackage
      androidComposition.platform-tools
    ];

  outputs =
    { native = nativeBridge; }
    // lib.optionalAttrs androidSupportedHost { androidNative = androidNative; }
    // lib.optionalAttrs androidX86EmulatorSupportedHost { androidX86Test = androidX86Test; }
    // lib.optionalAttrs iosSupportedHost { iosNative = iosNative; };

  env =
    { COMPOSETTY_NATIVE_RESOURCES = "${nativeBridge}/share/composetty"; }
    // lib.optionalAttrs androidSupportedHost {
      ANDROID_HOME = "${androidSdkPackage}/libexec/android-sdk";
      ANDROID_SDK_ROOT = "${androidSdkPackage}/libexec/android-sdk";
      ANDROID_NDK_ROOT = androidNdk;
      GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdkPackage}/libexec/android-sdk/build-tools/36.0.0/aapt2";
      COMPOSETTY_ANDROID_JNI_LIBS = "${androidNative}/jniLibs";
    }
    // lib.optionalAttrs iosSupportedHost {
      COMPOSETTY_IOS_NATIVE = "${iosNative}";
    };

  scripts.gw.exec = "./gradlew \"$@\"";
  scripts.native-build.exec = "devenv build outputs.native";
  scripts.native-export.exec = ''
    set -euo pipefail
    destination="''${1:-build/native-resources}"
    mkdir -p "$destination"
    cp -R "${nativeBridge}/share/composetty/." "$destination/"
    chmod -R u+w "$destination"
    echo "$destination/native/ghostty/${platform}/${libraryName}"
  '';

  git-hooks.hooks.check = {
    enable = true;
    name = "Gradle check";
    entry = "devenv shell -- ./gradlew check";
    pass_filenames = false;
  };

  enterShell = ''
    ${lib.optionalString iosSupportedHost ''
      export DEVELOPER_DIR=${xcodeDeveloperDir}
      unset SDKROOT
    ''}
    echo "Composetty development environment"
    echo "  Java:   $(java -version 2>&1 | head -n1)"
    echo "  Native: $COMPOSETTY_NATIVE_RESOURCES/native/ghostty/${platform}/${libraryName}"
  '';
}
