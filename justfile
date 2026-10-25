# The debug APK: Gradle builds the Rust core through cargo-ndk first.
build:
    ./gradlew assembleDebug

# The Rust workspace's checks and tests on the host, then the app's JVM unit tests.
test:
    cargo fmt --all --check
    cargo clippy --workspace --locked --all-targets -- -D warnings
    cargo test --workspace --locked
    ./gradlew testDebugUnitTest

format:
    cargo fmt --all

# The iOS Rust core as bellterm-ios/Generated: xcframework plus Swift bindings.
# Needs a macOS 26 host: Zig 0.15.2 cannot link on macOS 27.
ios-core:
    bellterm-ios/scripts/build-core.sh

# The Xcode project from bellterm-ios/project.yml.
ios-project:
    cd bellterm-ios && xcodegen

# Swift tests on the newest iPhone simulator; needs bellterm-ios/Generated.
ios-test: ios-project
    cd bellterm-ios && xcodebuild test -project Bellterm.xcodeproj -scheme Bellterm \
        -destination "platform=iOS Simulator,name=$(xcrun simctl list devices available | grep -o 'iPhone[^(]*' | tail -1 | sed 's/ *$//')" \
        CODE_SIGNING_ALLOWED=NO
