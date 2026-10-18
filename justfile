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
