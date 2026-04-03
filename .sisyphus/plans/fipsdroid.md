# FIPSDroid: Android FIPS BLE Leaf Node — Technical Feasibility Plan

## TL;DR

> **Quick Summary**: Build an Android APK that connects over Bluetooth Low Energy (L2CAP CoC) to a machine running upstream `fips`, completes a Noise IK handshake using `microfips-protocol`, exchanges heartbeats, and proves the viability of an Android FIPS leaf node. The plan maximizes reuse of existing Rust crates (`microfips-protocol` + `microfips-core`) with a UniFFI bridge to Kotlin.
>
> **Deliverables**:
> - 10-section technical feasibility document (this plan, executed as implementation tasks)
> - Rust crate `fipsdroid-core` implementing BLE transport + node lifecycle
> - Android APK with minimal debug UI showing connection/handshake/heartbeat status
> - Test evidence: logcat traces proving L2CAP connection, Noise IK handshake, and heartbeat exchange
>
> **Estimated Effort**: Large (multi-week feasibility study + implementation)
> **Parallel Execution**: YES — 4 waves
> **Critical Path**: Embassy audit → Transport impl → Node integration → Android app → End-to-end test

---

## Context

### Original Request

Create a comprehensive technical plan for "fipsdroid" — an Android APK that acts as a FIPS-compatible Bluetooth leaf node, capable of peering with a machine running upstream `fips` over BLE. The first milestone is a feasibility study: prove that an Android phone can complete the minimum viable FIPS lifecycle (connect, handshake, heartbeat) with a real `fips` peer.

### Interview Summary

**Key Discussions**:
- **Scope**: Feasibility study only — not production app, not full routing, not polished UX
- **Reuse strategy**: Maximize reuse of `microfips-protocol` (protocol state machine) and `microfips-core` (crypto). Avoid creating a third divergent protocol implementation
- **Repo structure**: New standalone repo, not folded into `microfips`
- **Node role**: Leaf node only — no routing, no multi-peer, no discovery beyond single known peer
- **Architecture split**: Clean separation between Android/platform code (Kotlin) and protocol logic (Rust)

**Research Findings**:

1. **microfips-protocol** is `no_std` with embassy-async (`embassy-time`, `embassy-futures`). Has clean `Transport` trait, `Node<T,R>` state machine with Noise IK handshake, heartbeat loop, and `NodeHandler` trait. Has `std` feature gate.

2. **microfips-core** is pure `no_std` crypto (sha2, k256, chacha20poly1305, hkdf, hmac). Fully portable, no embassy dependency.

3. **Upstream fips BLE** uses L2CAP CoC (SeqPacket) via `bluer` (BlueZ, Linux-only). PSM `0x0085`. Service UUID `9c90b790-2cc5-42c0-9f87-c9cc40648f4c`. Pre-handshake pubkey exchange: `[0x00][32-byte pubkey]`. `BleIo` trait abstracts the stack.

4. **Android L2CAP CoC** available via `BluetoothSocket.createL2capChannel()` (API 29+). Known device fragmentation issues — works reliably on Pixel, less so on Samsung/other.

5. **macOS L2CAP CoC** supported via CoreBluetooth since macOS 11. Theoretically compatible but unproven with fips.

6. **Embassy std compatibility**: `embassy-time` has `std` + `generic-queue-32` features enabling host/Android usage without Embassy executor. `embassy-futures` works with any executor.

7. **Issue #47 in microfips**: Documents 4 BLE transport paths. Active L2CAP work on ESP32, partially blocked at advertising.

### Metis Review

**Identified Gaps** (addressed):
- **L2CAP role**: Defaulted to Android as client/initiator for feasibility — simpler, doesn't require advertising implementation
- **Key exchange UX**: Hardcoded keys for feasibility study — no key management complexity
- **Test devices**: Pixel as baseline, Samsung as fragmentation test (recommended, not blocking)
- **macOS vs Linux peer**: Linux first (proven path), macOS as stretch goal
- **Embassy timer capacity**: Added as pre-implementation audit task (Wave 1)
- **Threading model**: Tokio as async runtime for Rust std on Android
- **NodeHandler callbacks**: Stay in Rust, expose results via UniFFI — minimize JNI boundary crossings
- **UniFFI vs JNI**: UniFFI recommended for feasibility (better async/coroutine support, less boilerplate), JNI as documented fallback

---

## Work Objectives

### Core Objective

Prove that an Android phone can peer over BLE (L2CAP CoC) with a machine running upstream `fips`, complete a Noise IK handshake using `microfips-protocol`, exchange heartbeats, and maintain a stable connection — demonstrating a viable path toward a real Android FIPS leaf node.

### Concrete Deliverables

1. **`fipsdroid-core` Rust crate** — BLE transport implementing `microfips-protocol::Transport`, node lifecycle wrapper, UniFFI bindings
2. **`fipsdroid` Android app** — Kotlin/Jetpack Compose, minimal debug UI, BLE permissions, L2CAP connection management
3. **Test evidence** — logcat traces, screenshots, connection logs proving end-to-end lifecycle
4. **Architecture documentation** — decisions, trade-offs, and lessons learned recorded in repo

### Definition of Done

- [ ] Android app connects to upstream `fips` peer over L2CAP CoC
- [ ] Noise IK handshake completes successfully (verified by logcat)
- [ ] At least one heartbeat exchange occurs (verified by logcat)
- [ ] Connection gracefully handles timeout and invalid key scenarios
- [ ] All Rust tests pass (`cargo test`)
- [ ] Android app builds and installs on Pixel device (`./gradlew assembleDebug`)

### Must Have

- L2CAP CoC transport on Android (PSM `0x0085`)
- Noise IK handshake using `microfips-protocol` Node
- Heartbeat send/receive
- Pre-handshake pubkey exchange matching upstream fips format (`[0x00][32B pubkey]`)
- Debug UI showing connection state (disconnected → connecting → handshaking → established → heartbeat count)
- Graceful error handling for connection timeout, handshake failure, BLE unavailability
- Embassy-time configured with `std` + `generic-queue-32` features
- UniFFI bridge from Rust to Kotlin

### Must NOT Have (Guardrails)

- ❌ Multi-peer connection pool (single peer only)
- ❌ Peer discovery/scanning (hardcode peer address for feasibility)
- ❌ Routing or relay functionality (leaf node only)
- ❌ Production key management or key exchange UX (hardcoded keys)
- ❌ GATT fallback transport
- ❌ Auto-reconnection logic
- ❌ Background service / persistent connection
- ❌ Polished UI / Material Design / animations
- ❌ Support for API < 29
- ❌ iOS or cross-platform support
- ❌ Over-abstraction or premature generalization
- ❌ Excessive inline comments or JSDoc-style documentation bloat

---

## Verification Strategy

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed. No exceptions.
> Acceptance criteria requiring "user manually tests/confirms" are FORBIDDEN.

### Test Decision

- **Infrastructure exists**: NO (new project)
- **Automated tests**: YES (Tests-after) — unit tests for Rust crate, no Android instrumentation tests for feasibility
- **Framework**: `cargo test` for Rust, `./gradlew test` for Kotlin unit tests
- **Agent-Executed QA**: ALWAYS — logcat capture, adb commands, cargo test output

### QA Policy

Every task MUST include agent-executed QA scenarios. Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

- **Rust crate**: Use Bash (`cargo test`, `cargo build --target aarch64-linux-android`)
- **Android app**: Use Bash (`./gradlew assembleDebug`, `adb install`, `adb logcat`)
- **End-to-end**: Use Bash (start fips peer, install app, capture logcat, verify output)

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Start Immediately — audits + scaffolding):
├── Task 1: Embassy timer audit on microfips-protocol [quick]
├── Task 2: Rust workspace scaffolding (fipsdroid-core crate) [quick]
├── Task 3: Android project scaffolding (Gradle + Kotlin) [quick]
├── Task 4: Type definitions and error types [quick]
└── Task 5: Research Android L2CAP API + UniFFI async patterns [quick]

Wave 2 (After Wave 1 — core implementation, MAX PARALLEL):
├── Task 6: BLE transport trait implementation (depends: 1, 2, 4) [deep]
├── Task 7: Node lifecycle wrapper (depends: 2, 4) [deep]
├── Task 8: UniFFI bridge layer (depends: 2, 4, 5) [unspecified-high]
├── Task 9: Android BLE permissions + connection manager (depends: 3, 5) [unspecified-high]
└── Task 10: Cargo-ndk cross-compilation setup (depends: 2) [quick]

Wave 3 (After Wave 2 — integration):
├── Task 11: Android↔Rust integration (depends: 8, 9, 10) [deep]
├── Task 12: Debug UI - connection state display (depends: 9) [visual-engineering]
└── Task 13: Pre-handshake pubkey exchange (depends: 6, 7) [unspecified-high]

Wave 4 (After Wave 3 — end-to-end validation):
├── Task 14: End-to-end test with upstream fips (depends: 11, 12, 13) [deep]
└── Task 15: Document findings + architecture decisions (depends: 14) [writing]

Wave FINAL (After ALL tasks — 4 parallel reviews, then user okay):
├── Task F1: Plan compliance audit (oracle)
├── Task F2: Code quality review (unspecified-high)
├── Task F3: Real manual QA (unspecified-high)
└── Task F4: Scope fidelity check (deep)
-> Present results -> Get explicit user okay

Critical Path: Task 1 → Task 6 → Task 13 → Task 14 → F1-F4 → user okay
Parallel Speedup: ~60% faster than sequential
Max Concurrent: 5 (Wave 1 & 2)
```

### Dependency Matrix

| Task | Depends On | Blocks | Wave |
|------|-----------|--------|------|
| 1 | — | 6 | 1 |
| 2 | — | 6, 7, 8, 10 | 1 |
| 3 | — | 9 | 1 |
| 4 | — | 6, 7, 8 | 1 |
| 5 | — | 8, 9 | 1 |
| 6 | 1, 2, 4 | 13 | 2 |
| 7 | 2, 4 | 13 | 2 |
| 8 | 2, 4, 5 | 11 | 2 |
| 9 | 3, 5 | 11, 12 | 2 |
| 10 | 2 | 11 | 2 |
| 11 | 8, 9, 10 | 14 | 3 |
| 12 | 9 | 14 | 3 |
| 13 | 6, 7 | 14 | 3 |
| 14 | 11, 12, 13 | 15 | 4 |
| 15 | 14 | F1-F4 | 4 |

### Agent Dispatch Summary

- **Wave 1**: **5 tasks** — T1 → `quick`, T2 → `quick`, T3 → `quick`, T4 → `quick`, T5 → `quick`
- **Wave 2**: **5 tasks** — T6 → `deep`, T7 → `deep`, T8 → `unspecified-high`, T9 → `unspecified-high`, T10 → `quick`
- **Wave 3**: **3 tasks** — T11 → `deep`, T12 → `visual-engineering`, T13 → `unspecified-high`
- **Wave 4**: **2 tasks** — T14 → `deep`, T15 → `writing`
- **FINAL**: **4 tasks** — F1 → `oracle`, F2 → `unspecified-high`, F3 → `unspecified-high`, F4 → `deep`

---

## TODOs

### Wave 1: Audits + Scaffolding (Start Immediately)

- [x] 1. Embassy Timer Capacity Audit

  **What to do**:
  - Search `microfips-protocol` source for all uses of `embassy_time::Timer`, `embassy_time::Instant`, and any timer-related constructs
  - Count the maximum number of concurrent timers that could be active during a single `Node` lifecycle (handshake + steady-state)
  - Verify that the count is ≤32 (the capacity of `generic-queue-32` feature)
  - If count exceeds 32: document which timers are used and propose either `generic-queue-64` or timer pooling strategy
  - Document findings in `docs/embassy-audit.md` with exact file:line references
  - Verify that `embassy-time = { features = ["std", "generic-queue-32"] }` is sufficient configuration

  **Must NOT do**:
  - Do not modify microfips-protocol source code
  - Do not change any Cargo.toml in microfips
  - Do not add embassy to fipsdroid yet (that's Task 2)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Pure research/audit task — read files, count patterns, write findings document
  - **Skills**: []
    - No specialized skills needed — just codebase reading and documentation
  - **Skills Evaluated but Omitted**:
    - `Research`: Not needed — this is codebase analysis, not web research

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 2, 3, 4, 5)
  - **Blocks**: Task 6 (BLE transport needs confirmed timer config)
  - **Blocked By**: None (can start immediately)

  **References**:

  **Pattern References** (existing code to follow):
  - `Amperstrand/microfips :: crates/microfips-protocol/src/node.rs` — Node state machine with heartbeat timers. Look for `Timer::after()`, `Timer::at()`, `Instant::now()` calls. The heartbeat loop uses timers for send interval and receive timeout.
  - `Amperstrand/microfips :: crates/microfips-protocol/src/transport.rs` — Transport trait with timeout behavior. Check if `with_timeout()` creates embassy timers.

  **API/Type References**:
  - `embassy-time` crate docs: `Timer::after(Duration)` creates a single timer consuming one queue slot. `generic-queue-32` feature provides 32 slots.

  **External References**:
  - Embassy issue #2830: Confirms timers panic with non-Embassy wakers unless generic-queue is used
  - `embassy-time` Cargo.toml features: `std`, `generic-queue-8`, `generic-queue-16`, `generic-queue-32`, `generic-queue-64`

  **WHY Each Reference Matters**:
  - `node.rs` is where the protocol state machine lives — this is where timers are created for heartbeat intervals, handshake timeouts, etc. The executor must count worst-case concurrent timers here.
  - `transport.rs` may create additional timers for I/O timeouts. These stack on top of node.rs timers.
  - Embassy issue #2830 proves this is a real failure mode, not theoretical.

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Timer count audit produces concrete number
    Tool: Bash (ast_grep_search + manual review)
    Preconditions: microfips-protocol source accessible via GitHub API (zai-zread)
    Steps:
      1. Search for `Timer::after` pattern in microfips-protocol/src/ — count occurrences
      2. Search for `Timer::at` pattern — count occurrences
      3. Search for `with_timeout` pattern — count occurrences
      4. Analyze control flow in node.rs to determine max concurrent timers
      5. Write findings to docs/embassy-audit.md
    Expected Result: docs/embassy-audit.md exists with: timer count ≤32, file:line references for each timer, and confirmed `generic-queue-32` sufficiency
    Failure Indicators: Timer count >32, or unable to determine concurrent count from static analysis
    Evidence: .sisyphus/evidence/task-1-timer-audit.md

  Scenario: Timer count exceeds capacity (edge case)
    Tool: Bash
    Preconditions: Timer audit reveals count >32
    Steps:
      1. Document which timers exceed capacity
      2. Propose mitigation: `generic-queue-64` or timer pooling
      3. Update docs/embassy-audit.md with risk assessment
    Expected Result: If count >32, mitigation strategy documented. If ≤32, this scenario is N/A (document as "capacity sufficient")
    Evidence: .sisyphus/evidence/task-1-timer-overflow.md
  ```

  **Commit**: YES
  - Message: `docs(audit): embassy timer capacity verified`
  - Files: `docs/embassy-audit.md`
  - Pre-commit: N/A (documentation only)

- [x] 2. Rust Workspace Scaffolding

  **What to do**:
  - Create a Cargo workspace at repo root with member `crates/fipsdroid-core`
  - `fipsdroid-core` Cargo.toml with dependencies:
    - `microfips-protocol = { git = "https://github.com/Amperstrand/microfips", features = ["std"] }`
    - `microfips-core = { git = "https://github.com/Amperstrand/microfips" }`
    - `embassy-time = { version = "0.5", features = ["std", "generic-queue-32"] }`
    - `embassy-futures = "0.1"`
    - `tokio = { version = "1", features = ["rt", "sync", "macros"] }`
    - `uniffi = { version = "0.28" }`
    - `thiserror = "2"`
    - `tracing = "0.1"`
    - `rand = "0.8"`
  - Create `src/lib.rs` with module declarations: `mod transport;`, `mod node;`, `mod bridge;`, `mod error;`, `mod types;`
  - Ensure `cargo check` passes (empty modules with appropriate stubs)

  **Must NOT do**:
  - Do not implement any logic — only scaffolding
  - Do not add Android-specific build configuration (that's Task 10)
  - Do not add more than the listed dependencies
  - Do not create test files yet

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Boilerplate scaffolding — create files with minimal content, verify cargo check passes
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - `CreateCLI`: Not a CLI project

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 3, 4, 5)
  - **Blocks**: Tasks 6, 7, 8, 10
  - **Blocked By**: None

  **References**:

  **Pattern References**:
  - `Amperstrand/microfips :: Cargo.toml` (workspace root) — Follow workspace layout pattern
  - `Amperstrand/microfips :: crates/microfips-protocol/Cargo.toml` — Dependency versions for embassy-time, embassy-futures, microfips-core

  **API/Type References**:
  - `microfips-protocol` re-exports: `Transport`, `Node`, `NodeHandler`, `FrameReader`, `FrameWriter`

  **External References**:
  - UniFFI 0.28 setup guide: `https://mozilla.github.io/uniffi-rs/`
  - cargo-ndk for Android cross-compilation

  **WHY Each Reference Matters**:
  - microfips Cargo.toml shows the workspace pattern and exact version pins for embassy crates
  - microfips-protocol Cargo.toml shows which features are available (`std`)

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Workspace builds successfully
    Tool: Bash
    Preconditions: None
    Steps:
      1. Run `cargo check` in workspace root
      2. Verify output contains "Checking fipsdroid-core"
      3. Verify exit code 0
    Expected Result: `cargo check` passes with no errors
    Failure Indicators: Compilation errors, missing dependencies, feature conflicts
    Evidence: .sisyphus/evidence/task-2-cargo-check.txt

  Scenario: Dependency resolution succeeds
    Tool: Bash
    Preconditions: Network access to crates.io and GitHub
    Steps:
      1. Run `cargo tree` to verify dependency graph
      2. Verify microfips-protocol and microfips-core appear in tree
      3. Verify embassy-time shows "std" and "generic-queue-32" features
    Expected Result: All dependencies resolve, no version conflicts
    Failure Indicators: Dependency conflicts, unresolvable git refs
    Evidence: .sisyphus/evidence/task-2-cargo-tree.txt
  ```

  **Commit**: YES (groups with Task 4)
  - Message: `feat(core): scaffold fipsdroid-core crate with types`
  - Files: `Cargo.toml`, `crates/fipsdroid-core/Cargo.toml`, `crates/fipsdroid-core/src/lib.rs`
  - Pre-commit: `cargo check`

- [x] 3. Android Project Scaffolding

  **What to do**:
  - Create Android project structure at `android/` directory:
    - `android/app/build.gradle.kts` — Android app module, minSdk 29, targetSdk 34, Kotlin 1.9+
    - `android/build.gradle.kts` — Root build file with AGP + Kotlin plugin
    - `android/settings.gradle.kts` — Project settings
    - `android/gradle.properties` — Android properties
    - `android/app/src/main/AndroidManifest.xml` — with BLE permissions: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION`
    - `android/app/src/main/java/com/fipsdroid/MainActivity.kt` — Empty Jetpack Compose activity
    - `android/app/src/main/java/com/fipsdroid/FipsDroidApp.kt` — Application class
  - Use Jetpack Compose for UI (not XML views)
  - Ensure `./gradlew assembleDebug` passes (empty app that launches)

  **Must NOT do**:
  - Do not implement any BLE logic (that's Task 9)
  - Do not add UniFFI/Rust integration (that's Task 10/11)
  - Do not design UI components (that's Task 12)
  - Do not add any external dependencies beyond Compose basics
  - Do not support API < 29

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Standard Android project scaffolding — well-known boilerplate
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - `visual-engineering`: Not doing UI yet, just empty scaffolding

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2, 4, 5)
  - **Blocks**: Task 9
  - **Blocked By**: None

  **References**:

  **External References**:
  - Android BLE permissions (API 31+): `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` — runtime permission model
  - Jetpack Compose setup: `androidx.compose.material3`, `androidx.activity:activity-compose`
  - Android Gradle Plugin 8.x with Kotlin DSL

  **WHY Each Reference Matters**:
  - BLE permissions are complex on Android 12+ (API 31) — need both `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` with runtime grants. Getting this right in the manifest early prevents debugging later.
  - Compose is the modern Android UI toolkit — using it from the start avoids migration later.

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Android project builds successfully
    Tool: Bash
    Preconditions: Android SDK installed, ANDROID_HOME set
    Steps:
      1. Run `./gradlew assembleDebug` in android/ directory
      2. Verify output contains "BUILD SUCCESSFUL"
      3. Verify APK exists at android/app/build/outputs/apk/debug/app-debug.apk
    Expected Result: APK builds successfully
    Failure Indicators: Gradle errors, missing SDK, compilation failures
    Evidence: .sisyphus/evidence/task-3-gradle-build.txt

  Scenario: Manifest declares required permissions
    Tool: Bash (grep)
    Preconditions: AndroidManifest.xml exists
    Steps:
      1. Search AndroidManifest.xml for BLUETOOTH_CONNECT permission
      2. Search for BLUETOOTH_SCAN permission
      3. Search for ACCESS_FINE_LOCATION permission
      4. Verify minSdkVersion is 29 in build.gradle.kts
    Expected Result: All 3 permissions declared, minSdk = 29
    Failure Indicators: Missing permissions, wrong minSdk
    Evidence: .sisyphus/evidence/task-3-manifest-check.txt
  ```

  **Commit**: YES
  - Message: `feat(android): scaffold Android project with BLE permissions`
  - Files: `android/` directory
  - Pre-commit: `./gradlew assembleDebug`

- [x] 4. Type Definitions and Error Types

  **What to do**:
  - Create `crates/fipsdroid-core/src/types.rs`:
    - `ConnectionState` enum: `Disconnected`, `Connecting`, `Connected`, `Handshaking`, `Established`, `Disconnecting`, `Error(String)`
    - `PeerInfo` struct: `address: String`, `pubkey: [u8; 32]`
    - `HeartbeatStatus` struct: `sent_count: u64`, `received_count: u64`, `last_received: Option<u64>` (timestamp)
    - `NodeConfig` struct: `peer_address: String`, `local_keypair: Keypair`, `psm: u16` (default 0x0085)
  - Create `crates/fipsdroid-core/src/error.rs`:
    - `FipsDroidError` enum using `thiserror`: `BleUnavailable`, `ConnectionFailed(String)`, `HandshakeFailed(String)`, `Timeout`, `TransportClosed`, `InvalidPeerKey`, `ProtocolError(String)`
  - All types should derive `Debug, Clone` minimum
  - Types intended for UniFFI should be marked with `#[derive(uniffi::Enum)]` or `#[derive(uniffi::Record)]` as appropriate

  **Must NOT do**:
  - Do not implement any methods on these types (just data definitions)
  - Do not add serialization (serde) — not needed for feasibility
  - Do not create more types than listed — resist scope creep

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Pure type definitions — small file, clear spec, no logic
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2, 3, 5)
  - **Blocks**: Tasks 6, 7, 8
  - **Blocked By**: None (but commits with Task 2)

  **References**:

  **Pattern References**:
  - `Amperstrand/microfips :: crates/microfips-protocol/src/node.rs` — `NodeState` enum pattern (how microfips models connection states)
  - `jmcorgan/fips :: src/transport/ble/mod.rs` — `BleAddr` and connection state types

  **API/Type References**:
  - `microfips-core` types: `Keypair`, `PublicKey` — use these for key-related fields
  - UniFFI derive macros: `uniffi::Enum`, `uniffi::Record`, `uniffi::Object`

  **WHY Each Reference Matters**:
  - microfips `NodeState` shows the protocol's own state model — our `ConnectionState` should map cleanly to it
  - fips `BleAddr` shows how upstream addresses BLE peers — our `PeerInfo` should be compatible
  - UniFFI derives determine which types cross the FFI boundary — getting this right early prevents refactoring

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Types compile and are usable
    Tool: Bash
    Preconditions: Task 2 complete (workspace exists)
    Steps:
      1. Run `cargo check` in workspace root
      2. Run `cargo doc --no-deps` to verify documentation compiles
    Expected Result: No compilation errors, docs generate
    Failure Indicators: Type errors, missing derives, UniFFI derive issues
    Evidence: .sisyphus/evidence/task-4-cargo-check.txt

  Scenario: Error type covers all expected failure modes
    Tool: Bash (grep)
    Preconditions: error.rs exists
    Steps:
      1. Verify BleUnavailable variant exists
      2. Verify ConnectionFailed variant exists
      3. Verify HandshakeFailed variant exists
      4. Verify Timeout variant exists
      5. Verify all variants implement Display (via thiserror)
    Expected Result: All 7 error variants present with Display impl
    Failure Indicators: Missing variants, thiserror derive errors
    Evidence: .sisyphus/evidence/task-4-error-types.txt
  ```

  **Commit**: YES (groups with Task 2)
  - Message: `feat(core): scaffold fipsdroid-core crate with types`
  - Files: `crates/fipsdroid-core/src/types.rs`, `crates/fipsdroid-core/src/error.rs`
  - Pre-commit: `cargo check`

- [x] 5. Research: Android L2CAP API + UniFFI Async Patterns

  **What to do**:
  - Research Android `BluetoothSocket.createL2capChannel()` API:
    - Exact method signatures and parameters
    - How to specify PSM (0x0085)
    - Threading requirements (must be off main thread)
    - Known device fragmentation issues (Samsung, Huawei, etc.)
    - Error handling patterns (IOException types)
    - Connection timeout behavior
  - Research UniFFI async/callback patterns:
    - How to expose Rust async functions as Kotlin suspend functions
    - `uniffi::export(async_runtime = "tokio")` configuration
    - Callback interfaces for streaming data (heartbeat updates)
    - Error propagation across FFI boundary
  - Document findings in `docs/research-l2cap.md` and `docs/research-uniffi.md`
  - Include code snippets for both Kotlin (L2CAP) and Rust (UniFFI) sides

  **Must NOT do**:
  - Do not write implementation code (just research documents)
  - Do not commit to a specific UniFFI version without verifying Android compatibility
  - Do not research GATT fallback (explicitly out of scope)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Pure research task — web search, documentation reading, writing findings
  - **Skills**: [`Research`]
    - `Research`: Needed for comprehensive web research on Android BLE API specifics and UniFFI patterns
  - **Skills Evaluated but Omitted**:
    - `Browser`: Not needed — documentation is text-based

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2, 3, 4)
  - **Blocks**: Tasks 8, 9
  - **Blocked By**: None

  **References**:

  **External References**:
  - Android `BluetoothSocket` API reference: `createL2capChannel(int psm)` and `createInsecureL2capChannel(int psm)`
  - UniFFI handbook: `https://mozilla.github.io/uniffi-rs/`
  - UniFFI async support: `https://mozilla.github.io/uniffi-rs/proc_macro/async.html`
  - `cargo-ndk` for Android cross-compilation: `https://github.com/nickelc/cargo-ndk`
  - Letterbox app (UniFFI + Android example): production reference for UniFFI on Android
  - `android-ble-rs` crate: JNI-based BLE alternative for comparison

  **WHY Each Reference Matters**:
  - Android L2CAP API has critical device fragmentation issues — research must document which devices work and which don't
  - UniFFI async patterns determine how the Rust event loop integrates with Kotlin coroutines — wrong choice here means architectural rework
  - Letterbox proves UniFFI + cargo-ndk works in production on Android — extract their build configuration

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: L2CAP research covers critical topics
    Tool: Bash (grep)
    Preconditions: docs/research-l2cap.md exists
    Steps:
      1. Verify document mentions "createL2capChannel" or "createInsecureL2capChannel"
      2. Verify document mentions PSM or "0x0085"
      3. Verify document mentions device fragmentation or "Samsung"
      4. Verify document includes Kotlin code snippet
    Expected Result: All 4 topics covered with code examples
    Failure Indicators: Missing topics, no code snippets, vague recommendations
    Evidence: .sisyphus/evidence/task-5-l2cap-research.txt

  Scenario: UniFFI research covers async patterns
    Tool: Bash (grep)
    Preconditions: docs/research-uniffi.md exists
    Steps:
      1. Verify document mentions "async_runtime" or "tokio"
      2. Verify document mentions "suspend" (Kotlin suspend functions)
      3. Verify document includes Rust code snippet with #[uniffi::export]
      4. Verify document mentions callback or streaming pattern
    Expected Result: All 4 topics covered with code examples on both Rust and Kotlin sides
    Failure Indicators: Missing async patterns, no Kotlin examples
    Evidence: .sisyphus/evidence/task-5-uniffi-research.txt
  ```

  **Commit**: YES
  - Message: `docs(research): L2CAP API + UniFFI async patterns`
  - Files: `docs/research-l2cap.md`, `docs/research-uniffi.md`
  - Pre-commit: N/A (documentation only)

### Wave 2: Core Implementation (After Wave 1)

- [x] 6. BLE Transport Trait Implementation

  **What to do**:
  - Implement `microfips_protocol::Transport` trait for Android BLE L2CAP:
    - Create `crates/fipsdroid-core/src/transport/mod.rs` — module structure
    - Create `crates/fipsdroid-core/src/transport/ble.rs` — `BleTransport` struct implementing `Transport`
    - `Transport::read()` — read from L2CAP socket (via channel from Kotlin side)
    - `Transport::write()` — write to L2CAP socket (via channel to Kotlin side)
    - `Transport::close()` — signal close to Kotlin side
  - The BLE transport does NOT directly call Android APIs — it communicates with Kotlin through channels:
    - `tokio::sync::mpsc` channel for incoming bytes (Kotlin → Rust)
    - `tokio::sync::mpsc` channel for outgoing bytes (Rust → Kotlin)
    - This clean boundary means the Transport impl is testable without Android
  - Implement pre-handshake pubkey exchange matching upstream fips format:
    - Send `[0x00][32-byte local pubkey]` before handshake
    - Receive `[0x00][32-byte remote pubkey]` from peer
    - Validate received pubkey format
  - Create `crates/fipsdroid-core/src/transport/mock.rs` — `MockBleTransport` for testing:
    - Backed by `tokio::sync::mpsc` channels
    - Allows injecting test data and verifying writes
  - Write unit tests:
    - Test read/write through mock channels
    - Test pubkey exchange format (valid and invalid)
    - Test close behavior
    - Test timeout behavior

  **Must NOT do**:
  - Do not call any Android/JNI APIs from Rust — this layer is platform-agnostic
  - Do not implement connection establishment (that's the Kotlin side)
  - Do not implement reconnection logic
  - Do not implement framing on top of L2CAP (L2CAP SeqPacket preserves message boundaries)
  - Do not handle more than one connection

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Core protocol integration — must understand microfips Transport trait contract, async patterns, and channel-based architecture. Requires careful design decisions.
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - `Research`: Research already done in Task 5

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 7, 8, 9, 10)
  - **Blocks**: Task 13 (pre-handshake pubkey exchange)
  - **Blocked By**: Tasks 1 (timer audit confirms config), 2 (workspace exists), 4 (error types)

  **References**:

  **Pattern References**:
  - `Amperstrand/microfips :: crates/microfips-protocol/src/transport.rs` — The `Transport` trait definition. CRITICAL: understand exact method signatures (`async fn read(&mut self, buf: &mut [u8]) -> Result<usize, Self::Error>`, `async fn write(&mut self, data: &[u8]) -> Result<(), Self::Error>`, `async fn close(&mut self) -> Result<(), Self::Error>`). Also study `MockTransport` and `ChannelTransport` as implementation examples.
  - `Amperstrand/microfips :: crates/microfips-protocol/src/framing.rs` — Frame format: compact length prefix + MAX_FRAME=1500. Understand how framing interacts with transport reads.
  - `jmcorgan/fips :: src/transport/ble/mod.rs` — Upstream BLE transport implementation. Study: pre-handshake pubkey exchange format (`[0x00][32B pubkey]`), how L2CAP SeqPacket is used (no additional framing needed), connection lifecycle.
  - `jmcorgan/fips :: src/transport/ble/io.rs` — `BleIo` trait and `BleStream` trait — the abstraction layer over BlueZ. Our channel-based approach serves the same purpose.

  **API/Type References**:
  - `microfips_protocol::Transport` — The trait we must implement. Error type must be our `FipsDroidError`.
  - `tokio::sync::mpsc::{Sender, Receiver}` — Channel types for Kotlin↔Rust communication.

  **WHY Each Reference Matters**:
  - `transport.rs` is THE contract we must satisfy — wrong method signatures = compile error. `MockTransport` shows the exact pattern for channel-based testing.
  - `framing.rs` — microfips-protocol handles framing internally. We must NOT add our own framing layer on top.
  - `fips/ble/mod.rs` — shows the exact pubkey exchange format we must match for interoperability. Byte-level compatibility is critical.
  - `fips/ble/io.rs` — shows how upstream abstracts the BLE stack. Our channel approach is analogous.

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Mock transport passes read/write roundtrip test
    Tool: Bash
    Preconditions: Task 2 complete, workspace builds
    Steps:
      1. Run `cargo test -p fipsdroid-core transport::tests` 
      2. Verify test "test_read_write_roundtrip" passes — sends bytes through mock, receives same bytes
      3. Verify test "test_close_signals_eof" passes — close on sender causes read to return 0/error
    Expected Result: All transport tests pass
    Failure Indicators: Channel deadlock, wrong byte ordering, close not propagating
    Evidence: .sisyphus/evidence/task-6-transport-tests.txt

  Scenario: Pubkey exchange format matches upstream fips
    Tool: Bash
    Preconditions: Transport implementation exists
    Steps:
      1. Run `cargo test -p fipsdroid-core transport::tests::test_pubkey_exchange`
      2. Verify test sends `[0x00][32 random bytes]` and receives same format
      3. Verify test rejects messages not starting with 0x00
      4. Verify test rejects messages with wrong length (not 33 bytes)
    Expected Result: Pubkey exchange matches fips wire format exactly
    Failure Indicators: Wrong prefix byte, wrong length, missing validation
    Evidence: .sisyphus/evidence/task-6-pubkey-exchange.txt
  ```

  **Commit**: YES
  - Message: `feat(transport): BLE L2CAP transport with channel bridge`
  - Files: `crates/fipsdroid-core/src/transport/mod.rs`, `crates/fipsdroid-core/src/transport/ble.rs`, `crates/fipsdroid-core/src/transport/mock.rs`
  - Pre-commit: `cargo test -p fipsdroid-core`

- [x] 7. Node Lifecycle Wrapper

  **What to do**:
  - Create `crates/fipsdroid-core/src/node.rs` — wrapper around `microfips_protocol::Node`:
    - `FipsDroidNode` struct wrapping `Node<BleTransport, OsRng>`
    - `new(config: NodeConfig, transport: BleTransport) -> Result<Self>` — constructor
    - `run(&mut self) -> Result<()>` — starts the node's main loop (handshake → steady state)
    - Connection state change callbacks via a channel (sends `ConnectionState` updates)
    - Heartbeat status tracking (count sent/received)
  - Implement `NodeHandler` trait for our use case:
    - `on_event()` — map protocol events to `ConnectionState` changes, send via channel
    - `on_message()` — handle incoming application messages (log for now)
    - `poll_at()` — delegate to protocol's heartbeat schedule
    - `on_tick()` — handle heartbeat timer events
  - Write unit tests using `MockBleTransport`:
    - Test node creation with valid config
    - Test state transitions: Connecting → Handshaking → Established
    - Test heartbeat counting

  **Must NOT do**:
  - Do not implement routing or message forwarding
  - Do not handle multiple peers
  - Do not implement reconnection — if connection drops, node stops
  - Do not modify microfips-protocol's Node behavior
  - Do not add persistent storage

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Core protocol integration — requires understanding microfips Node state machine, NodeHandler callback semantics, and async lifecycle management. Design decisions affect all downstream tasks.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 6, 8, 9, 10)
  - **Blocks**: Task 13 (pubkey exchange uses node)
  - **Blocked By**: Tasks 2 (workspace), 4 (types)

  **References**:

  **Pattern References**:
  - `Amperstrand/microfips :: crates/microfips-protocol/src/node.rs` — THE reference for how Node works. Study: constructor pattern (`Node::new()`), `run()` loop structure, `NodeHandler` trait callbacks (`on_event`, `on_message`, `poll_at`, `on_tick`), handshake flow (Noise IK initiator), steady-state heartbeat loop. Pay special attention to the test at the bottom — `test_handshake_full` shows how to set up a mock responder for testing.
  - `Amperstrand/microfips :: crates/microfips-protocol/src/fsp_handler.rs` — FSP handler implementing `NodeHandler`. Shows the pattern for implementing the handler trait with event dispatch.

  **API/Type References**:
  - `microfips_protocol::NodeHandler` trait — callbacks we must implement. Each callback has specific semantics.
  - `microfips_protocol::Node<T: Transport, R: CryptoRng>` — generic over transport and RNG. We instantiate with `BleTransport` and `rand::rngs::OsRng`.
  - `ConnectionState` from Task 4 — our state enum sent via channel.

  **WHY Each Reference Matters**:
  - `node.rs` is the most complex file we depend on. Our wrapper must understand its lifecycle guarantees: what happens on handshake failure, how heartbeat timeouts work, when `on_event` fires vs `on_message`.
  - `fsp_handler.rs` is a concrete example of implementing `NodeHandler` — copy this pattern.

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Node wrapper compiles and creates successfully
    Tool: Bash
    Preconditions: Tasks 2, 4, 6 complete
    Steps:
      1. Run `cargo test -p fipsdroid-core node::tests::test_node_creation`
      2. Verify FipsDroidNode::new() returns Ok with valid config
    Expected Result: Node created without error
    Failure Indicators: Type mismatches with microfips-protocol, missing trait impls
    Evidence: .sisyphus/evidence/task-7-node-creation.txt

  Scenario: State transitions emit correct ConnectionState sequence
    Tool: Bash
    Preconditions: MockBleTransport available from Task 6
    Steps:
      1. Run `cargo test -p fipsdroid-core node::tests::test_state_transitions`
      2. Verify state channel receives: Connecting → Handshaking → Established (in order)
      3. Verify heartbeat count increments after Established
    Expected Result: Correct state sequence, heartbeat counting works
    Failure Indicators: Missing states, wrong order, channel deadlock
    Evidence: .sisyphus/evidence/task-7-state-transitions.txt
  ```

  **Commit**: YES
  - Message: `feat(node): node lifecycle wrapper with state tracking`
  - Files: `crates/fipsdroid-core/src/node.rs`
  - Pre-commit: `cargo test -p fipsdroid-core`

- [ ] 8. UniFFI Bridge Layer

  **What to do**:
  - Create `crates/fipsdroid-core/src/bridge.rs` — UniFFI-exported interface:
    - `FipsDroidBridge` object (UniFFI `Object`):
      - `new(peer_address: String, peer_pubkey: Vec<u8>, local_privkey: Vec<u8>) -> Result<Self>`
      - `start(callback: Box<dyn FipsDroidCallback>) -> Result<()>` — starts node on tokio runtime
      - `stop() -> Result<()>` — signals shutdown
      - `get_state() -> ConnectionState` — current connection state
      - `get_heartbeat_status() -> HeartbeatStatus` — current heartbeat counts
    - `FipsDroidCallback` trait (UniFFI callback interface):
      - `on_state_changed(state: ConnectionState)` — called on state transitions
      - `on_heartbeat(status: HeartbeatStatus)` — called on heartbeat events
      - `on_error(error: String)` — called on fatal errors
  - Create UniFFI UDL file or use proc-macro approach:
    - Export all types from Task 4 across FFI boundary
    - Export `FipsDroidBridge` and `FipsDroidCallback`
  - The bridge holds a `tokio::runtime::Runtime` and spawns the node task on it
  - Write tests verifying the bridge can be created and state queried

  **Must NOT do**:
  - Do not expose raw Transport or Node types via FFI — only the bridge facade
  - Do not handle BLE connection from Rust side — bridge receives byte channels from Kotlin
  - Do not add complex configuration — hardcode reasonable defaults
  - Do not implement cleanup/lifecycle beyond basic start/stop

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: FFI boundary design requires careful thought about type marshalling, thread safety, and lifecycle management. Not "deep" because UniFFI handles most complexity.
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - `Research`: Research done in Task 5

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 6, 7, 9, 10)
  - **Blocks**: Task 11 (Android integration uses bridge)
  - **Blocked By**: Tasks 2 (workspace), 4 (types), 5 (UniFFI research)

  **References**:

  **Pattern References**:
  - `docs/research-uniffi.md` (from Task 5) — UniFFI async patterns, callback interface design, error propagation

  **API/Type References**:
  - `ConnectionState`, `HeartbeatStatus`, `NodeConfig`, `FipsDroidError` from Task 4
  - UniFFI proc-macro: `#[uniffi::export]`, `#[uniffi::Object]`, `#[uniffi::export(callback_interface)]`
  - `tokio::runtime::Runtime` — for hosting async node lifecycle from synchronous FFI calls

  **External References**:
  - UniFFI handbook: `https://mozilla.github.io/uniffi-rs/proc_macro/index.html`
  - UniFFI async exports: `https://mozilla.github.io/uniffi-rs/proc_macro/async.html`
  - UniFFI callback interfaces: `https://mozilla.github.io/uniffi-rs/proc_macro/callback_interfaces.html`

  **WHY Each Reference Matters**:
  - Task 5 research documents are THE reference for how to structure FFI — follow their recommendations exactly
  - UniFFI proc-macros determine how Kotlin sees our API — wrong attribute = wrong generated code
  - Tokio runtime management at FFI boundary is a known complexity — must hold `Runtime` in `FipsDroidBridge` and use `runtime.spawn()` correctly

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Bridge creates and reports initial state
    Tool: Bash
    Preconditions: Tasks 2, 4 complete
    Steps:
      1. Run `cargo test -p fipsdroid-core bridge::tests::test_bridge_creation`
      2. Verify FipsDroidBridge::new() returns Ok
      3. Verify get_state() returns ConnectionState::Disconnected
    Expected Result: Bridge initializes with Disconnected state
    Failure Indicators: UniFFI derive errors, tokio runtime creation failure
    Evidence: .sisyphus/evidence/task-8-bridge-creation.txt

  Scenario: UniFFI generates Kotlin bindings
    Tool: Bash
    Preconditions: UniFFI configuration complete
    Steps:
      1. Run UniFFI bindgen to generate Kotlin sources
      2. Verify generated .kt files contain FipsDroidBridge class
      3. Verify generated .kt files contain FipsDroidCallback interface
      4. Verify ConnectionState enum is present in generated code
    Expected Result: Kotlin bindings compile and contain all expected types
    Failure Indicators: Missing types, wrong method signatures, unsupported types across FFI
    Evidence: .sisyphus/evidence/task-8-uniffi-bindings.txt
  ```

  **Commit**: YES
  - Message: `feat(bridge): UniFFI bridge layer with callback interface`
  - Files: `crates/fipsdroid-core/src/bridge.rs`, UniFFI config files
  - Pre-commit: `cargo test -p fipsdroid-core`

- [x] 9. Android BLE Permissions + Connection Manager

  **What to do**:
  - Create `android/app/src/main/java/com/fipsdroid/ble/BleConnectionManager.kt`:
    - Runtime permission request flow for `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION`
    - `connect(address: String, psm: Int)` — creates L2CAP socket via `BluetoothDevice.createInsecureL2capChannel(psm)`
    - Reads from L2CAP socket on IO thread, forwards bytes to Rust bridge via callback
    - Writes bytes from Rust bridge to L2CAP socket
    - Connection state management (disconnected/connecting/connected/error)
    - Error handling: timeout (10s), IOException, BLE unavailable, permission denied
  - Create `android/app/src/main/java/com/fipsdroid/ble/BlePermissions.kt`:
    - Composable permission request UI using Accompanist or manual ActivityResultContracts
    - Check BLE availability and adapter state
  - Use `createInsecureL2capChannel()` (not `createL2capChannel()`) for feasibility — avoids pairing complexity
  - All BLE operations must run on background thread (Dispatchers.IO)

  **Must NOT do**:
  - Do not implement BLE scanning/discovery (hardcode peer address)
  - Do not implement pairing/bonding
  - Do not implement GATT services
  - Do not handle multiple connections
  - Do not implement auto-reconnection
  - Do not target API < 29

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Android BLE API has device fragmentation pitfalls and complex permission model. Requires careful threading and error handling.
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - `Browser`: Not a web task

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 6, 7, 8, 10)
  - **Blocks**: Tasks 11 (integration), 12 (UI)
  - **Blocked By**: Tasks 3 (Android scaffold), 5 (L2CAP research)

  **References**:

  **Pattern References**:
  - `docs/research-l2cap.md` (from Task 5) — Android L2CAP API specifics, device fragmentation notes, code snippets

  **API/Type References**:
  - `android.bluetooth.BluetoothSocket` — L2CAP socket
  - `android.bluetooth.BluetoothDevice.createInsecureL2capChannel(int psm)` — creates L2CAP CoC socket
  - `android.bluetooth.BluetoothAdapter` — check BLE availability
  - `android.Manifest.permission.BLUETOOTH_CONNECT` — required permission (API 31+)

  **External References**:
  - Android BLE L2CAP documentation
  - Android runtime permissions guide (API 31+ changes)

  **WHY Each Reference Matters**:
  - Task 5 research doc is THE guide for which API to use and which devices are problematic
  - `createInsecureL2capChannel` vs `createL2capChannel` — insecure avoids pairing, critical for feasibility speed
  - Permission model changed significantly at API 31 — must handle both pre-31 and post-31 paths

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: BLE permission check and request flow
    Tool: Bash (adb)
    Preconditions: App installed on device, BLE permissions not yet granted
    Steps:
      1. Launch app: `adb shell am start -n com.fipsdroid/.MainActivity`
      2. Verify permission dialog appears (screenshot or logcat)
      3. Grant permissions via: `adb shell pm grant com.fipsdroid android.permission.BLUETOOTH_CONNECT`
      4. Verify logcat shows "BLE permissions granted"
    Expected Result: Permission flow works, app proceeds after grant
    Failure Indicators: Crash on permission deny, no permission dialog, wrong permissions requested
    Evidence: .sisyphus/evidence/task-9-ble-permissions.txt

  Scenario: BLE unavailable handling
    Tool: Bash (adb)
    Preconditions: BLE adapter disabled on device
    Steps:
      1. Disable Bluetooth: `adb shell settings put global bluetooth_on 0`
      2. Launch app
      3. Verify logcat shows "BLE unavailable" error, no crash
      4. Re-enable Bluetooth: `adb shell settings put global bluetooth_on 1`
    Expected Result: Graceful error message, no crash
    Failure Indicators: App crash, unhandled exception, blank screen
    Evidence: .sisyphus/evidence/task-9-ble-unavailable.txt
  ```

  **Commit**: YES
  - Message: `feat(android): BLE L2CAP connection manager with permissions`
  - Files: `android/app/src/main/java/com/fipsdroid/ble/BleConnectionManager.kt`, `android/app/src/main/java/com/fipsdroid/ble/BlePermissions.kt`
  - Pre-commit: `./gradlew test`

- [x] 10. Cargo-ndk Cross-Compilation Setup

  **What to do**:
  - Install and configure `cargo-ndk` for Android cross-compilation:
    - Add `.cargo/config.toml` with Android target configuration:
      - `aarch64-linux-android` (arm64, primary)
      - `armv7-linux-androideabi` (arm32, secondary)
      - `x86_64-linux-android` (emulator)
    - Set linker paths to Android NDK
  - Create build script (`build-android.sh` or `Makefile`):
    - Compiles `fipsdroid-core` for all Android targets
    - Runs UniFFI bindgen to generate Kotlin sources
    - Copies `.so` files to `android/app/src/main/jniLibs/{abi}/`
    - Copies generated Kotlin bindings to `android/app/src/main/java/`
  - Configure Android project to load native library:
    - Add `System.loadLibrary("fipsdroid_core")` in Application class
    - Add jniLibs source set in build.gradle.kts
  - Verify cross-compilation succeeds with `cargo ndk -t aarch64-linux-android build`

  **Must NOT do**:
  - Do not set up CI/CD pipeline
  - Do not optimize build for size (no LTO, no strip — that's for production)
  - Do not support iOS or other non-Android targets
  - Do not add more than 3 Android targets (arm64, arm32, x86_64)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Build system configuration — known patterns, no design decisions, just correct config
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 6, 7, 8, 9)
  - **Blocks**: Task 11 (Android integration requires compiled .so)
  - **Blocked By**: Task 2 (workspace must exist)

  **References**:

  **External References**:
  - `cargo-ndk` documentation: `https://github.com/nickelc/cargo-ndk`
  - Android NDK installation: `sdkmanager "ndk;26.1.10909125"`
  - UniFFI bindgen CLI: `uniffi-bindgen generate`

  **WHY Each Reference Matters**:
  - cargo-ndk simplifies cross-compilation — handles NDK toolchain selection automatically
  - jniLibs directory structure must exactly match Android's expected layout (`jniLibs/arm64-v8a/`, `jniLibs/armeabi-v7a/`, `jniLibs/x86_64/`)
  - UniFFI bindgen must run after compilation to generate Kotlin sources from the compiled dylib

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Cross-compilation produces Android .so
    Tool: Bash
    Preconditions: Android NDK installed, Task 2 complete
    Steps:
      1. Run `cargo ndk -t aarch64-linux-android build`
      2. Verify .so file exists at target path
      3. Run `file` command on .so to verify it's ARM64 ELF
    Expected Result: .so file is valid aarch64 ELF shared library
    Failure Indicators: Linker errors, missing NDK, wrong architecture
    Evidence: .sisyphus/evidence/task-10-cross-compile.txt

  Scenario: Build script produces complete Android artifacts
    Tool: Bash
    Preconditions: Build script exists
    Steps:
      1. Run build script (e.g., `./build-android.sh`)
      2. Verify .so files exist in android/app/src/main/jniLibs/arm64-v8a/
      3. Verify generated Kotlin bindings exist in android/app/src/main/java/
      4. Run `./gradlew assembleDebug` to verify everything links
    Expected Result: Full build pipeline works end-to-end
    Failure Indicators: Missing .so, missing bindings, gradle link errors
    Evidence: .sisyphus/evidence/task-10-build-pipeline.txt
  ```

  **Commit**: YES
  - Message: `build(ndk): cargo-ndk cross-compilation + build script`
  - Files: `.cargo/config.toml`, `build-android.sh` or `Makefile`, Android jniLibs setup
  - Pre-commit: `cargo ndk -t aarch64-linux-android build`

### Wave 3: Integration (After Wave 2)

- [ ] 11. Android↔Rust Integration

  **What to do**:
  - Wire up the Kotlin BLE connection manager (Task 9) to the Rust UniFFI bridge (Task 8):
    - In `MainActivity.kt` or a `ViewModel`:
      - Create `FipsDroidBridge` instance with hardcoded peer config
      - Implement `FipsDroidCallback` interface to receive state changes and heartbeats
      - On "Connect" button tap: start BLE connection → when L2CAP socket connected, pass byte streams to bridge → call `bridge.start(callback)`
    - Create `android/app/src/main/java/com/fipsdroid/FipsDroidViewModel.kt`:
      - Holds `FipsDroidBridge` instance
      - Exposes `connectionState: StateFlow<ConnectionState>` for UI
      - Exposes `heartbeatStatus: StateFlow<HeartbeatStatus>` for UI
      - `connect()` / `disconnect()` methods
    - Bridge the byte flow:
      - BleConnectionManager reads from L2CAP socket → feeds bytes to Rust via bridge method
      - Rust writes bytes → BleConnectionManager writes to L2CAP socket
    - Handle lifecycle: Activity stop → bridge.stop() → BLE disconnect
  - Verify the integration compiles: `./gradlew assembleDebug`

  **Must NOT do**:
  - Do not implement background service (foreground activity only)
  - Do not persist connection state across app restarts
  - Do not handle configuration changes (rotation) beyond basic ViewModel survival
  - Do not add dependency injection framework (manual construction is fine)

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Integration layer bridging Android lifecycle, BLE I/O, and Rust FFI. Threading model must be correct (main thread for UI, IO dispatcher for BLE, tokio for Rust). Subtle bugs arise from lifecycle mismatches.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 12, 13)
  - **Blocks**: Task 14 (end-to-end test)
  - **Blocked By**: Tasks 8 (UniFFI bridge), 9 (BLE manager), 10 (cross-compilation)

  **References**:

  **Pattern References**:
  - Task 8 output: `FipsDroidBridge` API and `FipsDroidCallback` interface — the Rust-side contract
  - Task 9 output: `BleConnectionManager` — the Android-side BLE abstraction
  - Task 10 output: Build pipeline that produces .so + Kotlin bindings

  **API/Type References**:
  - `kotlinx.coroutines.flow.StateFlow` — for reactive UI state
  - `androidx.lifecycle.ViewModel` — for surviving configuration changes
  - `kotlinx.coroutines.Dispatchers.IO` — for BLE operations

  **WHY Each Reference Matters**:
  - Tasks 8, 9, 10 outputs define both sides of the bridge — this task connects them
  - ViewModel + StateFlow is the standard Compose state management — ensures UI updates correctly
  - Threading model must be right: UI on Main, BLE on IO, Rust on its own tokio runtime

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: App builds with native library integration
    Tool: Bash
    Preconditions: Tasks 8, 9, 10 complete
    Steps:
      1. Run build script to produce .so + bindings
      2. Run `./gradlew assembleDebug`
      3. Verify APK contains libfipsdroid_core.so: `unzip -l app-debug.apk | grep .so`
      4. Install and launch: `adb install -r app-debug.apk && adb shell am start -n com.fipsdroid/.MainActivity`
      5. Verify no crash in logcat: `adb logcat -s FIPSDroid:* | head -10`
    Expected Result: App launches, loads native library, shows initial UI without crash
    Failure Indicators: UnsatisfiedLinkError, native crash, missing .so in APK
    Evidence: .sisyphus/evidence/task-11-integration-launch.txt

  Scenario: ViewModel initializes bridge correctly
    Tool: Bash (adb logcat)
    Preconditions: App installed
    Steps:
      1. Launch app
      2. Verify logcat shows "FipsDroidBridge initialized" or similar
      3. Verify initial state is "Disconnected"
    Expected Result: Bridge creates successfully, state is Disconnected
    Failure Indicators: FFI error, panic in Rust, wrong initial state
    Evidence: .sisyphus/evidence/task-11-viewmodel-init.txt
  ```

  **Commit**: YES
  - Message: `feat(integration): wire Android BLE to Rust bridge`
  - Files: `android/app/src/main/java/com/fipsdroid/FipsDroidViewModel.kt`, updated `MainActivity.kt`
  - Pre-commit: `./gradlew assembleDebug`

- [ ] 12. Debug UI — Connection State Display

  **What to do**:
  - Create minimal Jetpack Compose debug UI in `android/app/src/main/java/com/fipsdroid/ui/`:
    - `DebugScreen.kt` — main composable:
      - Peer address display (hardcoded, shown as text)
      - Connection state indicator: colored dot + text (Red=Disconnected, Yellow=Connecting/Handshaking, Green=Established)
      - Heartbeat counter: "Sent: N / Received: N / Last: Xs ago"
      - "Connect" / "Disconnect" button (toggles based on state)
      - Error message display area (shows last error if any)
      - Raw log view: scrollable text area showing last 50 log lines
    - `ConnectionStateIndicator.kt` — reusable state dot + label composable
  - Wire UI to ViewModel StateFlows from Task 11
  - No navigation, no multiple screens — single screen debug view
  - Use Material3 defaults, no custom theming

  **Must NOT do**:
  - Do not create settings screen or configuration UI
  - Do not implement peer address input (hardcoded)
  - Do not add animations or transitions
  - Do not use custom fonts or colors beyond Material3 defaults
  - Do not implement landscape layout
  - Do not add navigation library

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: Jetpack Compose UI creation — visual component, layout, state binding
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - `Browser`: Not web UI — native Android Compose

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 11, 13)
  - **Blocks**: Task 14 (end-to-end test needs UI to interact with)
  - **Blocked By**: Task 9 (BLE permissions UI)

  **References**:

  **API/Type References**:
  - `ConnectionState` enum from Task 4 — maps to UI indicator colors
  - `HeartbeatStatus` struct from Task 4 — maps to counter display
  - `androidx.compose.material3.*` — Material3 components
  - `androidx.compose.runtime.collectAsState` — StateFlow → Compose state

  **WHY Each Reference Matters**:
  - ConnectionState enum defines ALL possible states — UI must handle every variant
  - Material3 provides consistent defaults without custom design work

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Debug screen renders with all elements
    Tool: Bash (adb)
    Preconditions: Task 11 complete, app installed
    Steps:
      1. Launch app: `adb shell am start -n com.fipsdroid/.MainActivity`
      2. Take screenshot: `adb exec-out screencap -p > screen.png`
      3. Verify logcat shows "DebugScreen composed"
      4. Verify UI shows: peer address text, state indicator (red/Disconnected), Connect button, heartbeat counters (0/0)
    Expected Result: All UI elements visible, state shows Disconnected, Connect button enabled
    Failure Indicators: Blank screen, crash, missing elements
    Evidence: .sisyphus/evidence/task-12-debug-screen.png

  Scenario: State indicator updates on connection attempt
    Tool: Bash (adb logcat)
    Preconditions: App running
    Steps:
      1. Simulate state change via ViewModel (or tap Connect with no peer available)
      2. Verify logcat shows state transition to "Connecting"
      3. Verify after timeout, state returns to "Disconnected" or "Error"
    Expected Result: UI reflects state changes in real-time
    Failure Indicators: UI stuck on old state, no color change, crash on state update
    Evidence: .sisyphus/evidence/task-12-state-update.txt
  ```

  **Commit**: YES
  - Message: `feat(ui): debug connection state display`
  - Files: `android/app/src/main/java/com/fipsdroid/ui/DebugScreen.kt`, `android/app/src/main/java/com/fipsdroid/ui/ConnectionStateIndicator.kt`
  - Pre-commit: `./gradlew assembleDebug`

- [ ] 13. Pre-Handshake Pubkey Exchange

  **What to do**:
  - Implement the pre-handshake pubkey exchange in `crates/fipsdroid-core/src/transport/pubkey.rs`:
    - Before the Noise IK handshake begins, both sides exchange public keys:
      - Send: `[0x00][32-byte local public key]` (33 bytes total)
      - Receive: `[0x00][32-byte remote public key]` (33 bytes total)
    - This matches upstream fips behavior exactly (see `fips/src/transport/ble/mod.rs`)
    - Validate received message:
      - Must be exactly 33 bytes
      - First byte must be 0x00
      - Remaining 32 bytes are the remote public key
    - Return the remote public key for use in Noise IK handshake
  - Integrate with the Node lifecycle wrapper (Task 7):
    - Pubkey exchange happens AFTER L2CAP connection but BEFORE Node.run() starts
    - Sequence: L2CAP connect → pubkey exchange → Node.run() (which does Noise IK handshake)
  - Write unit tests:
    - Valid pubkey exchange (both sides send correctly formatted messages)
    - Invalid first byte (not 0x00) → error
    - Wrong length message → error
    - Timeout on receive → error

  **Must NOT do**:
  - Do not implement key generation (use hardcoded test keys)
  - Do not implement key storage or management
  - Do not add key rotation
  - Do not deviate from upstream fips wire format

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Protocol wire format implementation requiring byte-level correctness. Must match upstream exactly for interoperability. Tests must verify format compliance.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 11, 12)
  - **Blocks**: Task 14 (end-to-end test requires pubkey exchange)
  - **Blocked By**: Tasks 6 (transport), 7 (node wrapper)

  **References**:

  **Pattern References**:
  - `jmcorgan/fips :: src/transport/ble/mod.rs` — CRITICAL: Lines showing pre-handshake pubkey exchange. Search for `0x00` byte prefix and 32-byte pubkey. This is the wire format we MUST match exactly. Study how upstream sends and validates the pubkey message.

  **API/Type References**:
  - `microfips_core::Keypair` — local keypair providing public key bytes
  - `FipsDroidError::InvalidPeerKey` — error variant for validation failures
  - `Transport::read()` / `Transport::write()` — how bytes flow

  **WHY Each Reference Matters**:
  - `fips/ble/mod.rs` defines THE wire format — byte-level deviation means interop failure. Must match exactly: `[0x00][32 bytes]`, no framing, no additional headers.

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Pubkey exchange roundtrip succeeds
    Tool: Bash
    Preconditions: Tasks 6, 7 complete
    Steps:
      1. Run `cargo test -p fipsdroid-core transport::pubkey::tests::test_pubkey_exchange_roundtrip`
      2. Verify two mock transports exchange pubkeys successfully
      3. Verify received pubkey matches what was sent
    Expected Result: Both sides receive correct 32-byte pubkey
    Failure Indicators: Wrong bytes, length mismatch, protocol deadlock
    Evidence: .sisyphus/evidence/task-13-pubkey-roundtrip.txt

  Scenario: Invalid pubkey format rejected
    Tool: Bash
    Preconditions: Transport mock available
    Steps:
      1. Run `cargo test -p fipsdroid-core transport::pubkey::tests::test_invalid_pubkey_rejected`
      2. Test case 1: Send `[0x01][32 bytes]` — wrong prefix → InvalidPeerKey error
      3. Test case 2: Send `[0x00][31 bytes]` — too short → InvalidPeerKey error
      4. Test case 3: Send `[0x00][33 bytes]` — too long → InvalidPeerKey error
    Expected Result: All invalid formats return FipsDroidError::InvalidPeerKey
    Failure Indicators: Accepts invalid format, wrong error type, panic
    Evidence: .sisyphus/evidence/task-13-pubkey-validation.txt
  ```

  **Commit**: YES
  - Message: `feat(handshake): pre-handshake pubkey exchange matching fips format`
  - Files: `crates/fipsdroid-core/src/transport/pubkey.rs`, updated `crates/fipsdroid-core/src/node.rs`
  - Pre-commit: `cargo test -p fipsdroid-core`

### Wave 4: End-to-End Validation (After Wave 3)

- [ ] 14. End-to-End Test with Upstream fips

  **What to do**:
  - Set up end-to-end test environment:
    - Linux machine (or VM with BLE passthrough) running upstream `fips` with BLE transport enabled
    - Android device (Pixel preferred) with fipsdroid APK installed
    - Ensure both devices are BLE-discoverable to each other
  - Configure test parameters:
    - Generate test keypair for Android side, configure matching key in fips config
    - Hardcode Linux machine's BLE address in Android app config
    - PSM: 0x0085
  - Execute the full lifecycle:
    1. Start fips on Linux with BLE listening
    2. Launch fipsdroid on Android
    3. Tap "Connect"
    4. Observe: L2CAP connection → pubkey exchange → Noise IK handshake → heartbeat exchange
    5. Capture logcat output showing each step
    6. Let run for 30+ seconds to verify heartbeat stability
    7. Tap "Disconnect" and verify clean shutdown
  - Document all findings:
    - Success/failure at each step
    - Timing measurements (connection time, handshake duration)
    - Any device-specific issues encountered
    - Screenshots of debug UI showing Established state + heartbeat counts
  - If Linux peer is unavailable, create a Rust-only "mock fips peer":
    - A simple Rust binary that listens on L2CAP PSM 0x0085
    - Performs the peer side of pubkey exchange
    - Runs microfips-protocol Node as responder
    - This provides a controlled test environment

  **Must NOT do**:
  - Do not automate the test (manual execution is fine for feasibility)
  - Do not test on more than 2 devices (one primary, one optional secondary)
  - Do not attempt multi-peer connections
  - Do not debug upstream fips issues — document and work around them

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: End-to-end validation requires debugging across multiple systems (Android, Linux, BLE stack). Likely to encounter unexpected issues requiring deep investigation.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential (depends on all Wave 3 tasks)
  - **Blocks**: Task 15 (documentation depends on test results)
  - **Blocked By**: Tasks 11, 12, 13

  **References**:

  **Pattern References**:
  - `jmcorgan/fips :: src/transport/ble/mod.rs` — How upstream fips listens for BLE connections. Study: PSM, service UUID, scan/probe behavior, connection acceptance.
  - `Amperstrand/microfips :: crates/microfips-protocol/src/node.rs :: test_handshake_full` — Shows how to set up a mock responder for testing. Can be adapted into a standalone test peer binary.

  **External References**:
  - `bluer` crate documentation for running fips BLE on Linux
  - Android `adb logcat` filtering: `adb logcat -s FIPSDroid:*`

  **WHY Each Reference Matters**:
  - fips BLE module shows exactly what the peer expects — our connection must satisfy its expectations
  - microfips test_handshake_full shows mock responder pattern — fallback if real fips peer is unavailable

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Full lifecycle with fips peer (happy path)
    Tool: Bash (adb logcat)
    Preconditions: fips running on Linux with BLE, fipsdroid installed on Pixel
    Steps:
      1. Start fips: `fips --ble` (or appropriate command) on Linux
      2. Install and launch fipsdroid: `adb install -r app-debug.apk && adb shell am start -n com.fipsdroid/.MainActivity`
      3. Tap Connect button (via adb input or manually)
      4. Capture logcat: `adb logcat -s FIPSDroid:* -d > e2e-log.txt`
      5. Verify log contains (in order):
         - "L2CAP connecting to [ADDRESS] PSM 0x0085"
         - "L2CAP connected"
         - "Pubkey exchange: sent local key"
         - "Pubkey exchange: received peer key"
         - "Noise IK handshake initiated"
         - "Noise IK handshake completed"
         - "Heartbeat sent: seq=1"
         - "Heartbeat received: seq=1"
      6. Wait 30 seconds, verify heartbeat count increases
      7. Take screenshot of debug UI showing green state + heartbeat counts
    Expected Result: Complete lifecycle from connection to stable heartbeats
    Failure Indicators: Connection timeout, handshake failure, heartbeat not received
    Evidence: .sisyphus/evidence/task-14-e2e-lifecycle.txt, .sisyphus/evidence/task-14-debug-ui.png

  Scenario: Connection to unavailable peer (timeout)
    Tool: Bash (adb logcat)
    Preconditions: No fips peer running
    Steps:
      1. Launch app, tap Connect
      2. Capture logcat for 15 seconds
      3. Verify timeout error appears within 10 seconds
    Expected Result: Clean timeout error, UI shows Error state, no crash
    Failure Indicators: Hang, crash, no timeout
    Evidence: .sisyphus/evidence/task-14-timeout.txt

  Scenario: Mock fips peer (fallback if real peer unavailable)
    Tool: Bash
    Preconditions: Mock peer binary compiled
    Steps:
      1. Run mock peer on Linux: `cargo run --bin mock-fips-peer`
      2. Run fipsdroid on Android, connect to mock peer
      3. Verify handshake and heartbeat succeed
    Expected Result: Same lifecycle as real peer scenario
    Failure Indicators: Mock peer diverges from real fips behavior
    Evidence: .sisyphus/evidence/task-14-mock-peer.txt
  ```

  **Commit**: YES
  - Message: `test(e2e): end-to-end validation with upstream fips peer`
  - Files: `tests/`, `.sisyphus/evidence/task-14-*`, optional `src/bin/mock_fips_peer.rs`
  - Pre-commit: `cargo test`

- [ ] 15. Document Findings + Architecture Decisions

  **What to do**:
  - Create `docs/architecture.md` — comprehensive architecture document:
    - System diagram (Mermaid): Android App → UniFFI → fipsdroid-core → microfips-protocol → BLE Transport → L2CAP → fips peer
    - Component responsibilities and boundaries
    - Threading model: Main (UI) ↔ IO (BLE) ↔ Tokio (Rust) ↔ Embassy-time (timers)
    - Data flow: bytes in/out through channel bridge
    - Key architectural decisions with rationale
  - Create `docs/findings.md` — feasibility study results:
    - **Executive Summary**: Can we do this? YES/NO with conditions
    - **What Worked**: List successes with evidence references
    - **What Didn't Work**: List failures/issues with root causes
    - **Device Compatibility**: Which devices worked, which didn't
    - **Performance**: Connection time, handshake duration, heartbeat stability
    - **Risks Encountered vs Predicted**: Compare actual risks to this plan's risk table
    - **Recommended Next Steps**: What to build next based on findings
  - Create `docs/embassy-integration.md` — specific notes on embassy-time/futures usage:
    - Configuration that worked
    - Timer capacity findings (from Task 1)
    - Any workarounds needed
    - Recommendations for future embassy usage
  - Update project `README.md`:
    - Project description
    - Build instructions (cargo-ndk, Gradle)
    - How to run the demo
    - Link to architecture and findings docs

  **Must NOT do**:
  - Do not write aspirational documentation about unbuilt features
  - Do not document features that don't exist yet
  - Do not create API reference docs (too early)
  - Do not add badges, contributing guides, or OSS boilerplate

  **Recommended Agent Profile**:
  - **Category**: `writing`
    - Reason: Technical writing — documentation, diagrams, synthesis of findings. No code changes.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Task 14 results)
  - **Parallel Group**: Sequential
  - **Blocks**: Final Verification Wave
  - **Blocked By**: Task 14 (need test results to document)

  **References**:

  **Pattern References**:
  - `Amperstrand/microfips :: docs/milestones.md` — Documentation style and milestone format from upstream project

  **Task Output References**:
  - Task 1 output: `docs/embassy-audit.md` — timer capacity findings to incorporate
  - Task 5 output: `docs/research-l2cap.md`, `docs/research-uniffi.md` — research findings to synthesize
  - Task 14 output: `.sisyphus/evidence/task-14-*` — test evidence to reference

  **WHY Each Reference Matters**:
  - microfips milestone doc shows the documentation style the ecosystem uses — match it for consistency
  - Earlier task outputs contain raw findings that need synthesis into coherent narrative

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Architecture document is complete
    Tool: Bash (grep)
    Preconditions: docs/architecture.md exists
    Steps:
      1. Verify Mermaid diagram exists: grep "```mermaid" docs/architecture.md
      2. Verify threading model section exists: grep -i "thread" docs/architecture.md
      3. Verify data flow section exists: grep -i "data flow" docs/architecture.md
      4. Verify document references specific source files (not vague)
    Expected Result: All sections present with concrete references
    Failure Indicators: Missing diagram, vague descriptions, no file references
    Evidence: .sisyphus/evidence/task-15-architecture-check.txt

  Scenario: Findings document answers the feasibility question
    Tool: Bash (grep)
    Preconditions: docs/findings.md exists
    Steps:
      1. Verify executive summary contains YES or NO verdict
      2. Verify "What Worked" section has ≥3 items
      3. Verify "Risks Encountered" section references this plan's risk table
      4. Verify "Next Steps" section has ≥3 actionable items
    Expected Result: Clear feasibility verdict with supporting evidence
    Failure Indicators: Ambiguous verdict, no evidence references, vague next steps
    Evidence: .sisyphus/evidence/task-15-findings-check.txt
  ```

  **Commit**: YES
  - Message: `docs(findings): architecture decisions + feasibility findings`
  - Files: `docs/architecture.md`, `docs/findings.md`, `docs/embassy-integration.md`, `README.md`
  - Pre-commit: N/A (documentation only)

---

## Final Verification Wave (MANDATORY — after ALL implementation tasks)

> 4 review agents run in PARALLEL. ALL must APPROVE. Present consolidated results to user and get explicit "okay" before completing.
>
> **Do NOT auto-proceed after verification. Wait for user's explicit approval before marking work complete.**

- [ ] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists (read file, run command). For each "Must NOT Have": search codebase for forbidden patterns — reject with file:line if found. Check evidence files exist in `.sisyphus/evidence/`. Compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [ ] F2. **Code Quality Review** — `unspecified-high`
  Run `cargo clippy`, `cargo test`, `./gradlew lint`. Review all changed files for: `unsafe` blocks without justification, `unwrap()` in production code, empty error handling, unused imports. Check AI slop: excessive comments, over-abstraction, generic names.
  Output: `Build [PASS/FAIL] | Lint [PASS/FAIL] | Tests [N pass/N fail] | Files [N clean/N issues] | VERDICT`

- [ ] F3. **Real Manual QA** — `unspecified-high`
  Start from clean state. Execute EVERY QA scenario from EVERY task — follow exact steps, capture evidence. Test cross-task integration (BLE transport → node lifecycle → UniFFI → Android UI). Test edge cases: BLE off, wrong peer address, timeout. Save to `.sisyphus/evidence/final-qa/`.
  Output: `Scenarios [N/N pass] | Integration [N/N] | Edge Cases [N tested] | VERDICT`

- [ ] F4. **Scope Fidelity Check** — `deep`
  For each task: read "What to do", read actual diff (git log/diff). Verify 1:1 — everything in spec was built (no missing), nothing beyond spec was built (no creep). Check "Must NOT do" compliance. Detect cross-task contamination: Task N touching Task M's files. Flag unaccounted changes.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

| After Task(s) | Commit Message | Key Files | Pre-commit Check |
|---------------|---------------|-----------|-----------------|
| 1 | `docs(audit): embassy timer capacity verified` | `docs/embassy-audit.md` | N/A |
| 2, 4 | `feat(core): scaffold fipsdroid-core crate with types` | `Cargo.toml`, `src/lib.rs`, `src/types.rs`, `src/error.rs` | `cargo check` |
| 3 | `feat(android): scaffold Android project` | `app/`, `build.gradle.kts`, `settings.gradle.kts` | `./gradlew assembleDebug` |
| 5 | `docs(research): L2CAP API + UniFFI async patterns` | `docs/research-l2cap.md`, `docs/research-uniffi.md` | N/A |
| 6 | `feat(transport): BLE L2CAP transport implementation` | `src/transport/`, `src/transport/ble.rs` | `cargo test` |
| 7 | `feat(node): node lifecycle wrapper` | `src/node.rs` | `cargo test` |
| 8 | `feat(bridge): UniFFI bridge layer` | `src/uniffi/`, `uniffi-bindgen/` | `cargo test` |
| 9 | `feat(android): BLE permissions + connection manager` | `app/src/main/java/.../ble/` | `./gradlew test` |
| 10 | `build(ndk): cargo-ndk cross-compilation` | `.cargo/config.toml`, `Makefile` or build script | `cargo ndk build` |
| 11 | `feat(integration): Android↔Rust integration` | `app/src/main/java/.../bridge/` | `./gradlew assembleDebug` |
| 12 | `feat(ui): debug connection state display` | `app/src/main/java/.../ui/` | `./gradlew assembleDebug` |
| 13 | `feat(handshake): pre-handshake pubkey exchange` | `src/transport/pubkey.rs` | `cargo test` |
| 14 | `test(e2e): end-to-end validation with upstream fips` | `tests/`, `.sisyphus/evidence/` | `cargo test` |
| 15 | `docs(findings): architecture decisions + lessons learned` | `docs/architecture.md`, `docs/findings.md` | N/A |

---

## Success Criteria

### Verification Commands

```bash
# Rust crate builds for Android target
cargo ndk -t aarch64-linux-android build --release
# Expected: Finished release target(s)

# Rust tests pass
cargo test
# Expected: test result: ok. N passed; 0 failed

# Android app builds
./gradlew assembleDebug
# Expected: BUILD SUCCESSFUL

# App installs on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Expected: Success

# Logcat shows successful lifecycle
adb logcat -s FIPSDroid:* | head -20
# Expected: L2CAP connected → Handshake completed → Heartbeat received
```

### Final Checklist

- [ ] All "Must Have" items implemented and verified
- [ ] All "Must NOT Have" items absent from codebase
- [ ] All Rust tests pass (`cargo test`)
- [ ] Android app builds and installs (`./gradlew assembleDebug` + `adb install`)
- [ ] End-to-end connection with upstream fips peer demonstrated
- [ ] Noise IK handshake completes (logcat evidence)
- [ ] Heartbeat exchange occurs (logcat evidence)
- [ ] Error scenarios handled gracefully (timeout, invalid key)
- [ ] Architecture decisions documented
- [ ] Evidence files present in `.sisyphus/evidence/`
