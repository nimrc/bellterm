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

# End-to-end UI tests on the connected device; a host adds the live connection test.
# am instrument exits 0 on failures too, so success is read from its summary.
e2e host="" port="22" user="" fingerprint="":
    ./gradlew installDebug installDebugAndroidTest
    adb shell rm -rf /sdcard/Android/data/app.bellterm/files/e2e
    adb shell am instrument -w -r \
        {{ if host != "" { "-e host " + host + " -e port " + port + " -e user " + user } else { "" } }} \
        {{ if fingerprint != "" { "-e fingerprint " + fingerprint } else { "" } }} \
        app.bellterm.test/androidx.test.runner.AndroidJUnitRunner | tee /dev/stderr | grep -q '^OK ('
