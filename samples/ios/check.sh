#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."
env -u LD -u CC -u CXX -u AR -u AS -u NM -u RANLIB -u STRIP \
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild \
    -project samples/ios/ComposettySample.xcodeproj \
    -scheme ComposettySample \
    -configuration Debug \
    -sdk iphonesimulator \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath build/ios-sample-derived \
    CODE_SIGNING_ALLOWED=NO \
    build

test "$(/usr/libexec/PlistBuddy -c 'Print :MinimumOSVersion' \
  samples/ios/build/xcode-frameworks/Debug/iphonesimulator*/ComposettyKit.framework/Info.plist)" = "14.0"

env -u LD -u CC -u CXX -u AR -u AS -u NM -u RANLIB -u STRIP \
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild \
    -project samples/ios/ComposettySample.xcodeproj \
    -scheme ComposettySample \
    -configuration Debug \
    -sdk iphoneos \
    -destination 'generic/platform=iOS' \
    -derivedDataPath build/ios-sample-device-derived \
    CODE_SIGNING_ALLOWED=NO \
    build

test "$(/usr/libexec/PlistBuddy -c 'Print :MinimumOSVersion' \
  samples/ios/build/xcode-frameworks/Debug/iphoneos*/ComposettyKit.framework/Info.plist)" = "14.0"
