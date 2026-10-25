#!/usr/bin/env bash
# Builds the Rust core for iOS devices and the simulator into
# bellterm-ios/Generated: BelltermCore.xcframework and its UniFFI Swift
# bindings. Needs Zig 0.15.2, pkg-config, and an Xcode whose SDKs Zig 0.15.2
# can link against: it cannot read the libSystem stubs of macOS SDK 26.5 and
# later, so CI selects Xcode 26.0 on a macOS 26 runner.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
work="$root/target/ios-core"
out="$root/bellterm-ios/Generated"
# Must match GHOSTTY_COMMIT in the build.rs of the libghostty-vt-sys that
# Cargo.lock pins (0.2.2).
ghostty_commit=a887df42c56f6de86c0fe6da9c4eeca37931e083
# Rust target : slice of Ghostty's libghostty-vt xcframework
targets=(aarch64-apple-ios:ios-arm64 aarch64-apple-ios-sim:ios-arm64-simulator)

ghostty="$work/ghostty"
if [ "$(git -C "$ghostty" rev-parse HEAD 2>/dev/null)" != "$ghostty_commit" ]; then
  rm -rf "$ghostty"
  git init -q "$ghostty"
  git -C "$ghostty" fetch -q --depth 1 https://github.com/ghostty-org/ghostty.git "$ghostty_commit"
  git -C "$ghostty" checkout -q FETCH_HEAD
fi

# A native macOS build with the xcframework enabled also builds iOS device and
# simulator static libraries. Cross-compiling instead would link an iOS dylib
# against SDK stubs Zig 0.15.2 cannot read.
vt="$work/libghostty-vt"
(cd "$ghostty" && zig build \
  -Demit-lib-vt=true -Demit-xcframework=true -Doptimize=ReleaseFast \
  -Dapp-runtime=none --prefix "$vt")
xcframework="$vt/lib/ghostty-vt.xcframework"

libraries=()
for pair in "${targets[@]}"; do
  rust_target=${pair%%:*}
  slice=${pair##*:}
  archive=$(find "$xcframework/$slice" -name '*.a' | head -1)
  if [ -z "$archive" ]; then
    echo "no $slice archive in $xcframework:" >&2
    find "$xcframework" >&2
    exit 1
  fi
  # libghostty-vt-sys links `static=ghostty-vt` from the .pc's directory.
  prefix="$work/libghostty-vt-$rust_target"
  rm -rf "$prefix"
  mkdir -p "$prefix/lib" "$prefix/share/pkgconfig"
  cp "$archive" "$prefix/lib/libghostty-vt.a"
  cat > "$prefix/share/pkgconfig/libghostty-vt-static.pc" <<PC
prefix=$prefix
Name: libghostty-vt-static
Description: Ghostty VT library (static, $slice)
Version: 0
Cflags: -I$ghostty/include
Libs: \${prefix}/lib/libghostty-vt.a
PC
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
