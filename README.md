# FIPSDroid

> ⚠️ **EXPERIMENTAL / WORK IN PROGRESS** ⚠️
>
> This project is in early development. Nothing is tested. Nothing is guaranteed to work.
> Do not use this for anything production-related. Expect bugs, incomplete features, and breaking changes.

Android FIPS BLE Leaf Node — an experimental implementation attempting to connect an Android phone to a machine running upstream [`fips`](https://github.com/jmcorgan/fips) over Bluetooth Low Energy (L2CAP CoC).

## Status

| Component | Status |
|-----------|--------|
| Embassy Timer Audit | ✅ Complete |
| Rust Workspace | ✅ Scaffolded |
| Android Project | ✅ Scaffolded |
| Type Definitions | ✅ Defined |
| BLE Transport | ✅ Complete |
| Node Lifecycle | 🚧 In Progress |
| UniFFI Bridge | 🚧 In Progress |
| Android BLE Manager | ✅ Complete |
| End-to-End Test | ❌ Not Started |

## What This Is

A feasibility study to determine if an Android APK can:
1. Connect over BLE L2CAP CoC to a machine running upstream `fips`
2. Complete a Noise IK handshake using `microfips-protocol`
3. Exchange heartbeats and maintain a stable connection

## What This Is NOT

- Production-ready
- Tested on real hardware (yet)
- Fully implemented
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

## Build (Eventually)

```bash
# Rust crate
cargo check

# Android APK (not yet functional)
cd android && ./gradlew assembleDebug
```

## Related Projects

- [microfips](https://github.com/Amperstrand/microfips) — Embedded FIPS protocol implementation
- [fips](https://github.com/jmcorgan/fips) — Upstream FIPS daemon

## License

TBD

## Contributing

Not ready for contributions yet. Check back when the feasibility study is complete.
