# FIPSDroid Phase 2: Android Integration + macOS CoreBluetooth Peer

## TL;DR

> **Quick Summary**: Complete Android-side integration (ViewModel, Debug UI, pubkey exchange wiring) and fork upstream jmcorgan/fips to add a macOS CoreBluetooth BLE transport — enabling end-to-end testing of FIPSDroid APK against the user's Mac over Bluetooth Low Energy.
>
> **Deliverables**:
> - Working FIPSDroid APK with debug UI showing connection state + heartbeats
> - Forked fips with `corebluetooth` feature implementing BleIo/BleStream/BleAcceptor/BleScanner traits
> - End-to-end test evidence: Android phone ↔ Mac over BLE L2CAP CoC
> - Revised documentation covering macOS peer setup
>
> **Estimated Effort**: Large (multi-week)
> **Parallel Execution**: YES — 5 waves
> **Critical Path**: Swift L2CAP prototype (go/no-go) → CoreBluetooth impl → E2E test

---

## Context

### Prior Work (Tasks 1-10 COMPLETE)

All foundation work is done and verified:
- Embassy timer audit: ≤32 concurrent timers confirmed, `generic-queue-32` sufficient
- Rust workspace: `fipsdroid-core` crate with microfips-protocol + microfips-core dependencies
- Android project: Gradle + Kotlin + Compose scaffold with BLE permissions
- Type definitions: ConnectionState, HeartbeatStatus, NodeConfig, FipsDroidError
- Research: L2CAP API patterns, UniFFI async patterns documented
- BLE transport: Channel-based `BleTransport` implementing `microfips_protocol::Transport`
- Node lifecycle: `FipsDroidNode` wrapping `microfips_protocol::Node` with state tracking
- UniFFI bridge: `FipsDroidBridge` with callback interface (skeleton — spawns empty tokio task)
- Android BLE: `BleConnectionManager` with L2CAP socket I/O + permission handling
- Cargo-ndk: Cross-compilation setup for Android targets
- **15/15 Rust tests pass**, Android builds, CI works, no LSP errors

### What Changed

User has no Bluetooth dongle → cannot use Linux VM with BLE passthrough → need macOS CoreBluetooth transport in fips so the user's Mac acts as BLE peer. User chose to **fork jmcorgan/fips** and add CoreBluetooth support alongside existing BlueZ transport.

### Metis Review

**Identified Gaps (addressed in this plan)**:
- **macOS L2CAP feasibility**: Added Swift prototype task (go/no-go gate) before committing to Rust implementation
- **Async runtime**: fips uses tokio (confirmed by `Cargo.toml: tokio = { version = "1", features = ["rt", "sync", "macros"] }`), NOT smol — compatible with existing fipsdroid
- **NSError → BleError mapping**: Included as explicit subtask in CoreBluetooth implementation
- **Thread safety for delegate callbacks**: Plan specifies channels to bridge Obj-C main queue → tokio
- **BleScanner implementation**: Will return hardcoded address once, then None
- **Edge cases**: BT radio toggle, PSM collision, unexpected disconnect, MTU, multiple connections — all addressed in QA scenarios
- **Single connection guardrail**: CoreBluetoothAcceptor accepts ONE connection, rejects subsequent

---

## Work Objectives

### Core Objective

Complete FIPSDroid Android integration and add macOS CoreBluetooth transport to a fork of upstream fips, then demonstrate end-to-end BLE communication between the Android phone and the user's Mac.

### Concrete Deliverables

1. **FIPSDroid APK** — fully wired: ViewModel + Debug UI + BLE → UniFFI → Node lifecycle
2. **Forked fips** — with `corebluetooth` feature implementing all BleIo/BleStream/BleAcceptor/BleScanner traits via objc2-core-bluetooth
3. **E2E test evidence** — logcat + terminal output proving L2CAP → pubkey exchange → Noise IK → heartbeats
4. **Updated documentation** — architecture + findings + macOS setup guide

### Definition of Done

- [ ] FIPSDroid APK launches, shows debug UI, Connect/Disconnect buttons work
- [ ] Forked fips builds on macOS with `--features corebluetooth`, advertises on PSM 0x0085
- [ ] Android phone connects to Mac over BLE L2CAP CoC
- [ ] Noise IK handshake completes (verified by logcat + fips terminal)
- [ ] At least one heartbeat exchange occurs (verified by logcat + fips terminal)
- [ ] All Rust tests pass in both repos
- [ ] Android app builds (`./gradlew assembleDebug`)

### Must Have

- Android ViewModel exposing ConnectionState + HeartbeatStatus as StateFlow
- Debug UI: state indicator (colored dot), heartbeat counter, Connect/Disconnect button, error display
- Pubkey exchange wired into Node lifecycle (after L2CAP connect, before Noise handshake)
- macOS CoreBluetooth transport implementing all 4 BleIo sub-traits
- Feature flag `corebluetooth` in forked fips (alongside `ble` for BlueZ)
- CoreBluetooth→tokio callback bridging via channels
- NSError → TransportError mapping
- Foreground-only operation (both macOS and Android)

### Must NOT Have (Guardrails)

- ❌ Multi-peer connection pool (single peer only)
- ❌ BLE scanning/discovery (hardcode addresses)
- ❌ Routing or relay functionality (leaf node only)
- ❌ Production key management (hardcoded keys)
- ❌ GATT fallback transport
- ❌ Auto-reconnection logic
- ❌ Background service (either platform)
- ❌ Polished UI / animations / custom theming
- ❌ macOS GUI app (CLI/TUI only for fips)
- ❌ Custom MTU negotiation (use defaults)
- ❌ Multiple simultaneous L2CAP channels (accept ONE, reject subsequent)
- ❌ iOS or Windows support
- ❌ PR to upstream fips (fork only for now)
- ❌ Both `ble` + `corebluetooth` features enabled simultaneously

---

## Verification Strategy

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed. No exceptions.

### Test Decision
- **Infrastructure exists**: YES (cargo test, 15 tests passing)
- **Automated tests**: YES (Tests-after — add tests for new code)
- **Framework**: `cargo test` for Rust, `./gradlew test` for Kotlin
- **Agent-Executed QA**: ALWAYS — logcat capture, terminal output, adb commands

### QA Policy
Every task MUST include agent-executed QA scenarios. Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

- **Rust crate**: Bash (`cargo test`, `cargo build`)
- **Android app**: Bash (`./gradlew assembleDebug`, `adb install`, `adb logcat`)
- **macOS fips**: Bash (`cargo build --features corebluetooth`, run binary, check output)
- **End-to-end**: Bash (start fips on Mac, install APK on phone, capture logs from both)

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 3A (Start Immediately — Android integration, MAX PARALLEL):
├── Task 11: Android↔Rust Integration — ViewModel + bridge wiring [deep]
├── Task 12: Debug UI — DebugScreen.kt + ConnectionStateIndicator.kt [visual-engineering]
└── Task 13: Pubkey exchange integration with Node lifecycle [unspecified-high]

Wave 3B (Start Immediately — macOS side, PARALLEL with 3A):
├── Task 14: Fork jmcorgan/fips + macOS build verification [quick]
└── Task 15: Swift L2CAP Prototype — GO/NO-GO gate [quick]

Wave 4 (After Wave 3B Task 15 passes — CoreBluetooth implementation):
├── Task 16: CoreBluetoothIo + CoreBluetoothStream [deep]
├── Task 17: CoreBluetoothAcceptor + CoreBluetoothScanner [deep]
└── Task 18: Feature-gating + build verification + integration test [unspecified-high]

Wave 5 (After Waves 3A + 4 — end-to-end):
└── Task 19: End-to-end test (Android ↔ Mac over BLE) [deep]

Wave 6 (After Wave 5 — documentation):
└── Task 20: Documentation — architecture, findings, macOS setup [writing]

Wave FINAL (After ALL tasks — 4 parallel reviews, then user okay):
├── Task F1: Plan compliance audit (oracle)
├── Task F2: Code quality review (unspecified-high)
├── Task F3: Real manual QA (unspecified-high)
└── Task F4: Scope fidelity check (deep)
→ Present results → Get explicit user okay

Critical Path: Task 15 (go/no-go) → Task 16 → Task 18 → Task 19 → F1-F4 → user okay
Parallel Speedup: ~50% faster than sequential
Max Concurrent: 5 (Wave 3A + 3B)
```

### Dependency Matrix

| Task | Depends On | Blocks | Wave |
|------|-----------|--------|------|
| 11 | (T8, T9, T10 complete) | 19 | 3A |
| 12 | (T9 complete) | 19 | 3A |
| 13 | (T6, T7 complete) | 19 | 3A |
| 14 | — | 15, 16, 17, 18 | 3B |
| 15 | 14 | 16, 17 (go/no-go) | 3B |
| 16 | 14, 15 | 18 | 4 |
| 17 | 14, 15 | 18 | 4 |
| 18 | 16, 17 | 19 | 4 |
| 19 | 11, 12, 13, 18 | 20 | 5 |
| 20 | 19 | F1-F4 | 6 |

### Agent Dispatch Summary

- **Wave 3A**: **3 tasks** — T11 → `deep`, T12 → `visual-engineering`, T13 → `unspecified-high`
- **Wave 3B**: **2 tasks** — T14 → `quick`, T15 → `quick`
- **Wave 4**: **3 tasks** — T16 → `deep`, T17 → `deep`, T18 → `unspecified-high`
- **Wave 5**: **1 task** — T19 → `deep`
- **Wave 6**: **1 task** — T20 → `writing`
- **FINAL**: **4 tasks** — F1 → `oracle`, F2 → `unspecified-high`, F3 → `unspecified-high`, F4 → `deep`

---

## TODOs

### Wave 3A: Android Integration (Start Immediately — parallel with Wave 3B)

- [ ] 11. Android↔Rust Integration — ViewModel + Bridge Wiring

  **What to do**:
  - Create `android/app/src/main/java/com/fipsdroid/FipsDroidViewModel.kt`:
    - Holds `FipsDroidBridge` instance (from UniFFI-generated bindings)
    - Implements `FipsDroidCallback` to receive state changes + heartbeats from Rust
    - Exposes `connectionState: StateFlow<ConnectionState>` for Compose UI
    - Exposes `heartbeatStatus: StateFlow<HeartbeatStatus>` for Compose UI
    - Exposes `errorMessage: StateFlow<String?>` for error display
    - `connect()` method: creates BLE L2CAP connection via `BleConnectionManager`, then calls `bridge.start(callback)`
    - `disconnect()` method: calls `bridge.stop()`, then disconnects BLE
  - Wire the byte flow between `BleConnectionManager` and `FipsDroidBridge`:
    - `BleConnectionManager` reads from L2CAP socket → feeds bytes to Rust bridge
    - Rust writes bytes → `BleConnectionManager` writes to L2CAP socket
    - The existing `BleTransport` in Rust uses `tokio::sync::mpsc` channels — the Kotlin side needs to feed/drain those channels via UniFFI methods
  - Update `bridge.rs` to actually wire up `FipsDroidNode::run()` instead of the current empty tokio task:
    - The `start()` method should create `BleTransport` from the byte channels, create `FipsDroidNode`, and call `node.run()` in the spawned tokio task
    - Add methods for the Kotlin side to push incoming bytes and pull outgoing bytes:
      - `feed_incoming(data: Vec<u8>)` — pushes bytes from BLE socket into Rust transport channel
      - `poll_outgoing() -> Option<Vec<u8>>` — pulls bytes from Rust transport channel for BLE socket
  - Handle lifecycle: Activity stop → `bridge.stop()` → BLE disconnect
  - Update `MainActivity.kt` to use ViewModel and Compose state

  **Must NOT do**:
  - Do not implement background service (foreground activity only)
  - Do not persist connection state across app restarts
  - Do not add dependency injection framework
  - Do not implement auto-reconnection
  - Do not handle configuration changes beyond ViewModel survival

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Integration layer bridging Android lifecycle, BLE I/O, and Rust FFI. Threading model must be correct (main thread for UI, IO dispatcher for BLE, tokio for Rust). Subtle bugs arise from lifecycle mismatches.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3A (with Tasks 12, 13)
  - **Blocks**: Task 19 (end-to-end test)
  - **Blocked By**: Tasks 8, 9, 10 (all complete)

  **References**:

  **Pattern References** (existing code in fipsdroid):
  - `crates/fipsdroid-core/src/bridge.rs` — Current bridge skeleton. Lines 30-65: `FipsDroidBridge::new()` creates tokio runtime. Lines 67-106: `start()` spawns empty tokio task with shutdown channel. The TODO at line 93 says "Replace with FipsDroidNode::run()". Wire this up.
  - `crates/fipsdroid-core/src/node.rs` — `FipsDroidNode::new(config, transport, state_tx)` at line 91 and `node.run()` at line 127. The bridge must create a `BleTransport`, pass it to `FipsDroidNode::new()`, then call `.run()` in the spawned task.
  - `crates/fipsdroid-core/src/transport/ble.rs` — `BleTransport::new(incoming_rx, outgoing_tx, close_tx)` — channel-based transport. Bridge must create the channels and hold the sender/receiver ends.
  - `android/app/src/main/java/com/fipsdroid/ble/BleConnectionManager.kt` — L2CAP socket I/O. This reads/writes bytes from the BLE socket. Needs to be connected to UniFFI bridge methods.
  - `android/app/src/main/java/com/fipsdroid/ble/RustBridge.kt` — Current stub interface (placeholder). Will be replaced by UniFFI-generated bindings.

  **API/Type References**:
  - `kotlinx.coroutines.flow.MutableStateFlow` / `StateFlow` — for reactive UI state
  - `androidx.lifecycle.ViewModel` — survives configuration changes
  - `kotlinx.coroutines.Dispatchers.IO` — for BLE socket operations
  - `crates/fipsdroid-core/src/types.rs` — `ConnectionState`, `HeartbeatStatus`, `NodeConfig` — the types that cross FFI boundary

  **External References**:
  - UniFFI Kotlin integration: `https://mozilla.github.io/uniffi-rs/kotlin/configuration.html`
  - UniFFI callback interfaces: `https://mozilla.github.io/uniffi-rs/proc_macro/callback_interfaces.html`

  **WHY Each Reference Matters**:
  - `bridge.rs` has the TODO telling us exactly what to wire up — follow it
  - `node.rs` defines the exact constructor signature — ViewModel must match it
  - `BleTransport::new()` takes channel halves — bridge must create those channels and expose the other halves via UniFFI for Kotlin to feed/drain
  - `BleConnectionManager.kt` already handles L2CAP socket I/O — just needs to be connected to the bridge methods

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: App builds with native library integration
    Tool: Bash
    Preconditions: cargo-ndk installed, Android NDK available
    Steps:
      1. Run `./build-android.sh` to produce .so + bindings
      2. Run `cd android && ./gradlew assembleDebug`
      3. Verify APK contains libfipsdroid_core.so: `unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep .so`
    Expected Result: APK builds, contains .so, no UnsatisfiedLinkError
    Failure Indicators: UnsatisfiedLinkError, missing .so in APK, UniFFI binding generation failure
    Evidence: .sisyphus/evidence/task-11-apk-build.txt

  Scenario: Bridge wires transport to node (unit test)
    Tool: Bash
    Preconditions: bridge.rs updated with real wiring
    Steps:
      1. Run `cargo test -p fipsdroid-core bridge::tests`
      2. Verify existing tests still pass (bridge creation, initial state, start/stop)
      3. Verify new test: feed_incoming sends bytes through transport channel
    Expected Result: All bridge tests pass, byte flow through channels works
    Failure Indicators: Channel deadlock, wrong thread, tokio runtime issues
    Evidence: .sisyphus/evidence/task-11-bridge-tests.txt

  Scenario: ViewModel initialization (error case — no BLE peer)
    Tool: Bash (adb)
    Preconditions: APK installed on device, no BLE peer available
    Steps:
      1. Install: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`
      2. Launch: `adb shell am start -n com.fipsdroid/.MainActivity`
      3. Capture logcat: `adb logcat -s FIPSDroid:* -d`
      4. Verify log shows bridge initialized with Disconnected state
      5. Verify no crash in 5 seconds
    Expected Result: App launches, bridge initializes, state is Disconnected, no crash
    Failure Indicators: Crash, UnsatisfiedLinkError, native panic
    Evidence: .sisyphus/evidence/task-11-viewmodel-init.txt
  ```

  **Commit**: YES
  - Message: `feat(integration): wire Android BLE to Rust bridge via ViewModel`
  - Files: `crates/fipsdroid-core/src/bridge.rs`, `android/app/src/main/java/com/fipsdroid/FipsDroidViewModel.kt`, `android/app/src/main/java/com/fipsdroid/MainActivity.kt`
  - Pre-commit: `cargo test -p fipsdroid-core && cd android && ./gradlew assembleDebug`

- [ ] 12. Debug UI — Connection State Display

  **What to do**:
  - Create `android/app/src/main/java/com/fipsdroid/ui/DebugScreen.kt` — main composable:
    - Peer address display (hardcoded, shown as text label)
    - Connection state indicator: colored dot + text
      - Red dot + "Disconnected" (default)
      - Yellow dot + "Connecting"
      - Yellow dot + "Handshaking"
      - Green dot + "Established"
      - Orange dot + "Disconnecting"
      - Red dot + "Error: {message}"
    - Heartbeat counter: "Sent: N / Received: N / Last: Xs ago"
    - "Connect" button (visible when Disconnected/Error) / "Disconnect" button (visible when Connected/Established)
    - Error message display area (shows last error if any, red text)
    - Raw log view: scrollable LazyColumn showing last 50 log lines
  - Create `android/app/src/main/java/com/fipsdroid/ui/ConnectionStateIndicator.kt`:
    - Reusable composable: `@Composable fun ConnectionStateIndicator(state: ConnectionState)`
    - Draws colored circle (Canvas) + text label
  - Wire UI to ViewModel StateFlows from Task 11 using `collectAsState()`
  - Update `MainActivity.kt` to use `DebugScreen` instead of current "Hello FipsDroid" text
  - Use Material3 defaults, no custom theming beyond what's already in `Theme.kt`

  **Must NOT do**:
  - Do not create settings screen or configuration UI
  - Do not implement peer address input field (hardcoded)
  - Do not add animations or transitions
  - Do not use custom fonts or colors beyond Material3 defaults
  - Do not implement landscape layout
  - Do not add navigation library
  - Do not add external UI dependencies (no Accompanist beyond what's needed)

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: Jetpack Compose UI creation — visual component design, layout, state binding to ViewModel
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3A (with Tasks 11, 13)
  - **Blocks**: Task 19 (end-to-end test needs UI)
  - **Blocked By**: Task 9 (complete) — uses BLE permissions composable

  **References**:

  **Pattern References** (existing code in fipsdroid):
  - `android/app/src/main/java/com/fipsdroid/ui/theme/Theme.kt` — Existing Material3 theme setup. Use this, don't add custom theming.
  - `android/app/src/main/java/com/fipsdroid/ble/BlePermissions.kt` — Permission request composable. DebugScreen should check permissions before showing Connect button.

  **API/Type References**:
  - `crates/fipsdroid-core/src/types.rs` — `ConnectionState` enum (all variants listed). UI must handle EVERY variant.
  - `crates/fipsdroid-core/src/types.rs` — `HeartbeatStatus` struct (sent_count, received_count, last_received)
  - `androidx.compose.material3.*` — Material3 components (Button, Text, Card, Surface)
  - `androidx.compose.runtime.collectAsState()` — StateFlow → Compose state conversion
  - `androidx.compose.foundation.Canvas` — for drawing colored state indicator dot

  **WHY Each Reference Matters**:
  - `ConnectionState` has 7 variants including `Error(String)` — UI must map each to a color+label, not just 2-3
  - `HeartbeatStatus.last_received` is `Option<u64>` (epoch seconds) — UI must format as "Xs ago" or "Never"
  - Material3 ensures consistent defaults without design work
  - `collectAsState()` is THE way to connect ViewModel StateFlow to Compose — no other approach

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Debug screen renders with all elements
    Tool: Bash (adb)
    Preconditions: Task 11 complete, APK installed on device
    Steps:
      1. Launch app: `adb shell am start -n com.fipsdroid/.MainActivity`
      2. Wait 3 seconds for UI to render
      3. Take screenshot: `adb exec-out screencap -p > .sisyphus/evidence/task-12-debug-screen.png`
      4. Verify logcat shows no crash: `adb logcat -s FIPSDroid:* -d | tail -5`
    Expected Result: Screenshot shows: peer address, red dot with "Disconnected", Connect button, heartbeat "Sent: 0 / Received: 0"
    Failure Indicators: Blank screen, crash, missing elements, yellow screen of death
    Evidence: .sisyphus/evidence/task-12-debug-screen.png

  Scenario: Connect button tap triggers state change
    Tool: Bash (adb)
    Preconditions: App running, no BLE peer available
    Steps:
      1. Tap Connect button (approximate coordinates or content description): `adb shell input tap 540 1200` or use accessibility
      2. Capture logcat: `adb logcat -s FIPSDroid:* -d | tail -10`
      3. Verify state transitions to Connecting then to Error (no peer → timeout)
    Expected Result: State indicator changes from red→yellow→red(error), error message displayed
    Failure Indicators: UI stuck, no state change, app crash on tap
    Evidence: .sisyphus/evidence/task-12-state-change.txt
  ```

  **Commit**: YES
  - Message: `feat(ui): debug connection state display with heartbeat counter`
  - Files: `android/app/src/main/java/com/fipsdroid/ui/DebugScreen.kt`, `android/app/src/main/java/com/fipsdroid/ui/ConnectionStateIndicator.kt`, `android/app/src/main/java/com/fipsdroid/MainActivity.kt`
  - Pre-commit: `cd android && ./gradlew assembleDebug`

- [ ] 13. Pubkey Exchange Integration with Node Lifecycle

  **What to do**:
  - Wire the existing `send_pubkey()` / `recv_pubkey()` methods (already in `transport/ble.rs`) into the node startup sequence in `bridge.rs`:
    - After BLE L2CAP connection is established but BEFORE `FipsDroidNode::run()`:
      1. Call `transport.send_pubkey(&local_pubkey)` — sends `[0x00][32B local pubkey]`
      2. Call `transport.recv_pubkey()` — receives `[0x00][32B remote pubkey]`, validates format
      3. Use received remote pubkey for Noise IK handshake initialization
    - This matches the upstream fips sequence exactly (see `jmcorgan/fips :: src/transport/ble/mod.rs`)
  - Update `FipsDroidBridge::start()` to include pubkey exchange step:
    - Currently: spawn empty task → wait for shutdown
    - After: spawn task → create transport → pubkey exchange → create node → node.run()
  - Update `FipsDroidNode::new()` to accept the remote public key from pubkey exchange instead of generating random keys:
    - Current: `generate_key_material()` creates random keys
    - After: Accept `local_secret: [u8; 32]` and `peer_pubkey: [u8; 33]` as parameters
    - The bridge provides these from the hardcoded config + pubkey exchange result
  - Emit `ConnectionState::Handshaking` between pubkey exchange and Noise handshake
  - Add timeout to pubkey exchange: 5 seconds (matching upstream `PUBKEY_EXCHANGE_TIMEOUT_SECS`)
  - Add tests:
    - Pubkey exchange completes before node.run() starts
    - Pubkey exchange timeout → FipsDroidError::Timeout
    - Invalid peer pubkey → FipsDroidError::InvalidPeerKey

  **Must NOT do**:
  - Do not implement key generation UI or key storage
  - Do not add key rotation
  - Do not deviate from upstream fips wire format (`[0x00][32B pubkey]`)
  - Do not change the `send_pubkey()` / `recv_pubkey()` method signatures in ble.rs (they work correctly)

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Protocol integration requiring byte-level correctness and lifecycle sequencing. Must match upstream fips format exactly.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3A (with Tasks 11, 12)
  - **Blocks**: Task 19 (end-to-end test requires pubkey exchange)
  - **Blocked By**: Tasks 6, 7 (both complete)

  **References**:

  **Pattern References** (existing code in fipsdroid):
  - `crates/fipsdroid-core/src/transport/ble.rs` — `send_pubkey(&self, pubkey: &[u8; 32])` and `recv_pubkey(&mut self) -> Result<[u8; 32]>`. These methods already exist and have passing tests. DO NOT modify them.
  - `crates/fipsdroid-core/src/node.rs:91-108` — `FipsDroidNode::new()` currently calls `generate_key_material()` which creates random keys. Needs modification to accept keys from pubkey exchange.
  - `crates/fipsdroid-core/src/node.rs:147-164` — `generate_key_material()` function. Will be replaced by explicit key parameters.
  - `crates/fipsdroid-core/src/bridge.rs:92-103` — The spawned task in `start()`. This is where pubkey exchange → node creation → node.run() sequence goes.

  **Pattern References** (upstream fips — read via zai-zread):
  - `jmcorgan/fips :: src/transport/ble/mod.rs` — `pubkey_exchange()` function. Shows exact sequence: send `[0x00][local_pubkey]`, receive peer's, 5-second timeout, tie-breaker for simultaneous sends. Our sequence is simpler (client sends first, server responds) but wire format must match.

  **API/Type References**:
  - `microfips_core::noise::ecdh_pubkey(secret: &[u8; 32]) -> Result<[u8; 33]>` — derives public key from secret
  - `microfips_protocol::node::Node::new(transport, rng, local_secret, peer_pubkey)` — takes `[u8; 32]` secret and `[u8; 33]` peer pubkey
  - Upstream fips constants: `PUBKEY_EXCHANGE_PREFIX = 0x00`, `PUBKEY_EXCHANGE_SIZE = 33`, `PUBKEY_EXCHANGE_TIMEOUT_SECS = 5`

  **WHY Each Reference Matters**:
  - `ble.rs` send/recv_pubkey are tested and working — don't touch them, just call them from bridge
  - `node.rs` generate_key_material() must be replaced — the current random keys won't match any peer
  - Upstream fips pubkey_exchange shows the wire protocol we must be compatible with
  - Node::new() signature tells us exactly what key material format is expected

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Pubkey exchange integrates with node lifecycle
    Tool: Bash
    Preconditions: bridge.rs updated, transport channels wired
    Steps:
      1. Run `cargo test -p fipsdroid-core` — all existing tests still pass
      2. Run new test: `cargo test -p fipsdroid-core bridge::tests::test_pubkey_exchange_in_lifecycle`
      3. Verify test creates BleTransport with mock channels, performs pubkey exchange, then starts node
    Expected Result: Pubkey exchange completes, node starts with correct keys
    Failure Indicators: Key mismatch, wrong sequence, channel deadlock
    Evidence: .sisyphus/evidence/task-13-pubkey-lifecycle.txt

  Scenario: Pubkey exchange timeout
    Tool: Bash
    Preconditions: Test with blocked transport (no peer response)
    Steps:
      1. Run `cargo test -p fipsdroid-core bridge::tests::test_pubkey_exchange_timeout`
      2. Verify test times out within 5 seconds
      3. Verify error is FipsDroidError::Timeout
    Expected Result: Clean timeout error, no hang
    Failure Indicators: Hangs indefinitely, wrong error type, panic
    Evidence: .sisyphus/evidence/task-13-pubkey-timeout.txt
  ```

  **Commit**: YES
  - Message: `feat(handshake): wire pubkey exchange into node lifecycle`
  - Files: `crates/fipsdroid-core/src/bridge.rs`, `crates/fipsdroid-core/src/node.rs`
  - Pre-commit: `cargo test -p fipsdroid-core`

### Wave 3B: macOS Side — Fork + Feasibility Gate (Start Immediately — parallel with Wave 3A)

- [ ] 14. Fork jmcorgan/fips + macOS Build Verification

  **What to do**:
  - Fork `jmcorgan/fips` to user's GitHub account (Amperstrand/fips)
  - Clone the fork locally to `~/src/fips`
  - Verify the fork builds on macOS WITHOUT the `ble` feature (BlueZ is Linux-only):
    - `cargo build --no-default-features --features tui` — should compile (TUI without BLE)
    - `cargo build --no-default-features` — should compile (minimal, no TUI, no BLE)
    - `cargo build` (default features including `ble`) — will FAIL on macOS (expected, BlueZ is Linux-only)
  - Verify the codebase structure matches our research:
    - `src/transport/ble/io.rs` exists with `BleIo`, `BleStream`, `BleAcceptor`, `BleScanner` traits
    - `src/transport/ble/mod.rs` exists with `BleTransport<I: BleIo>`
    - Feature flag `ble = ["dep:bluer"]` in Cargo.toml
  - Create a new branch: `feature/corebluetooth`
  - Document the fork in fipsdroid's README

  **Must NOT do**:
  - Do not modify any existing fips code yet (just fork + verify)
  - Do not add CoreBluetooth dependencies yet (that's Task 16)
  - Do not attempt to build with `ble` feature on macOS (it will fail — BlueZ is Linux-only)
  - Do not create a PR to upstream

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Fork repo, clone, verify builds. No design decisions, just verification.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3B (with Task 15)
  - **Blocks**: Tasks 15, 16, 17, 18
  - **Blocked By**: None

  **References**:

  **Pattern References** (upstream fips — read via zai-zread):
  - `jmcorgan/fips :: src/transport/ble/io.rs` — Full trait definitions for BleIo, BleStream, BleAcceptor, BleScanner. Already read in full during research. Verify these traits exist in the fork unchanged.
  - `jmcorgan/fips :: Cargo.toml` — Feature flags: `ble = ["dep:bluer"]`, `default = ["tui", "ble"]`. bluer v0.17. edition 2024.

  **WHY Each Reference Matters**:
  - Must verify fork matches our research findings exactly — trait signatures are the contract we'll implement against
  - Cargo.toml features determine how we add CoreBluetooth without conflicting with BlueZ

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Fork builds on macOS without BLE
    Tool: Bash
    Preconditions: GitHub CLI authenticated, git configured
    Steps:
      1. Fork: `gh repo fork jmcorgan/fips --clone --remote` (or verify existing fork)
      2. Verify clone exists at ~/src/fips
      3. Run `cargo build --no-default-features` in ~/src/fips
      4. Verify exit code 0, no compilation errors
    Expected Result: Compiles successfully without BLE feature
    Failure Indicators: Compilation errors, missing dependencies, edition 2024 issues
    Evidence: .sisyphus/evidence/task-14-fork-build.txt

  Scenario: BLE traits exist as expected
    Tool: Bash (grep)
    Preconditions: Fork cloned
    Steps:
      1. Verify `src/transport/ble/io.rs` exists
      2. Grep for `pub trait BleIo` in io.rs
      3. Grep for `pub trait BleStream` in io.rs
      4. Grep for `pub trait BleAcceptor` in io.rs
      5. Grep for `pub trait BleScanner` in io.rs
    Expected Result: All 4 traits found with exact names
    Failure Indicators: Missing traits, renamed traits, different module structure
    Evidence: .sisyphus/evidence/task-14-trait-verify.txt
  ```

  **Commit**: YES (in forked fips repo)
  - Message: `chore: create feature/corebluetooth branch`
  - Files: N/A (just branch creation)
  - Pre-commit: `cargo build --no-default-features`

- [ ] 15. Swift L2CAP Prototype — GO/NO-GO Gate

  **What to do**:
  - Create a minimal Swift CLI/app to verify macOS supports L2CAP peripheral role:
    - Location: `~/src/fips/prototype/` (in the forked fips repo, or standalone)
    - A Swift file that:
      1. Creates a `CBPeripheralManager`
      2. Waits for `CBManagerState.poweredOn`
      3. Calls `peripheralManager.publishL2CAPChannel(withEncryption: false)`
      4. In delegate callback `didPublishL2CAPChannel(withPSM:)`: prints the assigned PSM
      5. In delegate callback `didOpenL2CAPChannel(channel:error:)`: prints "L2CAP channel opened" and reads/writes a few bytes
    - Alternative: Use a Swift Package Manager project for easy build
    - This is a SPIKE — throwaway code to verify the API works
  - Test with any BLE central (e.g., nRF Connect app on Android, or LightBlue):
    - Connect to the Mac's advertised service
    - Open L2CAP channel on the published PSM
    - Verify bidirectional byte transfer
  - **GO/NO-GO Decision**:
    - ✅ GO: macOS opens L2CAP channel, bytes flow bidirectionally → proceed to Rust implementation
    - ❌ NO-GO: macOS doesn't support L2CAP peripheral role, or L2CAP channel doesn't open → STOP. Report to user. Explore alternatives (macOS as central? TCP fallback? different transport?)
  - Document results regardless of outcome

  **Must NOT do**:
  - Do not implement the full fips protocol in Swift (just verify L2CAP works)
  - Do not spend more than a few hours on this — it's a feasibility check, not production code
  - Do not polish the Swift code — it's throwaway
  - Do not implement Noise handshake or pubkey exchange in Swift

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Minimal Swift prototype — 50-100 lines of Swift to verify one API call works. Throwaway code.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3B (with Task 14)
  - **Blocks**: Tasks 16, 17 (if NO-GO, entire macOS CoreBluetooth track is blocked)
  - **Blocked By**: Task 14 (fork must exist for working directory)

  **References**:

  **External References**:
  - Apple CBPeripheralManager docs: `https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager`
  - Apple publishL2CAPChannel: `https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/2880157-publishl2capchannel`
  - Apple CBL2CAPChannel: `https://developer.apple.com/documentation/corebluetooth/cbl2capchannel`
  - Apple CBPeripheralManagerDelegate: `peripheralManager(_:didPublishL2CAPChannel:error:)` and `peripheralManager(_:didOpen:error:)`
  - RxBluetoothKit L2CAP implementation (reference for delegate patterns): `https://github.com/Polidea/RxBluetoothKit`
  - nRF Connect for Mobile (Android): Use as BLE central to test the prototype

  **WHY Each Reference Matters**:
  - Apple docs confirm whether L2CAP peripheral role is supported on macOS (not just iOS)
  - `publishL2CAPChannel` is THE critical API — if this doesn't work on macOS, we need a different approach
  - nRF Connect provides a quick way to test without writing Android code

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Swift prototype publishes L2CAP channel on macOS
    Tool: Bash
    Preconditions: Xcode/Swift toolchain installed, Bluetooth enabled on Mac
    Steps:
      1. Build the Swift prototype: `cd ~/src/fips/prototype && swift build` (or `swiftc main.swift -framework CoreBluetooth`)
      2. Run: `.build/debug/prototype` (or `./main`)
      3. Verify output contains "CBPeripheralManager powered on"
      4. Verify output contains "Published L2CAP channel with PSM:" followed by a number
      5. If PSM appears: L2CAP peripheral role works on macOS → GO
      6. If error or no PSM: → NO-GO
    Expected Result: PSM published, L2CAP channel advertised
    Failure Indicators: "powered off" (BT disabled), "unsupported" (macOS doesn't support L2CAP peripheral), entitlement error
    Evidence: .sisyphus/evidence/task-15-l2cap-prototype.txt

  Scenario: L2CAP bidirectional byte transfer (optional — requires Android/nRF Connect)
    Tool: Bash + manual BLE connection
    Preconditions: Prototype running, Android device with nRF Connect
    Steps:
      1. On Android nRF Connect: scan for Mac, connect
      2. Open L2CAP channel on published PSM
      3. Send bytes from Android
      4. Verify prototype prints received bytes
      5. Prototype sends response bytes
      6. Verify Android receives response
    Expected Result: Bidirectional byte flow works
    Failure Indicators: Connection refused, channel not found, bytes corrupted
    Evidence: .sisyphus/evidence/task-15-l2cap-bidirectional.txt

  Scenario: NO-GO — macOS doesn't support L2CAP peripheral (failure path)
    Tool: Bash
    Preconditions: Prototype fails to publish L2CAP channel
    Steps:
      1. Document the error message
      2. Research alternative approaches:
         a. macOS as BLE central (connect TO Android acting as peripheral)
         b. TCP transport for testing (bypass BLE entirely for mac<->phone)
         c. USB-connected debugging
      3. Write findings to .sisyphus/evidence/task-15-nogo-alternatives.md
    Expected Result: Clear documentation of failure + viable alternatives
    Evidence: .sisyphus/evidence/task-15-nogo-alternatives.md
  ```

  **Commit**: YES (in forked fips repo, or separate)
  - Message: `spike: Swift L2CAP peripheral prototype for macOS feasibility`
  - Files: `prototype/` directory
  - Pre-commit: `swift build`

---

### Wave 4: CoreBluetooth Implementation (After Task 15 GO)

- [ ] 16. CoreBluetoothIo + CoreBluetoothStream Implementation

  **What to do**:
  - Add `objc2-core-bluetooth` (v0.3.2) and `objc2-foundation` as optional dependencies in forked fips `Cargo.toml`:
    - New feature: `corebluetooth = ["dep:objc2-core-bluetooth", "dep:objc2-foundation"]`
    - Ensure `corebluetooth` and `ble` (BlueZ) are mutually exclusive via compile error if both enabled
  - Create `src/transport/ble/corebluetooth.rs` in forked fips with:
    - **CoreBluetoothStream** — wraps `CBL2CAPChannel`:
      - Holds `tokio::sync::mpsc::Sender<Vec<u8>>` (outgoing) and `tokio::sync::mpsc::Receiver<Vec<u8>>` (incoming)
      - Obj-C delegate callback `channel(_:didReadData:)` pushes bytes into the incoming mpsc channel
      - `send()` writes bytes to `CBL2CAPChannel.outputStream` (must dispatch to Obj-C main queue)
      - `recv()` reads from the incoming mpsc channel (async, stays on tokio)
      - `send_mtu()` / `recv_mtu()` return `CBL2CAPChannel.outputMTU` / `CBL2CAPChannel.inputMTU`
      - `remote_addr()` returns `BleAddr` constructed from `CBPeer.identifier` (UUID)
      - Implements `BleStream` trait
    - **CoreBluetoothIo** — wraps `CBPeripheralManager`:
      - Creates `CBPeripheralManager` on init
      - Waits for `CBManagerState.poweredOn` via delegate callback (with timeout)
      - `listen(psm)`: calls `peripheralManager.publishL2CAPChannel(withEncryption: false)`, returns `CoreBluetoothAcceptor`
      - `connect(addr, psm)`: NOT implemented for peripheral role — returns `TransportError::Unsupported`
      - `start_advertising()`: starts advertising with `FIPS_SERVICE_UUID` (`9c90b790-2cc5-42c0-9f87-c9cc40648f4c`)
      - `stop_advertising()`: calls `peripheralManager.stopAdvertising()`
      - `start_scanning()`: returns `CoreBluetoothScanner` (stub — see Task 17)
      - `local_addr()`: returns `BleAddr` from system Bluetooth address
      - `adapter_name()`: returns `"CoreBluetooth"`
      - Implements `BleIo` trait
    - **NSError → TransportError mapping** helper function:
      - `CBError.connectionFailed` → `TransportError::ConnectionFailed`
      - `CBError.peripheralDisconnected` → `TransportError::Disconnected`
      - `CBATTError.insufficientEncryption` → `TransportError::EncryptionRequired`
      - Other errors → `TransportError::Other(format!("{}", nserror))`
    - **Thread safety**: All Obj-C delegate callbacks run on main queue. Use `tokio::sync::mpsc` channels to bridge to tokio runtime. The mpsc sender is held by the Obj-C delegate, the receiver by the async Rust code.
  - Add `#[cfg(feature = "corebluetooth")]` gate to the module
  - Register the module in `src/transport/ble/mod.rs`:
    ```rust
    #[cfg(feature = "corebluetooth")]
    pub mod corebluetooth;
    ```

  **Must NOT do**:
  - Do not touch the existing BlueZ (`bluer`) code — it must continue to work on Linux
  - Do not implement `connect()` — Mac is peripheral (listener), not central
  - Do not implement MTU negotiation — use OS defaults
  - Do not add GUI — this is for CLI/daemon fips only
  - Do not enable both `ble` + `corebluetooth` features simultaneously
  - Do not use `unsafe` blocks without clear justification comments
  - Do not use `block_on()` — use channels for Obj-C→tokio bridging

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Complex FFI bridging between Obj-C runtime (CoreBluetooth delegate callbacks on main queue) and async Rust (tokio). Thread safety, lifetime management, and correct channel usage require careful implementation.
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - `Browser`: No web UI involved

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Task 17 — different types)
  - **Parallel Group**: Wave 4 (with Tasks 17, 18)
  - **Blocks**: Task 18 (integration test)
  - **Blocked By**: Tasks 14, 15 (fork + go/no-go)

  **References**:

  **Pattern References** (upstream fips — the contract to implement against):
  - `jmcorgan/fips :: src/transport/ble/io.rs` — Full `BleIo` trait with 8 methods, `BleStream` trait with 5 methods. These are the EXACT signatures to implement. Already reproduced in handoff context.
  - `jmcorgan/fips :: src/transport/ble/io.rs` — `BluerIo` implementation (~200 lines) — reference for HOW to implement BleIo. Shows pattern: create adapter on init, advertise service UUID, listen returns acceptor wrapping bluer listener.
  - `jmcorgan/fips :: src/transport/ble/mod.rs` — `BleTransport<I: BleIo>` — this generic struct works with ANY BleIo implementation. Our CoreBluetoothIo must satisfy the same trait bounds.
  - `jmcorgan/fips :: Cargo.toml` — Current feature flags. Add `corebluetooth` alongside `ble` without breaking existing features.

  **API/Type References**:
  - `BleAddr` type from fips — 6-byte Bluetooth address. For CoreBluetooth, derive from `CBPeer.identifier` (UUID → first 6 bytes).
  - `TransportError` enum from fips — the error type all trait methods return. Must map NSError codes to these variants.
  - `DEFAULT_PSM: u16 = 0x0085` — the PSM for L2CAP channel publishing.
  - `FIPS_SERVICE_UUID` — `9c90b790-2cc5-42c0-9f87-c9cc40648f4c` — advertised service UUID.

  **External References**:
  - `objc2-core-bluetooth` crate: https://docs.rs/objc2-core-bluetooth/0.3.2 — Rust bindings to CoreBluetooth
  - `objc2` crate: https://docs.rs/objc2 — Foundation for Obj-C interop in Rust
  - Apple CBPeripheralManager: https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager
  - Apple CBL2CAPChannel: https://developer.apple.com/documentation/corebluetooth/cbl2capchannel
  - `alexmoon/corebluetooth-rs`: https://github.com/alexmoon/corebluetooth-rs — reference implementation (uses older objc crate but shows patterns)

  **WHY Each Reference Matters**:
  - `io.rs` BleIo trait is the CONTRACT — every method signature must match exactly
  - `BluerIo` shows the PATTERN for implementing BleIo — follow same structure with CoreBluetooth APIs
  - `BleTransport` is the CONSUMER — our implementation must be compatible with it
  - `objc2-core-bluetooth` docs show the Rust API surface for CoreBluetooth — different from raw Obj-C

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: CoreBluetooth module compiles on macOS
    Tool: Bash
    Preconditions: Forked fips with corebluetooth feature flag added
    Steps:
      1. Run `cargo build --features corebluetooth --no-default-features` in ~/src/fips
      2. Verify exit code 0
      3. Verify no warnings about unused code
    Expected Result: Clean compilation with no errors or warnings
    Failure Indicators: Compilation error, linker error (missing framework), feature conflict
    Evidence: .sisyphus/evidence/task-16-compile.txt

  Scenario: CoreBluetoothStream channel bridging (unit test)
    Tool: Bash
    Preconditions: corebluetooth.rs implemented
    Steps:
      1. Run `cargo test --features corebluetooth test_corebluetooth_stream_channel`
      2. Test creates CoreBluetoothStream with mock channels
      3. Sends bytes through outgoing channel, verifies they arrive
      4. Pushes bytes into incoming channel, verifies recv() returns them
    Expected Result: Bidirectional byte flow through channels works
    Failure Indicators: Channel deadlock, wrong bytes, timeout
    Evidence: .sisyphus/evidence/task-16-stream-test.txt

  Scenario: NSError mapping covers expected error codes
    Tool: Bash
    Preconditions: Error mapping function implemented
    Steps:
      1. Run `cargo test --features corebluetooth test_nserror_mapping`
      2. Verify CBError.connectionFailed → TransportError::ConnectionFailed
      3. Verify CBError.peripheralDisconnected → TransportError::Disconnected
      4. Verify unknown error → TransportError::Other with descriptive message
    Expected Result: All error mappings correct
    Failure Indicators: Wrong mapping, panic on unknown error
    Evidence: .sisyphus/evidence/task-16-error-mapping.txt
  ```

  **Commit**: YES (in forked fips repo)
  - Message: `feat(ble): CoreBluetoothIo + CoreBluetoothStream implementation`
  - Files: `src/transport/ble/corebluetooth.rs`, `Cargo.toml`
  - Pre-commit: `cargo build --features corebluetooth --no-default-features`

- [ ] 17. CoreBluetoothAcceptor + CoreBluetoothScanner

  **What to do**:
  - Implement **CoreBluetoothAcceptor** in `src/transport/ble/corebluetooth.rs`:
    - Implements `BleAcceptor` trait
    - Wraps a `tokio::sync::oneshot::Receiver<CoreBluetoothStream>` — the sender is held by the `CBPeripheralManagerDelegate`
    - When `peripheralManager(_:didOpen:error:)` fires, delegate creates `CoreBluetoothStream` from the `CBL2CAPChannel` and sends it through the oneshot channel
    - `accept()` awaits the oneshot receiver — returns the stream when a client connects
    - **Single connection only**: After first accept, subsequent calls return `TransportError::ConnectionLimitReached` (or similar)
    - Handle error case: if delegate callback receives an error instead of a channel, propagate it
  - Implement **CoreBluetoothScanner** in `src/transport/ble/corebluetooth.rs`:
    - Implements `BleScanner` trait
    - **Stub implementation**: Returns a hardcoded `BleAddr` on first `next()` call, then returns `None` on subsequent calls
    - This is sufficient for testing — we know the Android device address ahead of time
    - The hardcoded address should be configurable via an environment variable `FIPS_PEER_ADDR` (or a const)
  - Wire into `CoreBluetoothIo`:
    - `listen(psm)` now returns `CoreBluetoothAcceptor`
    - `start_scanning()` now returns `CoreBluetoothScanner`
  - Add `DefaultBleTransport` type alias for CoreBluetooth:
    ```rust
    #[cfg(feature = "corebluetooth")]
    pub type DefaultBleTransport = BleTransport<CoreBluetoothIo>;
    ```

  **Must NOT do**:
  - Do not implement real BLE scanning (discovery) — hardcoded address only
  - Do not accept multiple simultaneous connections — single peer only
  - Do not implement reconnection logic in acceptor
  - Do not add connection timeout in acceptor (upstream doesn't have one)

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Acceptor must correctly bridge Obj-C delegate callback (async, runs on main queue) to tokio oneshot channel. Single-connection guardrail requires careful state management.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Task 16 — same file but different types)
  - **Parallel Group**: Wave 4 (with Tasks 16, 18)
  - **Blocks**: Task 18 (integration test)
  - **Blocked By**: Tasks 14, 15 (fork + go/no-go)

  **References**:

  **Pattern References** (upstream fips):
  - `jmcorgan/fips :: src/transport/ble/io.rs` — `BluerAcceptor` implementation — shows pattern: wraps bluer L2CAP listener, `accept()` awaits next connection, creates BluerStream from it. Follow same pattern with CoreBluetooth delegate callback.
  - `jmcorgan/fips :: src/transport/ble/io.rs` — `BluerScanner` implementation — shows pattern: wraps bluer discovery, `next()` yields discovered addresses. Our stub is simpler but must match the trait.
  - `jmcorgan/fips :: src/transport/ble/io.rs` — `DefaultBleTransport` type alias (line ~380) — shows how to wire the type alias with cfg attributes.

  **API/Type References**:
  - `BleAcceptor` trait: `accept(&mut self) -> impl Future<Output = Result<Self::Stream, TransportError>>`
  - `BleScanner` trait: `next(&mut self) -> impl Future<Output = Option<BleAddr>>`
  - `tokio::sync::oneshot` — for single-use channel (acceptor waits for ONE connection)
  - Apple `peripheralManager(_:didOpen:error:)` — delegate callback that fires when L2CAP channel is opened by a client

  **WHY Each Reference Matters**:
  - `BluerAcceptor` shows the exact accept() pattern we need to replicate with CoreBluetooth
  - `BluerScanner` shows what the transport layer expects from next() — we need to match behavior
  - `DefaultBleTransport` type alias pattern must be replicated for `corebluetooth` feature

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Acceptor receives connection via channel
    Tool: Bash
    Preconditions: CoreBluetoothAcceptor implemented
    Steps:
      1. Run `cargo test --features corebluetooth test_acceptor_receives_stream`
      2. Test creates oneshot channel, sends mock stream through sender
      3. Acceptor.accept() receives the stream
      4. Verify stream is usable (can call remote_addr())
    Expected Result: accept() returns the stream successfully
    Failure Indicators: Channel closed prematurely, wrong stream type
    Evidence: .sisyphus/evidence/task-17-acceptor-test.txt

  Scenario: Acceptor rejects second connection
    Tool: Bash
    Preconditions: CoreBluetoothAcceptor already accepted one connection
    Steps:
      1. Run `cargo test --features corebluetooth test_acceptor_single_connection`
      2. First accept() succeeds
      3. Second accept() returns TransportError
    Expected Result: Second accept returns error, not hang
    Failure Indicators: Hangs forever, accepts second connection
    Evidence: .sisyphus/evidence/task-17-acceptor-single.txt

  Scenario: Scanner returns hardcoded address then None
    Tool: Bash
    Preconditions: CoreBluetoothScanner implemented
    Steps:
      1. Run `cargo test --features corebluetooth test_scanner_stub`
      2. First next() returns Some(BleAddr)
      3. Second next() returns None
    Expected Result: Exactly one address yielded
    Failure Indicators: Returns multiple, never returns None, panics
    Evidence: .sisyphus/evidence/task-17-scanner-test.txt
  ```

  **Commit**: YES (in forked fips repo)
  - Message: `feat(ble): CoreBluetoothAcceptor + CoreBluetoothScanner`
  - Files: `src/transport/ble/corebluetooth.rs`
  - Pre-commit: `cargo test --features corebluetooth --no-default-features`

- [ ] 18. Feature-Gating + Build Verification + Integration Test

  **What to do**:
  - Wire CoreBluetooth into the fips transport system:
    - In `src/transport/ble/mod.rs`, add cfg-gated imports and type aliases:
      ```rust
      #[cfg(feature = "corebluetooth")]
      use corebluetooth::CoreBluetoothIo;

      #[cfg(feature = "corebluetooth")]
      pub type DefaultBleTransport = BleTransport<CoreBluetoothIo>;
      ```
    - Ensure `DefaultBleTransport` is available under EITHER `ble` OR `corebluetooth` feature (but not both)
    - Add compile_error! if both features enabled:
      ```rust
      #[cfg(all(feature = "ble", feature = "corebluetooth"))]
      compile_error!("Cannot enable both 'ble' (BlueZ) and 'corebluetooth' features simultaneously");
      ```
  - Verify feature isolation:
    - `cargo build --features corebluetooth --no-default-features` — compiles with CoreBluetooth, no BlueZ
    - `cargo build --features ble --no-default-features` — compiles with BlueZ, no CoreBluetooth (on Linux)
    - `cargo build --no-default-features` — compiles with no BLE at all
    - `cargo build --features "ble,corebluetooth"` — compile error (mutual exclusion)
  - Write integration test (gated behind `corebluetooth` feature + `#[ignore]` for CI):
    - Create `tests/corebluetooth_integration.rs` (or add to existing test file)
    - Test creates `CoreBluetoothIo`
    - Calls `start_advertising()` — verifies no error
    - Calls `listen(0x0085)` — gets an acceptor
    - Calls `stop_advertising()` — verifies no error
    - This test requires macOS with Bluetooth enabled (hence `#[ignore]` for CI)
  - Verify fips can start with CoreBluetooth transport:
    - `cargo run --features corebluetooth --no-default-features -- --help` should work
    - If fips has a BLE mode, verify it initializes CoreBluetoothIo when `corebluetooth` feature is active

  **Must NOT do**:
  - Do not modify the `ble` feature behavior — Linux BlueZ path must be unchanged
  - Do not add `corebluetooth` to default features (it's macOS-only)
  - Do not remove `#[ignore]` from integration test (it needs hardware)
  - Do not implement any new protocol logic — just wiring

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Build system wiring + feature flag correctness + integration test. Must verify mutual exclusion works and both paths compile independently.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential (after Tasks 16, 17)
  - **Blocks**: Task 19 (end-to-end test)
  - **Blocked By**: Tasks 16, 17

  **References**:

  **Pattern References** (upstream fips):
  - `jmcorgan/fips :: src/transport/ble/mod.rs` — Current cfg-gated structure: `#[cfg(feature = "ble")]` controls BlueZ imports, `DefaultBleTransport` type alias, and `MockBleIo` for tests. Extend this pattern with `#[cfg(feature = "corebluetooth")]` blocks.
  - `jmcorgan/fips :: Cargo.toml` — Current feature definitions. Add `corebluetooth = ["dep:objc2-core-bluetooth", "dep:objc2-foundation"]` alongside `ble = ["dep:bluer"]`.

  **API/Type References**:
  - `compile_error!` macro — for mutual exclusion enforcement at compile time
  - Cargo feature flags: `[features]` section in Cargo.toml
  - `#[cfg(feature = "X")]` attribute — conditional compilation

  **WHY Each Reference Matters**:
  - Existing `mod.rs` cfg structure shows exactly where to add CoreBluetooth branches
  - Cargo.toml features must coexist without breaking existing `ble` users

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Feature isolation — corebluetooth builds without BlueZ
    Tool: Bash
    Preconditions: Tasks 16, 17 complete
    Steps:
      1. Run `cargo build --features corebluetooth --no-default-features` in ~/src/fips
      2. Verify exit code 0
      3. Verify no references to bluer in build output
    Expected Result: Clean build with CoreBluetooth only
    Failure Indicators: bluer compilation attempted, linker error
    Evidence: .sisyphus/evidence/task-18-feature-isolation.txt

  Scenario: Mutual exclusion compile error
    Tool: Bash
    Preconditions: compile_error! macro added
    Steps:
      1. Run `cargo build --features "ble,corebluetooth" --no-default-features` in ~/src/fips
      2. Verify compilation FAILS
      3. Verify error message contains "Cannot enable both"
    Expected Result: Compilation fails with clear error message
    Failure Indicators: Compiles successfully (bad!), wrong error message
    Evidence: .sisyphus/evidence/task-18-mutual-exclusion.txt

  Scenario: Integration test — CoreBluetoothIo initializes
    Tool: Bash
    Preconditions: macOS with Bluetooth enabled
    Steps:
      1. Run `cargo test --features corebluetooth --no-default-features -- --ignored test_corebluetooth_init`
      2. Test creates CoreBluetoothIo, calls start_advertising(), then stop_advertising()
      3. Verify no panic, no error
    Expected Result: CoreBluetoothIo initializes and advertises successfully
    Failure Indicators: Panic, entitlement error, Bluetooth off
    Evidence: .sisyphus/evidence/task-18-integration.txt

  Scenario: No-BLE build still works
    Tool: Bash
    Preconditions: Changes merged
    Steps:
      1. Run `cargo build --no-default-features` in ~/src/fips
      2. Verify exit code 0 — builds without any BLE
    Expected Result: Compiles without BLE features
    Failure Indicators: Missing conditional compilation, cfg error
    Evidence: .sisyphus/evidence/task-18-no-ble.txt
  ```

  **Commit**: YES (in forked fips repo)
  - Message: `feat(ble): feature-gate corebluetooth + mutual exclusion + integration test`
  - Files: `Cargo.toml`, `src/transport/ble/mod.rs`, `tests/corebluetooth_integration.rs`
  - Pre-commit: `cargo build --features corebluetooth --no-default-features && cargo build --no-default-features`

### Wave 5: End-to-End Test (After Waves 3A + 4)

- [ ] 19. End-to-End Test — Android ↔ Mac over BLE

  **What to do**:
  - This is the milestone test: FIPSDroid APK on Android phone talks to forked fips on Mac over BLE.
  - **Prerequisites check** (before attempting E2E):
    - Forked fips builds with CoreBluetooth: `cargo build --features corebluetooth --no-default-features`
    - FIPSDroid APK builds: `./build-android.sh && cd android && ./gradlew assembleDebug`
    - Android device connected via USB: `adb devices` shows device
    - Mac Bluetooth is enabled
  - **Mac side setup**:
    - Generate or use hardcoded test keys (32-byte secret + derived pubkey)
    - Start fips with CoreBluetooth: `cargo run --features corebluetooth --no-default-features -- --ble`
    - Verify terminal shows: "Advertising BLE on PSM 133 (0x0085)" (or similar)
    - Note the Mac's Bluetooth address for Android config
  - **Android side setup**:
    - Install APK: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`
    - Configure peer address (hardcoded in Rust or via adb intent extra)
    - Configure peer pubkey (hardcoded to match Mac's key)
  - **Test sequence**:
    1. Mac: fips running, advertising on L2CAP PSM 0x0085
    2. Android: Launch FIPSDroid, tap Connect
    3. Verify L2CAP connection established (logcat + fips terminal)
    4. Verify pubkey exchange completes (both sides log received pubkey)
    5. Verify Noise IK handshake completes (both sides log "handshake complete")
    6. Verify heartbeat sent from one side, received on other
    7. Tap Disconnect on Android
    8. Verify clean disconnect (both sides log "disconnected")
  - **Evidence capture**:
    - `adb logcat -s FIPSDroid:*` — Android side logs
    - fips terminal output — Mac side logs
    - Screenshots of Android debug UI showing state transitions
  - **If E2E fails**: Document the failure point precisely:
    - Which step failed?
    - Error messages from both sides
    - Is it L2CAP (transport layer) or protocol (Noise/heartbeat) issue?

  **Must NOT do**:
  - Do not automate BLE pairing (manual pairing if needed)
  - Do not test multiple simultaneous connections
  - Do not test reconnection after disconnect
  - Do not performance test or stress test
  - Do not test with BLE disabled (that's an edge case for F3)

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Cross-device test requiring coordination of two processes (Mac fips + Android APK), hardware interaction (BLE), and careful evidence capture from both ends. Debugging BLE issues requires understanding of both CoreBluetooth and Android BLE stacks.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential (depends on ALL prior waves)
  - **Blocks**: Task 20 (documentation needs E2E results)
  - **Blocked By**: Tasks 11, 12, 13, 18

  **References**:

  **Pattern References** (fipsdroid):
  - `crates/fipsdroid-core/src/bridge.rs` — The full lifecycle: `start()` → create transport → pubkey exchange → create node → `node.run()`. This is what runs on Android when user taps Connect.
  - `crates/fipsdroid-core/src/transport/ble.rs` — `send_pubkey()` / `recv_pubkey()` — the wire format that must match between Android and Mac.
  - `android/app/src/main/java/com/fipsdroid/ble/BleConnectionManager.kt` — Android L2CAP socket I/O. Verify it connects on PSM 0x0085.

  **Pattern References** (forked fips):
  - `src/transport/ble/mod.rs` — fips BLE lifecycle: advertise → accept connection → pubkey exchange → Noise handshake → heartbeat loop. The Mac side follows this sequence.
  - `src/transport/ble/corebluetooth.rs` — CoreBluetoothIo — the macOS-specific transport implementation.

  **External References**:
  - `adb logcat` reference: https://developer.android.com/tools/logcat
  - Upstream fips CLI flags: Check `--help` output for BLE-related options

  **WHY Each Reference Matters**:
  - Both `bridge.rs` (Android) and `mod.rs` (Mac) define the protocol sequence — they must be compatible
  - `send_pubkey`/`recv_pubkey` wire format is the first thing exchanged — format mismatch = immediate failure
  - `BleConnectionManager` handles the Android BLE socket — it must target the right PSM

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Full E2E — L2CAP → pubkey → Noise → heartbeat
    Tool: Bash (two terminals + adb)
    Preconditions: Mac BT on, Android device connected via USB, both repos built
    Steps:
      1. Mac terminal: `cd ~/src/fips && cargo run --features corebluetooth --no-default-features -- --ble 2>&1 | tee .sisyphus/evidence/task-19-mac-output.txt`
      2. Verify Mac output: "Advertising" or "Listening on PSM"
      3. Android: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`
      4. Android: `adb shell am start -n com.fipsdroid/.MainActivity`
      5. Wait 3s, take screenshot: `adb exec-out screencap -p > .sisyphus/evidence/task-19-android-disconnected.png`
      6. Tap Connect (via adb tap or UI automation)
      7. Wait 10s for full handshake
      8. Capture logcat: `adb logcat -s FIPSDroid:* -d > .sisyphus/evidence/task-19-android-logcat.txt`
      9. Take screenshot: `adb exec-out screencap -p > .sisyphus/evidence/task-19-android-connected.png`
      10. Verify logcat contains: "L2CAP connected", "pubkey exchange complete", "Noise handshake complete", "heartbeat"
      11. Verify Mac output contains: "Connection accepted", "pubkey exchange", "handshake complete", "heartbeat"
      12. Tap Disconnect
      13. Verify both sides show "disconnected"
    Expected Result: Full lifecycle completes — both sides show connected state and heartbeats
    Failure Indicators: L2CAP timeout, pubkey mismatch, Noise handshake failure, no heartbeats
    Evidence: .sisyphus/evidence/task-19-mac-output.txt, task-19-android-logcat.txt, task-19-android-*.png

  Scenario: E2E failure diagnosis (if happy path fails)
    Tool: Bash
    Preconditions: Happy path scenario failed at some step
    Steps:
      1. Identify failure step from evidence files
      2. If L2CAP fails: check `adb logcat -s BluetoothL2cap:*` for Android BLE errors
      3. If pubkey fails: compare hex dump of sent vs received pubkey bytes
      4. If Noise fails: verify key material matches (same secret/pubkey pair on both sides)
      5. Document findings in .sisyphus/evidence/task-19-diagnosis.md
    Expected Result: Clear diagnosis of failure point with recommended fix
    Evidence: .sisyphus/evidence/task-19-diagnosis.md
  ```

  **Commit**: YES
  - Message: `test(e2e): Android↔macOS BLE end-to-end validation`
  - Files: `.sisyphus/evidence/task-19-*`
  - Pre-commit: N/A (evidence files only)

### Wave 6: Documentation (After Wave 5)

- [ ] 20. Documentation — Architecture, Findings, macOS Setup Guide

  **What to do**:
  - Create or update `docs/architecture.md`:
    - Mermaid diagram showing full stack: Android App → UniFFI → fipsdroid-core → microfips-protocol → BLE Transport → L2CAP → CoreBluetooth (Mac) / BlueZ (Linux)
    - Module responsibilities and data flow
    - Thread model: main thread (UI), IO dispatcher (BLE socket), tokio runtime (Rust async)
    - Key design decisions and rationale
  - Create `docs/findings.md`:
    - Feasibility verdict (based on E2E test results)
    - What worked well, what was difficult
    - Known limitations and future work
    - Performance observations (connection time, handshake duration)
  - Create `docs/macos-setup.md`:
    - Prerequisites: macOS version, Xcode command line tools, Rust toolchain
    - Building forked fips: `cargo build --features corebluetooth --no-default-features`
    - Running: exact command with flags
    - Troubleshooting: Bluetooth permissions, entitlements, common errors
    - Testing with Android device: step-by-step guide
  - Update `README.md`:
    - Update status table with all completed tasks
    - Add link to forked fips repo
    - Add "Testing with macOS" section pointing to `docs/macos-setup.md`
    - Update architecture diagram to include macOS path

  **Must NOT do**:
  - Do not create API reference documentation (not at this stage)
  - Do not document internal implementation details of CoreBluetooth bridge
  - Do not create user-facing documentation (this is developer docs only)
  - Do not document features that don't exist yet (routing, multi-peer, etc.)

  **Recommended Agent Profile**:
  - **Category**: `writing`
    - Reason: Documentation task — technical writing, Mermaid diagrams, setup guides. No code changes.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential (needs E2E results from Task 19)
  - **Blocks**: F1-F4 (final verification)
  - **Blocked By**: Task 19

  **References**:

  **Pattern References**:
  - `README.md` — Current README structure. Update in-place, don't restructure.
  - `docs/research-l2cap.md` — Existing research doc. Follow similar formatting style.
  - `docs/research-uniffi.md` — Existing research doc. Follow similar formatting style.

  **WHY Each Reference Matters**:
  - README has existing status table and architecture diagram — update them, don't create new ones
  - Existing docs show the formatting conventions used in this project

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Documentation files exist and are well-formed
    Tool: Bash
    Preconditions: Task 19 complete with evidence
    Steps:
      1. Verify `docs/architecture.md` exists and contains Mermaid diagram (grep for "```mermaid")
      2. Verify `docs/findings.md` exists and contains feasibility verdict
      3. Verify `docs/macos-setup.md` exists and contains build command
      4. Verify `README.md` updated — status table shows all tasks
    Expected Result: All 4 docs exist with required content
    Failure Indicators: Missing files, empty sections, broken Mermaid syntax
    Evidence: .sisyphus/evidence/task-20-docs-verify.txt

  Scenario: macOS setup guide is followable
    Tool: Bash
    Preconditions: docs/macos-setup.md exists
    Steps:
      1. Read the guide and extract the build command
      2. Run the build command in ~/src/fips
      3. Verify it matches the actual build process (command works)
    Expected Result: Guide commands actually work when followed
    Failure Indicators: Wrong directory, wrong flags, missing prerequisite
    Evidence: .sisyphus/evidence/task-20-guide-verify.txt
  ```

  **Commit**: YES
  - Message: `docs: architecture + findings + macOS setup guide`
  - Files: `docs/architecture.md`, `docs/findings.md`, `docs/macos-setup.md`, `README.md`
  - Pre-commit: N/A

---

## Final Verification Wave (MANDATORY — after ALL implementation tasks)

> 4 review agents run in PARALLEL. ALL must APPROVE. Present consolidated results to user and get explicit "okay" before completing.
>
> **Do NOT auto-proceed after verification. Wait for user's explicit approval before marking work complete.**

- [ ] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists (read file, curl endpoint, run command). For each "Must NOT Have": search codebase for forbidden patterns — reject with file:line if found. Check evidence files exist in .sisyphus/evidence/. Compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [ ] F2. **Code Quality Review** — `unspecified-high`
  Run `cargo clippy` + `cargo test` in BOTH repos (fipsdroid + forked fips). Run `./gradlew lint` for Android. Review all changed files for: `unsafe` blocks without justification, `unwrap()` in production code, empty error handling, unused imports. Check AI slop: excessive comments, over-abstraction, generic names.
  Output: `Build [PASS/FAIL] | Lint [PASS/FAIL] | Tests [N pass/N fail] | Files [N clean/N issues] | VERDICT`

- [ ] F3. **Real Manual QA** — `unspecified-high`
  Start from clean state. Execute EVERY QA scenario from EVERY task — follow exact steps, capture evidence. Test cross-task integration (Android BLE → UniFFI bridge → Node → pubkey exchange). Test edge cases: BLE off, wrong address, timeout. Save to `.sisyphus/evidence/final-qa/`.
  Output: `Scenarios [N/N pass] | Integration [N/N] | Edge Cases [N tested] | VERDICT`

- [ ] F4. **Scope Fidelity Check** — `deep`
  For each task: read "What to do", read actual diff (git log/diff). Verify 1:1 — everything in spec was built (no missing), nothing beyond spec was built (no creep). Check "Must NOT do" compliance. Detect cross-task contamination: Task N touching Task M's files. Flag unaccounted changes.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

| After Task(s) | Commit Message | Key Files | Pre-commit Check |
|---------------|---------------|-----------|-----------------|
| 11 | `feat(integration): wire Android BLE to Rust bridge via ViewModel` | `FipsDroidViewModel.kt`, `MainActivity.kt` | `./gradlew assembleDebug` |
| 12 | `feat(ui): debug connection state display` | `DebugScreen.kt`, `ConnectionStateIndicator.kt` | `./gradlew assembleDebug` |
| 13 | `feat(handshake): wire pubkey exchange into node lifecycle` | `node.rs`, `bridge.rs` | `cargo test` |
| 14 | `chore: fork upstream fips for macOS CoreBluetooth support` | Fork repo | `cargo build --no-default-features` |
| 15 | `spike: verify macOS L2CAP peripheral feasibility` | `prototype/` | `swift build` |
| 16 | `feat(ble): CoreBluetoothIo + CoreBluetoothStream` | `src/transport/ble/corebluetooth.rs` | `cargo build --features corebluetooth` |
| 17 | `feat(ble): CoreBluetoothAcceptor + CoreBluetoothScanner` | `src/transport/ble/corebluetooth.rs` | `cargo build --features corebluetooth` |
| 18 | `feat(ble): feature-gate corebluetooth + integration test` | `Cargo.toml`, `src/transport/ble/mod.rs` | `cargo test --features corebluetooth` |
| 19 | `test(e2e): Android↔macOS BLE end-to-end validation` | `.sisyphus/evidence/` | N/A |
| 20 | `docs: architecture + findings + macOS setup guide` | `docs/` | N/A |

---

## Success Criteria

### Verification Commands

```bash
# FIPSDroid repo — Rust tests
cargo test --all
# Expected: test result: ok. N passed; 0 failed

# FIPSDroid repo — Android build
cd android && ./gradlew assembleDebug
# Expected: BUILD SUCCESSFUL

# Forked fips — macOS build
cd ~/src/fips && cargo build --features corebluetooth --no-default-features
# Expected: Finished dev target(s)

# Forked fips — run with CoreBluetooth
cd ~/src/fips && cargo run --features corebluetooth --no-default-features -- --ble
# Expected: "Advertising BLE on PSM 133 (0x0085)"

# E2E — Android logcat
adb logcat -s FIPSDroid:* | grep -E "(L2CAP|Noise|Heartbeat)"
# Expected: L2CAP connected → Noise handshake OK → Heartbeat sent/received
```

### Final Checklist

- [ ] All "Must Have" items implemented and verified
- [ ] All "Must NOT Have" items absent from codebase
- [ ] All Rust tests pass in fipsdroid (`cargo test --all`)
- [ ] Forked fips builds on macOS (`cargo build --features corebluetooth`)
- [ ] Android app builds and installs (`./gradlew assembleDebug` + `adb install`)
- [ ] End-to-end BLE connection demonstrated (Android ↔ Mac)
- [ ] Noise IK handshake completes (evidence in logcat + fips terminal)
- [ ] Heartbeat exchange occurs (evidence in logcat + fips terminal)
- [ ] Architecture decisions documented
- [ ] Evidence files present in `.sisyphus/evidence/`
