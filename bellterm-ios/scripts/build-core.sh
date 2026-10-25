#!/usr/bin/env bash
# Builds the Rust core for iOS devices and the simulator into
# bellterm-ios/Generated: BelltermCore.xcframework and its UniFFI Swift
# bindings. Needs Xcode, Zig 0.15.2, and pkg-config.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
work="$root/target/ios-core"
out="$root/bellterm-ios/Generated"
# Must match GHOSTTY_COMMIT in the build.rs of the libghostty-vt-sys that
# Cargo.lock pins (0.2.2).
ghostty_commit=a887df42c56f6de86c0fe6da9c4eeca37931e083
targets=(aarch64-apple-ios:aarch64-ios aarch64-apple-ios-sim:aarch64-ios-simulator)

# Zig 0.15.2 cannot read the macOS 26.4+ SDK's libSystem stubs, so hide the
# macOS SDK from it: its build runner then links against Zig's bundled stubs.
# The iOS SDK queries still reach the real xcrun.
shim="$work/xcrun-shim"
mkdir -p "$shim"
cat > "$shim/xcrun" <<'EOF'
#!/bin/sh
[ "$1 $2" = "--sdk macosx" ] && exit 1
exec /usr/bin/xcrun "$@"
EOF
chmod +x "$shim/xcrun"
export PATH="$shim:$PATH"

ghostty="$work/ghostty"
if [ "$(git -C "$ghostty" rev-parse HEAD 2>/dev/null)" != "$ghostty_commit" ]; then
  rm -rf "$ghostty"
  git init -q "$ghostty"
  git -C "$ghostty" fetch -q --depth 1 https://github.com/ghostty-org/ghostty.git "$ghostty_commit"
  git -C "$ghostty" checkout -q FETCH_HEAD
fi

libraries=()
for pair in "${targets[@]}"; do
  rust_target=${pair%%:*}
  zig_target=${pair##*:}
  prefix="$work/libghostty-vt-$zig_target"
  # The flags libghostty-vt-sys passes for its own vendored build.
  (cd "$ghostty" && zig build \
    -Demit-lib-vt=true -Doptimize=ReleaseFast -Dcpu=baseline \
    -Demit-xcframework=false -Dapp-runtime=none \
    -Dtarget="$zig_target" --prefix "$prefix")
  PKG_CONFIG_ALLOW_CROSS=1 PKG_CONFIG_PATH="$prefix/share/pkgconfig" \
    cargo build --locked --release -p bellterm-ffi --target "$rust_target"
  libraries+=("$root/target/$rust_target/release/libbellterm_ffi.a")
done

bindings="$work/bindings"
rm -rf "$bindings"
cargo run --locked --release -p bellterm-ffi --features bindgen --bin uniffi-bindgen -- \
  generate --library "${libraries[0]}" --language swift --out-dir "$bindings"

headers="$work/headers"
rm -rf "$headers" "$out"
mkdir -p "$headers" "$out/Swift"
cp "$bindings"/*.h "$headers/"
cp "$bindings"/*.modulemap "$headers/module.modulemap"
cp "$bindings"/*.swift "$out/Swift/"

xcframework_args=()
for library in "${libraries[@]}"; do
  xcframework_args+=(-library "$library" -headers "$headers")
done
xcodebuild -create-xcframework "${xcframework_args[@]}" -output "$out/BelltermCore.xcframework"
