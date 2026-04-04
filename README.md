# FIPSDroid

> ⚠️ **EXPERIMENTAL / WORK IN PROGRESS** ⚠️
>
> This project is in early development. Nothing is tested. Nothing is guaranteed to work.
> Do not use this for anything production-related. Expect bugs, incomplete features, and breaking changes.

Android FIPS BLE Leaf Node — an experimental implementation attempting to connect an Android phone to a machine running upstream [`fips`](https://github.com/jmcorgan/fips) over Bluetooth Low Energy (L2CAP CoC).

## Status (Verified 2026-04-04)

| Component | Status |
|-----------|--------|
| Rust core (`fipsdroid-core`) tests | ✅ 19/19 pass |
| Android APK build/install | ✅ `assembleDebug` + `installDebug` pass |
| BLE demo runtime logs | ✅ Confirmed via `adb logcat` |
| BLE demo scan/connect to Mac | ⚠️ Scan works; no Mac match in latest run |
| Main UI wired to Rust bridge | ❌ Not wired (TODO stubs in `MainActivity`) |
| Bridge ViewModel | ❌ Missing (`BridgeViewModel.kt` absent) |
| FIPS leaf-node end-to-end (Noise IK + heartbeats) | ❌ Not yet validated |

## Evidence Snapshot (2026-04-04)

### Commands run and outcomes

- `cargo test -p fipsdroid-core` → **PASS** (19 passed, 0 failed)
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**
- `./gradlew :app:installDebug` → **Installed on device** (`SM-G991B`)
- `adb logcat` during app runs → logs captured from `BlePermissions` and `BleDemo`
- `system_profiler SPBluetoothDataType` on Mac → Bluetooth **State: Off**
- `/tmp/fips-l2cap.log` from Swift relay → `Bluetooth OFF` (relay cannot advertise/connect)

### Code-level evidence for current blockers

- `android/app/src/main/java/com/fipsdroid/MainActivity.kt`
  - `onConnect` and `onDisconnect` contain TODO placeholders and only update UI state/log lines.
- `android/app/src/main/java/com/fipsdroid/ble/RustBridge.kt`
  - Only `StubRustBridge` exists; no real UniFFI-backed implementation wired in app flow.
- `android/app/src/main/java/com/fipsdroid/BridgeViewModel.kt`
  - File does not exist.
- UniFFI APIs exist (`uniffi/fipsdroid_core/fipsdroid_core.kt`), but are not used by app code yet.

## What This Is

A feasibility study to determine if an Android APK can:
1. Connect over BLE L2CAP CoC to a machine running upstream `fips`
2. Complete a Noise IK handshake using `microfips-protocol`
3. Exchange heartbeats and maintain a stable connection

## What This Is NOT (yet)

- Production-ready
- Fully integrated end-to-end
- A complete FIPS node (leaf node only, no routing)

## Architecture

```
┌─────────────────┐
│   Android App   │  Kotlin / Jetpack Compose
│   (fipsdroid)   │
└────────┬────────┘
         │ UniFFI
         ▼
┌─────────────────┐
│  fipsdroid-core │  Rust
│  (this repo)    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ microfips-protocol│  Protocol state machine
│ microfips-core   │  Crypto primitives
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  BLE Transport  │  L2CAP CoC (PSM 0x0085)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Upstream fips  │  Linux (BlueZ)
└─────────────────┘
```

## Prerequisites

- Rust 1.70+
- Android SDK (API 29+)
- Android NDK (for cross-compilation)
- Cargo-ndk

## Build

```bash
# Rust crate
cargo check

# Android APK
cd android && ./gradlew assembleDebug

# Install to connected device
./gradlew :app:installDebug

# Run Rust tests
cd .. && cargo test -p fipsdroid-core
```

## Current Challenges / Blockers

1. **Main app is not connected to Rust bridge yet**
   - The production-like UI exists, but the connect/disconnect actions are placeholders.
2. **No BridgeViewModel orchestration layer**
   - No byte relay loop (`feedIncoming`/`pollOutgoing`) between BLE socket and Rust bridge in main app flow.
3. **Mac Bluetooth was off during latest verification run**
   - Prevented relay advertisement/discovery, so no BLE connection evidence in this run.
4. **Leaf-node proof not complete**
   - No verified `Established` + heartbeat progression in integrated path yet.

## Next Milestones

### Milestone A — Wire Real Integration Path (Android)
- Add `BridgeViewModel.kt` with real `FipsDroidBridge` lifecycle + callback mapping.
- Replace `StubRustBridge` flow in app path with UniFFI-backed implementation.
- Wire `MainActivity` actions to ViewModel connect/disconnect.

### Milestone B — End-to-End Runtime Validation
- Start fips daemon (TCP), Swift BLE↔TCP relay, and Android app.
- Capture synchronized logs from:
  - Android (`adb logcat`)
  - Swift relay (`/tmp/fips-l2cap.log`)
  - daemon stdout/stderr
- Verify evidence of:
  - BLE connect
  - Noise IK completion
  - heartbeat send/receive counters

### Milestone C — Stabilization + Documentation
- Convert captured runtime proof into reproducible runbook.
- Update issue tracker with pass/fail artifacts.
- Close solved issues and create follow-ups for remaining blockers.

## Related Projects

- [microfips](https://github.com/Amperstrand/microfips) — Embedded FIPS protocol implementation
- [fips](https://github.com/jmcorgan/fips) — Upstream FIPS daemon
- [fips (fork for macOS)](https://github.com/Amperstrand/fips) — Fork with macOS compatibility improvements

## License

TBD

## Contributing

Not ready for contributions yet. Check back when the feasibility study is complete.
