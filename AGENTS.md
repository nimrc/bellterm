# AGENTS.md

Engineering guidance for agents working in this repository. Read it alongside
`README.md` and the code before making changes.

## Layout

Bellterm is a native Android and iOS client of a Herdr daemon reached over SSH. Herdr
on the host owns terminals and session state; disconnecting the app must leave
them running. The app never installs, starts, stops, or upgrades Herdr.

| Location | Responsibility |
| --- | --- |
| `app/` | Kotlin/Compose UI, JNI wrapper, Android services |
| `bellterm-ios/` | SwiftUI app (XcodeGen `project.yml`), mirroring `app/` |
| `bellterm-ios/rust` | `bellterm-ffi`: `native` exposed to Swift through UniFFI |
| `native/` | Rust JNI core: russh transport, libghostty-vt screen, input routing |
| `crates/herdr-protocol` | Herdr wire types, framing, validation, atomic surface patches |
| `crates/herdr-client` | Discovery, socket worker, session transitions, ordered commands |
| `crates/test-support/sandbox.rs` | Isolated process setup for herdr-client's opt-in live tests |

- Keep dependency direction from `bellterm-ffi` to `native` to `herdr-client` to
  `herdr-protocol`.
- Bellterm is Apache-2.0, and `crates/` is third-party Apache-2.0 code (see
  `NOTICE`): keep its copyright, licence files, and `license` fields as they
  are. Vendored bincode field and enum variant order in `herdr-protocol` is a
  wire contract: never reorder it, and keep the attribution in
  `crates/herdr-protocol/NOTICE.md`.
- Never modify Herdr itself; it is owned by an unrelated team.
- Put shared Rust dependency versions in the root `[workspace.dependencies]`.

## Rust

- Every crate exposes its own `Error` and `Result<T>` built with `thiserror`.
  Never use `String` as an error type; propagate with `?` and keep sources.
- No `unwrap()`/`expect()` in production code, and no new `unsafe`.
- Keep test bodies out of production files: declare `#[cfg(test)] mod tests;`.
- Treat terminal content and daemon messages as untrusted data.

## Android

- The UI thread never blocks: socket, disk, and SSH work live in the Rust core
  or on background threads.
- Keep `local.properties`, keystores, and signing passwords out of git.

## iOS

- The main actor never blocks: `Session.nextEvent` runs on its own thread.
- Keep `bellterm-ios/Generated/` and the generated `.xcodeproj` out of git.
- iOS 26 is the floor; wrap iPhone Duo APIs in `#available(iOS 27.1, *)`.

## Verification

```sh
just format
just test     # Rust fmt, clippy, tests; then Kotlin unit tests
just build    # debug APK; needs the NDK, cargo-ndk, and Zig 0.15.2
just ios-core # iOS core; needs Xcode 26.0 on macOS 26 (see README)
just ios-test # Swift tests on a simulator; needs bellterm-ios/Generated
```

Report what was actually run; never claim an unrun check passed.

## Git

- Conventional commit subjects (`feat`, `fix`, `refactor`, `docs`, `test`,
  `chore`); explain nontrivial changes in the body.
- Only commit, push, or open a PR when asked. Never add AI attribution lines.
