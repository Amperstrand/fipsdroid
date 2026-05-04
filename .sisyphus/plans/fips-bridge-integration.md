# FIPS Bridge Integration — Android↔Mac BLE↔TCP Relay

## TL;DR

> **Quick Summary**: Upgrade the existing BLE echo-demo into a working FIPS protocol stack: modify fipsdroid-core's bridge to skip pubkey exchange, build a Swift BLE↔TCP relay on Mac, wire the Android app to the Rust bridge via UniFFI, configure and run the fips daemon over TCP, and verify end-to-end Noise IK handshake + heartbeats.
>
> **Deliverables**:
> - Modified `bridge.rs` that uses pre-configured peer pubkey instead of BLE pubkey exchange
> - Swift BLE↔TCP relay binary (upgrade of `prototype/main.swift`) with FMP framing
> - Android app wired to FipsDroidBridge with real-time state + heartbeat UI
> - fips daemon config for TCP transport on localhost
> - End-to-end verified: Android → BLE → Swift relay → TCP → fips daemon → Noise IK → heartbeats
>
> **Estimated Effort**: Large
> **Parallel Execution**: YES — 4 waves
> **Critical Path**: Task 1 → Task 2 (parallel with 3, 4) → Task 5 → Task 6 → Task 7

---

## Progress Snapshot (2026-04-04)

### Verified Done
- ✅ Task 0 pushed (per prior milestone notes)
- ✅ Task 1 complete (`bridge.rs` skip-pubkey flow implemented; tests passing)
- ✅ Task 2 complete (test keypairs + daemon config created in `~/src/fips/prototype/`)
- ✅ Task 3 complete (Swift relay binary exists and runs; writes `/tmp/fips-l2cap.log`)

### Incomplete / Blocked
- ❌ Task 4 not complete: `BridgeViewModel.kt` missing; main app not wired to UniFFI bridge
- ⚠️ Task 5 partially complete: debug UI exists, but requested FIPS activity wiring is not complete
- ❌ Task 6 not complete: no evidence yet of full Android→BLE→relay→daemon Noise IK + heartbeat flow
- ❌ Task 7 pending: docs/issues update in progress, final integration commit/push pending

### Fresh Evidence Captured Today
- `cargo test -p fipsdroid-core`: 19/19 passing
- `./gradlew :app:assembleDebug` and `:app:installDebug`: successful
- `adb logcat` confirms BLE runtime logging from app
- `/tmp/fips-l2cap.log` + `system_profiler` show Mac Bluetooth was OFF in latest run

---

## Context

### Original Request
Make the Android phone work as a real FIPS leaf node that connects to a Mac running the fips daemon over Bluetooth. The user wants verbose logging, ADB-based monitoring, git commits at milestones, and push to GitHub.

### Interview Summary
**Key Discussions**:
- BLE transport layer is DONE (PING/PONG verified, 3 cycles)
- CoreBluetooth Rust approach ABANDONED (objc2-core-bluetooth lacks L2CAP APIs)
- Architecture decision: Swift relay bridge (BLE↔TCP) on Mac, fips daemon uses existing TCP transport
- bridge.rs `_peer_pubkey` parameter is UNUSED — must be fixed to skip pubkey exchange
- Noise IK transmits initiator's pubkey automatically — no pre-config needed on fips side for Android's key
- fips daemon TCP transport uses FMP framing — Swift relay needs FMP-aware reading for TCP→BLE direction
- BLE→TCP direction is raw byte forwarding (BLE L2CAP preserves message boundaries)

**Research Findings**:
- FMP wire format: `[ver_hi4|phase_lo4][flags][payload_len:2 LE]` prefix determines packet size
- msg1=114 bytes, msg2=69 bytes, established=prefix(4)+header(12)+payload_len+tag(16)
- fips daemon uses Nostr npub (bech32) for peer identification
- TCP transport: `bind_addr` for listening, `send_async()` writes raw FMP packets, `tcp_receive_loop()` reads with `read_fmp_packet()`
- Mac publishes dynamic PSMs (192, 194), not 0x0085

### Metis Review
**Identified Gaps** (addressed):
- bridge.rs pubkey exchange must become conditional/skippable — ADDRESSED in Task 1
- Swift relay FMP framing is non-trivial (phase-dependent sizing) — ADDRESSED in Task 3 with exact byte-level spec
- Keypair generation/management needed for both Android and fips daemon — ADDRESSED in Task 4
- Android BLE must transition from demo PING/PONG to binary data forwarding — ADDRESSED in Task 5

---

## Work Objectives

### Core Objective
Make Android phone function as a FIPS leaf node: complete Noise IK handshake and exchange heartbeats with the fips daemon on Mac, communicating over BLE L2CAP through a Swift relay bridge.

### Concrete Deliverables
- `crates/fipsdroid-core/src/bridge.rs` — Modified to use pre-configured peer pubkey
- `prototype/main.swift` (in fips repo) — Upgraded from echo to BLE↔TCP relay with FMP framing
- `android/app/src/main/java/com/fipsdroid/BridgeViewModel.kt` — New ViewModel wiring BLE to bridge
- `android/app/src/main/java/com/fipsdroid/FipsActivity.kt` — New activity with state + heartbeat UI
- fips daemon test config TOML — TCP transport on `127.0.0.1:4443`
- Test keypairs generated and configured on both sides

### Definition of Done
- [ ] `cargo test -p fipsdroid-core` passes (15+ existing tests + new skip-pubkey tests)
- [ ] Swift relay compiles and runs: `swiftc prototype/main.swift -o fips-relay && ./fips-relay`
- [ ] Android APK builds: `./gradlew assembleDebug`
- [ ] End-to-end: Android shows `Established` state and heartbeat count > 0 in ADB logs
- [ ] fips daemon logs show `Noise IK handshake completed` for the Android peer

### Must Have
- Skip pubkey exchange in bridge.rs (use pre-configured peer pubkey)
- FMP-framing-aware TCP→BLE relay (cannot just forward raw TCP bytes — must recover packet boundaries)
- BLE→TCP raw forwarding (BLE L2CAP preserves message boundaries, no framing needed)
- Verbose logging on ALL components (Android via logcat, Swift relay to /tmp/fips-l2cap.log, fips daemon to stdout)
- Git commits at each milestone with push to GitHub
- Kill old APK before installing new one (`adb shell am force-stop com.fipsdroid`)

### Must NOT Have (Guardrails)
- Do NOT add any new pubkey exchange protocol — Noise IK handles key transmission
- Do NOT modify microfips-protocol or microfips-core crates
- Do NOT modify the fips daemon source code — only create config files
- Do NOT add encryption to the BLE↔TCP relay — the relay is a dumb byte pipe (Noise handles encryption)
- Do NOT implement reconnection/retry logic in this milestone — happy path only
- Do NOT add UI beyond minimal state indicator and heartbeat counter
- Do NOT remove the existing PING/PONG demo — add new FIPS activity alongside it

---

## Verification Strategy (MANDATORY)

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed. No exceptions.

### Test Decision
- **Infrastructure exists**: YES (cargo test for Rust, no Android instrumented tests)
- **Automated tests**: Tests-after (add tests for bridge.rs changes, no TDD)
- **Framework**: `cargo test` for Rust, ADB logcat + manual verification for Android/Swift

### QA Policy
Every task MUST include agent-executed QA scenarios.
Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

- **Rust**: Use Bash (cargo test) — run tests, assert pass count
- **Swift**: Use Bash (swiftc + run) — compile, start relay, verify log output
- **Android**: Use Bash (ADB) — build APK, install, start activity, read logcat
- **End-to-end**: Use Bash (ADB + Swift relay + fips daemon) — start all 3, verify handshake in logs

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 0 (Immediate — push existing work):
└── Task 0: Git push existing commits to GitHub (both repos)

Wave 1 (After Wave 0 — foundation, MAX PARALLEL):
├── Task 1: Modify bridge.rs to skip pubkey exchange (fipsdroid repo) [deep]
├── Task 2: Generate test keypairs + fips daemon config (fips repo) [quick]
└── Task 3: Upgrade Swift relay with FMP framing (fips repo) [deep]

Wave 2 (After Task 1 — Android integration):
├── Task 4: Wire Android BLE to FipsDroidBridge (fipsdroid repo) [unspecified-high]
└── Task 5: Build minimal FIPS debug UI (fipsdroid repo) [visual-engineering]

Wave 3 (After Tasks 2, 3, 4, 5 — end-to-end):
├── Task 6: End-to-end integration test (both repos) [deep]
└── Task 7: Final git commit + push + issue updates [quick]

Wave FINAL (After ALL tasks — 4 parallel reviews, then user okay):
├── Task F1: Plan compliance audit (oracle)
├── Task F2: Code quality review (unspecified-high)
├── Task F3: Real manual QA (unspecified-high)
└── Task F4: Scope fidelity check (deep)
-> Present results -> Get explicit user okay

Critical Path: Task 0 → Task 1 → Task 4 → Task 6 → Task 7 → F1-F4 → user okay
Parallel Speedup: ~50% faster than sequential
Max Concurrent: 3 (Wave 1)
```

### Dependency Matrix

| Task | Depends On | Blocks |
|------|-----------|--------|
| 0 | — | 1, 2, 3 |
| 1 | 0 | 4, 5 |
| 2 | 0 | 6 |
| 3 | 0 | 6 |
| 4 | 1 | 6 |
| 5 | 1 | 6 |
| 6 | 2, 3, 4, 5 | 7 |
| 7 | 6 | F1-F4 |
| F1-F4 | 7 | — |

### Agent Dispatch Summary

- **Wave 0**: **1** task — T0 → `quick`
- **Wave 1**: **3** tasks — T1 → `deep`, T2 → `quick`, T3 → `deep`
- **Wave 2**: **2** tasks — T4 → `unspecified-high`, T5 → `visual-engineering`
- **Wave 3**: **2** tasks — T6 → `deep`, T7 → `quick`
- **FINAL**: **4** tasks — F1 → `oracle`, F2 → `unspecified-high`, F3 → `unspecified-high`, F4 → `deep`

---

## TODOs

> Implementation + Test = ONE Task. Never separate.
> EVERY task MUST have: Recommended Agent Profile + Parallelization info + QA Scenarios.

- [x] 0. Push Existing Commits to GitHub (Both Repos)

  **What to do**:
  - In `fipsdroid` repo (`/Users/macbook/src/fipsdroid`): `git push origin main` (2 commits ahead: `f8bed29`, `6433937`)
  - In `fips` repo (`/Users/macbook/src/fips`): `git push origin macos-support` (1 commit ahead: `e126a34`)
  - Verify both pushes succeeded by checking `git status` shows "up to date with origin"

  **Must NOT do**:
  - Do NOT force push
  - Do NOT push to upstream (only origin)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Simple git push commands, no code changes
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (must complete first)
  - **Parallel Group**: Wave 0 (solo)
  - **Blocks**: Tasks 1, 2, 3
  - **Blocked By**: None

  **References**:
  - `fipsdroid` remote: `origin → git@github.com:Amperstrand/fipsdroid.git`, branch `main`
  - `fips` remote: `origin → git@github.com:Amperstrand/fips.git`, branch `macos-support`

  **Acceptance Criteria**:
  - [ ] `git -C /Users/macbook/src/fipsdroid status` shows "up to date" or "nothing to commit"
  - [ ] `git -C /Users/macbook/src/fips status` shows "up to date" or "nothing to commit"

  **QA Scenarios**:

  ```
  Scenario: Verify fipsdroid push succeeded
    Tool: Bash
    Preconditions: 2 unpushed commits on main
    Steps:
      1. Run: git -C /Users/macbook/src/fipsdroid push origin main
      2. Run: git -C /Users/macbook/src/fipsdroid status
    Expected Result: Status shows "Your branch is up to date with 'origin/main'"
    Failure Indicators: "rejected", "failed to push", "non-fast-forward"
    Evidence: .sisyphus/evidence/task-0-push-fipsdroid.txt

  Scenario: Verify fips push succeeded
    Tool: Bash
    Preconditions: 1 unpushed commit on macos-support
    Steps:
      1. Run: git -C /Users/macbook/src/fips push origin macos-support
      2. Run: git -C /Users/macbook/src/fips status
    Expected Result: Status shows "Your branch is up to date with 'origin/macos-support'"
    Failure Indicators: "rejected", "failed to push", "non-fast-forward"
    Evidence: .sisyphus/evidence/task-0-push-fips.txt
  ```

  **Commit**: NO (push only, no new commits)

---

- [x] 1. Modify bridge.rs — Skip Pubkey Exchange, Use Pre-configured Peer Key

  **What to do**:
  - In `crates/fipsdroid-core/src/bridge.rs`:
    1. Remove the underscore from `_peer_pubkey` on line 118 — make it `peer_pubkey_33` (already a `[u8; 33]`)
    2. Delete the entire pubkey exchange block (lines 151-198): the `local_pubkey_32` derivation, the `timeout(... send_pubkey/recv_pubkey ...)` call, and the `peer_pubkey_received` match arms
    3. Delete the `peer_pubkey_33` re-derivation on lines 200-202 (it's now computed at line 118)
    4. Keep the `Handshaking` state emit (line 205) — move it to right before `FipsDroidNode::new()`
    5. Keep the `local_pubkey` derivation (lines 134-149) — it's still needed for `local_secret`
    6. Remove `PUBKEY_EXCHANGE_TIMEOUT_SECS` constant (line 12) — no longer used
    7. The `peer_pubkey_33` variable (computed from constructor's `self.peer_pubkey` Vec) feeds directly into `FipsDroidNode::new()` at line 225
  - Add 2 new tests:
    - `test_bridge_skips_pubkey_exchange`: Create bridge with valid 33-byte peer_pubkey, start(), verify state transitions to `Connecting` then `Handshaking` without feeding any pubkey data via `feed_incoming()`
    - `test_bridge_rejects_invalid_pubkey_length`: Create bridge with 16-byte peer_pubkey, start(), verify `on_error` callback fires with key-related error
  - Run `cargo test -p fipsdroid-core` to verify all 15+ existing tests still pass plus 2 new ones

  **Must NOT do**:
  - Do NOT modify `BleTransport` (send_pubkey/recv_pubkey methods stay — they're just unused now)
  - Do NOT modify `FipsDroidNode` or `microfips-protocol`
  - Do NOT add a "mode" parameter — always skip pubkey exchange (bridge mode is the only mode)
  - Do NOT change the UniFFI API signature (peer_pubkey stays as `Vec<u8>`)

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Requires careful understanding of the async flow and state machine to modify without breaking existing tests
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 2, 3)
  - **Blocks**: Tasks 4, 5
  - **Blocked By**: Task 0

  **References**:

  **Pattern References**:
  - `crates/fipsdroid-core/src/bridge.rs:118-124` — Current `_peer_pubkey` derivation from `self.peer_pubkey` Vec → `[u8; 33]`. This is the code to UN-underscore and keep.
  - `crates/fipsdroid-core/src/bridge.rs:126-278` — The entire async spawn block. Lines 151-202 are the pubkey exchange to DELETE. Lines 204-239 are the node creation to KEEP (but use `peer_pubkey_33` from line 118 instead of re-derived `peer_pubkey_33`).
  - `crates/fipsdroid-core/src/bridge.rs:342-511` — Existing tests. New tests should follow this pattern (MockCallback, etc.)

  **API/Type References**:
  - `crates/fipsdroid-core/src/node.rs:90-112` — `FipsDroidNode::new()` takes `peer_pubkey: [u8; 33]` at line 95. This is the consumer of the peer pubkey.
  - `crates/fipsdroid-core/src/types.rs:1-10` — `ConnectionState` enum with `Handshaking` variant
  - `crates/fipsdroid-core/src/transport/ble.rs:78-96` — `send_pubkey()`/`recv_pubkey()` methods that will become dead code (but keep them)

  **Acceptance Criteria**:
  - [ ] `cargo test -p fipsdroid-core` passes with 17+ tests (15 existing + 2 new)
  - [ ] `cargo check -p fipsdroid-core` has no warnings about unused `_peer_pubkey`
  - [ ] `grep -n "send_pubkey\|recv_pubkey\|PUBKEY_EXCHANGE_TIMEOUT" crates/fipsdroid-core/src/bridge.rs` returns NO matches
  - [ ] `grep -n "peer_pubkey_33" crates/fipsdroid-core/src/bridge.rs` returns matches (used, not underscored)

  **QA Scenarios**:

  ```
  Scenario: All existing tests still pass after bridge.rs modification
    Tool: Bash
    Preconditions: bridge.rs modified per spec above
    Steps:
      1. Run: cargo test -p fipsdroid-core 2>&1
      2. Parse output for "test result:" line
    Expected Result: "test result: ok. N passed; 0 failed" where N >= 17
    Failure Indicators: "FAILED", "0 failed" not present, compilation errors
    Evidence: .sisyphus/evidence/task-1-cargo-test.txt

  Scenario: Bridge transitions to Handshaking without pubkey exchange data
    Tool: Bash
    Preconditions: New test `test_bridge_skips_pubkey_exchange` added
    Steps:
      1. Run: cargo test -p fipsdroid-core test_bridge_skips_pubkey_exchange -- --nocapture 2>&1
    Expected Result: Test passes — state reaches Handshaking without any feed_incoming() calls
    Failure Indicators: "timed out", "pubkey exchange", test failure
    Evidence: .sisyphus/evidence/task-1-skip-pubkey-test.txt

  Scenario: No dead code warnings for peer_pubkey
    Tool: Bash
    Preconditions: bridge.rs modified
    Steps:
      1. Run: cargo check -p fipsdroid-core 2>&1
      2. Search output for "unused" or "dead_code" warnings referencing peer_pubkey
    Expected Result: No warnings about unused peer_pubkey
    Failure Indicators: "warning: unused variable: `_peer_pubkey`" or similar
    Evidence: .sisyphus/evidence/task-1-cargo-check.txt
  ```

  **Commit**: YES
  - Message: `fix(bridge): skip pubkey exchange, use pre-configured peer key directly`
  - Files: `crates/fipsdroid-core/src/bridge.rs`
  - Pre-commit: `cargo test -p fipsdroid-core`

---

- [x] 2. Generate Test Keypairs + fips Daemon TCP Config

  **What to do**:
  - Generate a Noise IK keypair for Android (initiator):
    1. Create a small Rust script or use `fips` CLI to generate a secp256k1 keypair
    2. Save the 32-byte private key as hex in `prototype/test-keys/android.privkey.hex`
    3. Derive the 33-byte compressed public key, save as hex in `prototype/test-keys/android.pubkey.hex`
  - Generate a Noise IK keypair for the fips daemon (responder):
    1. Either use `fips` daemon's own key generation or create a test keypair
    2. Save the daemon's private key hex in `prototype/test-keys/daemon.privkey.hex`
    3. Derive the daemon's public key (npub bech32 format), save in `prototype/test-keys/daemon.npub.txt`
  - Create fips daemon test config at `prototype/test-fips-config.toml`:
    ```toml
    [identity]
    nsec = "<daemon private key in hex or bech32>"
    
    [[transports.tcp]]
    bind_addr = "127.0.0.1:4443"
    
    [[peers]]
    npub = "<android's npub>"
    connect_policy = "accept"
    ```
  - Create a README at `prototype/test-keys/README.md` explaining these are TEST KEYS ONLY
  - All files go in the `fips` repo (`/Users/macbook/src/fips/`)

  **Must NOT do**:
  - Do NOT use real/production keys
  - Do NOT modify any fips daemon source code
  - Do NOT commit private keys to a public repo (these are test-only keys for local development)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: File generation, no complex logic. May need to run a Rust snippet to derive keys.
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 3)
  - **Blocks**: Task 6
  - **Blocked By**: Task 0

  **References**:

  **Pattern References**:
  - `/Users/macbook/src/fips/src/config/peer.rs` — PeerConfig struct showing `npub` field format (Nostr bech32)
  - `/Users/macbook/src/fips/src/config/transport.rs` — TcpConfig with `bind_addr` field

  **API/Type References**:
  - `microfips_core::noise::ecdh_pubkey(&secret)` — Derives compressed 33-byte pubkey from 32-byte secret. Used in bridge.rs line 134.
  - Nostr npub format: bech32 encoding of the 32-byte x-coordinate of the public key

  **External References**:
  - fips config format: Check existing test configs in `/Users/macbook/src/fips/tests/` or `/Users/macbook/src/fips/scripts/` for TOML/YAML examples

  **Acceptance Criteria**:
  - [ ] `prototype/test-keys/android.privkey.hex` exists and contains 64 hex chars
  - [ ] `prototype/test-keys/android.pubkey.hex` exists and contains 66 hex chars (33 bytes compressed)
  - [ ] `prototype/test-keys/daemon.privkey.hex` exists and contains 64 hex chars
  - [ ] `prototype/test-keys/daemon.npub.txt` exists and contains npub1... bech32 string
  - [ ] `prototype/test-fips-config.toml` exists and is valid TOML with correct structure
  - [ ] The Android pubkey can be verified: derive from privkey, compare to stored pubkey

  **QA Scenarios**:

  ```
  Scenario: Verify keypair files exist and have correct format
    Tool: Bash
    Preconditions: Key generation script has run
    Steps:
      1. Run: wc -c /Users/macbook/src/fips/prototype/test-keys/android.privkey.hex
      2. Run: wc -c /Users/macbook/src/fips/prototype/test-keys/android.pubkey.hex
      3. Run: head -1 /Users/macbook/src/fips/prototype/test-keys/daemon.npub.txt
      4. Run: cat /Users/macbook/src/fips/prototype/test-fips-config.toml
    Expected Result: privkey=64 hex chars, pubkey=66 hex chars, npub starts with "npub1", TOML has [identity] and [[transports.tcp]] sections
    Failure Indicators: Wrong file sizes, missing files, invalid TOML syntax
    Evidence: .sisyphus/evidence/task-2-keypair-verify.txt

  Scenario: Verify key derivation is consistent
    Tool: Bash
    Preconditions: Both key files exist
    Steps:
      1. Read android.privkey.hex
      2. Use a Rust snippet or fips utility to derive the pubkey from the privkey
      3. Compare derived pubkey with stored android.pubkey.hex
    Expected Result: Derived pubkey matches stored pubkey exactly
    Failure Indicators: Mismatch between derived and stored public key
    Evidence: .sisyphus/evidence/task-2-key-derivation-check.txt
  ```

  **Commit**: YES
  - Message: `chore(config): add test keypairs and fips daemon TCP config for BLE relay testing`
  - Files: `prototype/test-keys/`, `prototype/test-fips-config.toml`
  - Pre-commit: none (no code to test)
  - Repo: `fips` (`/Users/macbook/src/fips/`), branch `macos-support`

---

- [x] 3. Upgrade Swift Relay — BLE Echo Server to BLE↔TCP Relay with FMP Framing

  **What to do**:
  - Modify `/Users/macbook/src/fips/prototype/main.swift` to:
    1. **Add TCP client connection**: On startup (after BLE L2CAP is published), connect to `127.0.0.1:4443` via TCP. The relay is a TCP CLIENT connecting to the fips daemon which is a TCP SERVER.
    2. **Replace echo logic in `L2CAPChannelHandler`**: Instead of PING→PONG echo, forward BLE bytes directly to TCP socket. BLE L2CAP preserves message boundaries, so each BLE read = one complete FMP packet → write it to TCP as-is.
    3. **Add FMP-aware TCP reader**: Read from TCP socket using FMP framing logic:
       - Read 4-byte prefix: `[ver_hi4|phase_lo4][flags][payload_len:2 LE]`
       - Phase 0 (established): remaining = 12 + payload_len + 16 bytes
       - Phase 1 (msg1): remaining = payload_len bytes (payload_len must be 110)
       - Phase 2 (msg2): remaining = payload_len bytes (payload_len must be 65)
       - Write the complete packet (prefix + remaining) as a single BLE L2CAP write
    4. **Bidirectional relay**: Two concurrent data flows:
       - BLE→TCP: `readAndForward()` replaces `readAndEcho()` — read from BLE inputStream, write to TCP outputStream
       - TCP→BLE: Separate thread/dispatch reads from TCP inputStream using FMP framing, writes to BLE outputStream
    5. **TCP connection config**: Read daemon address from command line arg or default to `127.0.0.1:4443`
    6. **Logging**: Log every packet direction, phase, and size. Log hex dump of first 16 bytes for debug.
    7. **Error handling**: If TCP disconnects, log and close BLE. If BLE disconnects, log and close TCP.

  **Must NOT do**:
  - Do NOT encrypt/decrypt — relay is a dumb byte pipe
  - Do NOT modify FMP packet contents — forward as-is
  - Do NOT validate packet crypto — just framing for boundary recovery
  - Do NOT remove the service UUID or advertising — keep existing BLE peripheral setup
  - Do NOT add reconnection logic — if either side drops, log and exit

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Requires implementing FMP framing protocol in Swift, bidirectional streaming, and correct async I/O
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2)
  - **Blocks**: Task 6
  - **Blocked By**: Task 0

  **References**:

  **Pattern References**:
  - `/Users/macbook/src/fips/prototype/main.swift:80-164` — Current `L2CAPChannelHandler` with `readAndEcho()`. This is what gets replaced with `readAndForward()`.
  - `/Users/macbook/src/fips/prototype/main.swift:166-272` — `PeripheralManager` — keep this entirely, only modify `didOpen channel` to also start TCP→BLE relay.

  **API/Type References**:
  - `/Users/macbook/src/fips/src/transport/tcp/stream.rs:94-161` — `read_fmp_packet()` Rust implementation. The Swift relay must replicate this logic exactly:
    - Line 102-103: `version = prefix[0] >> 4; phase = prefix[0] & 0x0F`
    - Line 109: `payload_len = u16 LE from prefix[2..4]`
    - Line 112-152: Phase-dependent remaining byte calculation
    - Constants: `MSG1_WIRE_SIZE=114`, `MSG2_WIRE_SIZE=69`, `MSG1_PAYLOAD_LEN=110`, `MSG2_PAYLOAD_LEN=65`
    - Established: `remaining = 12 + payload_len + 16`
  - `/Users/macbook/src/fips/src/transport/tcp/stream.rs:66-75` — Wire size constants to replicate in Swift

  **External References**:
  - Swift Foundation `Socket` / `InputStream`/`OutputStream` for TCP: Use `Stream.getStreamsToHost(withName:port:inputStream:outputStream:)` or `CFStreamCreatePairWithSocketToHost`

  **Acceptance Criteria**:
  - [ ] `swiftc /Users/macbook/src/fips/prototype/main.swift -o /tmp/fips-relay` compiles with no errors
  - [ ] Running `/tmp/fips-relay` logs "Connecting to TCP 127.0.0.1:4443" and "L2CAP channel published with PSM: NN"
  - [ ] When BLE client connects and sends bytes, relay logs "BLE→TCP: N bytes (phase X)" and writes to TCP
  - [ ] When TCP sends FMP packets, relay logs "TCP→BLE: N bytes (phase X)" and writes to BLE

  **QA Scenarios**:

  ```
  Scenario: Swift relay compiles successfully
    Tool: Bash
    Preconditions: main.swift has been modified with relay logic
    Steps:
      1. Run: swiftc /Users/macbook/src/fips/prototype/main.swift -o /tmp/fips-relay 2>&1
      2. Check exit code
    Expected Result: Exit code 0, no compilation errors. Warnings are acceptable.
    Failure Indicators: "error:", non-zero exit code
    Evidence: .sisyphus/evidence/task-3-swift-compile.txt

  Scenario: Relay starts and advertises BLE
    Tool: Bash
    Preconditions: Compiled relay binary exists at /tmp/fips-relay
    Steps:
      1. Run: timeout 5 /tmp/fips-relay 2>&1 || true
      2. Check output for expected log lines
    Expected Result: Logs contain "FIPS L2CAP PERIPHERAL STARTED", "L2CAP channel published with PSM:", "Connecting to TCP"
    Failure Indicators: Crash, no PSM published, missing TCP connection attempt
    Evidence: .sisyphus/evidence/task-3-relay-startup.txt

  Scenario: FMP framing logic handles all packet types correctly
    Tool: Bash
    Preconditions: Relay is running, TCP echo server simulates fips daemon
    Steps:
      1. Verify relay code contains correct constants: MSG1_WIRE_SIZE=114, MSG2_WIRE_SIZE=69
      2. Verify relay code parses phase from prefix[0] & 0x0F
      3. Verify established phase calculates remaining as 12 + payload_len + 16
    Expected Result: grep of main.swift confirms all three FMP constants and phase parsing logic
    Failure Indicators: Wrong constants, missing phase handling, incorrect remaining calculation
    Evidence: .sisyphus/evidence/task-3-fmp-framing-verify.txt
  ```

  **Commit**: YES
  - Message: `feat(relay): upgrade Swift L2CAP probe to BLE↔TCP relay with FMP framing`
  - Files: `prototype/main.swift`
  - Pre-commit: `swiftc prototype/main.swift -o /tmp/fips-relay`
  - Repo: `fips` (`/Users/macbook/src/fips/`), branch `macos-support`

---

- [ ] 4. Wire Android BLE to FipsDroidBridge — BridgeViewModel + Data Forwarding

  **What to do**:
  - Create `android/app/src/main/java/com/fipsdroid/bridge/BridgeViewModel.kt`:
    1. ViewModel that owns a `FipsDroidBridge` instance (from UniFFI bindings)
    2. Constructor params: `peer_pubkey` (hex string from hardcoded test key or intent extra), `local_privkey` (hex string)
    3. On BLE L2CAP connection established:
       - Create `FipsDroidBridge(peer_address, peer_pubkey_bytes, local_privkey_bytes)`
       - Call `bridge.start(callback)` with a callback that updates LiveData/StateFlow for UI
       - Start a coroutine loop that reads from BLE `inputStream` and calls `bridge.feed_incoming(data)`
       - Start a coroutine loop that calls `bridge.poll_outgoing()` and writes result to BLE `outputStream`
    4. Expose StateFlow for: `connectionState: ConnectionState`, `heartbeatStatus: HeartbeatStatus`, `logLines: List<String>`
    5. `connect(address: String, psm: Int)` method:
       - Uses `BleConnectionManager.connect(address, psm)` to get L2CAP connection
       - Wires input/output streams to bridge as described above
    6. `disconnect()` method: calls `bridge.stop()`, closes BLE connection
    7. Verbose logging: Log every `feed_incoming` call with byte count, every `poll_outgoing` result, every state change
  - Ensure UniFFI bindings are generated: `cargo build -p fipsdroid-core` should produce the Kotlin bindings
  - The hardcoded test keys (from Task 2) go in a companion object or BuildConfig field for now

  **Must NOT do**:
  - Do NOT modify `BleConnectionManager.kt` — use it as-is
  - Do NOT modify `BleDemoActivity.kt` — new activity in Task 5
  - Do NOT add reconnection logic
  - Do NOT handle key management beyond hardcoded test keys

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Android ViewModel + coroutines + UniFFI integration requires substantial Kotlin knowledge
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Task 5)
  - **Blocks**: Task 6
  - **Blocked By**: Task 1 (needs modified bridge.rs that skips pubkey exchange)

  **References**:

  **Pattern References**:
  - `android/app/src/main/java/com/fipsdroid/BleDemoActivity.kt:288-321` — `connectToDevice()` pattern with PSM fallback loop. BridgeViewModel should reuse this connection flow.
  - `android/app/src/main/java/com/fipsdroid/BleDemoActivity.kt:323-374` — `performPingPong()` shows how to read/write BLE streams. Replace PING/PONG with bridge feed/poll loops.
  - `android/app/src/main/java/com/fipsdroid/ble/BleConnectionManager.kt:23-41` — `L2capConnection` class with `inputStream`/`outputStream` — the streams to wire to the bridge.

  **API/Type References**:
  - `crates/fipsdroid-core/src/bridge.rs:40-77` — `FipsDroidBridge` UniFFI API: `new(peer_address, peer_pubkey, local_privkey)`, `start(callback)`, `stop()`, `feed_incoming(data)`, `poll_outgoing()`, `get_state()`, `get_heartbeat_status()`
  - `crates/fipsdroid-core/src/bridge.rs:14-19` — `FipsDroidCallback` interface: `on_state_changed(state)`, `on_heartbeat(status)`, `on_error(error)`
  - `crates/fipsdroid-core/src/types.rs` — `ConnectionState`, `HeartbeatStatus`, `NodeConfig` — these will appear as Kotlin classes via UniFFI

  **Test References**:
  - `android/app/src/main/java/com/fipsdroid/ble/RustBridge.kt` — Check if UniFFI bindings are already generated/configured here

  **Acceptance Criteria**:
  - [ ] `BridgeViewModel.kt` exists at `android/app/src/main/java/com/fipsdroid/bridge/BridgeViewModel.kt`
  - [ ] `./gradlew assembleDebug` succeeds (APK builds)
  - [ ] BridgeViewModel references UniFFI-generated `FipsDroidBridge`, `FipsDroidCallback`, `ConnectionState` classes
  - [ ] ViewModel exposes `connectionState: StateFlow<String>` and `heartbeatStatus: StateFlow<HeartbeatStatus>` (or equivalent)

  **QA Scenarios**:

  ```
  Scenario: Android APK builds with BridgeViewModel
    Tool: Bash
    Preconditions: BridgeViewModel.kt created, UniFFI bindings available
    Steps:
      1. Run: adb shell am force-stop com.fipsdroid
      2. Run: cd /Users/macbook/src/fipsdroid/android && ./gradlew assembleDebug 2>&1
      3. Check for BUILD SUCCESSFUL
    Expected Result: "BUILD SUCCESSFUL" in output, APK at app/build/outputs/apk/debug/app-debug.apk
    Failure Indicators: "BUILD FAILED", unresolved reference to FipsDroidBridge, Kotlin compilation errors
    Evidence: .sisyphus/evidence/task-4-android-build.txt

  Scenario: BridgeViewModel has correct API surface
    Tool: Bash
    Preconditions: BridgeViewModel.kt exists
    Steps:
      1. Run: grep -n "fun connect\|fun disconnect\|feed_incoming\|poll_outgoing\|connectionState\|heartbeatStatus" android/app/src/main/java/com/fipsdroid/bridge/BridgeViewModel.kt
    Expected Result: All methods found: connect(), disconnect(), feed_incoming usage, poll_outgoing usage, connectionState flow, heartbeatStatus flow
    Failure Indicators: Missing any of the required methods
    Evidence: .sisyphus/evidence/task-4-viewmodel-api.txt
  ```

  **Commit**: YES (grouped with Task 5)
  - Message: `feat(android): wire BLE to FipsDroidBridge with debug UI`
  - Files: `android/app/src/main/java/com/fipsdroid/bridge/BridgeViewModel.kt`, `android/app/src/main/java/com/fipsdroid/FipsActivity.kt`, `android/app/src/main/AndroidManifest.xml`
  - Pre-commit: `cd android && ./gradlew assembleDebug`

---

- [ ] 5. Build Minimal FIPS Debug UI — FipsActivity

  **What to do**:
  - Create `android/app/src/main/java/com/fipsdroid/FipsActivity.kt`:
    1. Compose UI with:
       - Header: "FIPS Bridge" + build version/timestamp
       - Connection state indicator: colored text showing `Disconnected`/`Connecting`/`Handshaking`/`Established`/`Error`
       - Heartbeat counter: "Sent: N / Received: N / Last: Xs ago"
       - "Start Scan" button (reuses BLE scan logic from BleDemoActivity — scan for SERVICE_UUID, connect, then wire to BridgeViewModel)
       - "Disconnect" button
       - Scrollable log view (last 500 lines) showing all bridge events
    2. Uses `BridgeViewModel` from Task 4 for state management
    3. On scan match → connect → wire to bridge automatically
    4. Candidate PSMs: same logic as BleDemoActivity (intent extra, PSM_FIPS, observed fallbacks)
    5. Verbose ADB logging: every state change, every heartbeat, every error with tag `FipsBridge`
  - Register in `AndroidManifest.xml` as a new activity (alongside BleDemoActivity)
  - Make FipsActivity the default launcher activity (move MAIN/LAUNCHER intent-filter from BleDemoActivity to FipsActivity)

  **Must NOT do**:
  - Do NOT remove BleDemoActivity — keep it registered but without LAUNCHER intent-filter
  - Do NOT add complex UI — minimal debug-focused layout only
  - Do NOT add settings/preferences screens

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: Compose UI layout, theming, visual state indicators
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Task 4)
  - **Blocks**: Task 6
  - **Blocked By**: Task 1 (needs modified bridge.rs), Task 4 (needs BridgeViewModel)

  **References**:

  **Pattern References**:
  - `android/app/src/main/java/com/fipsdroid/BleDemoActivity.kt:443-523` — `BleDemoScreen` Composable. Copy this layout pattern but replace PING/PONG with bridge state display.
  - `android/app/src/main/java/com/fipsdroid/BleDemoActivity.kt:126-197` — `startBleScan()` implementation. Reuse identical scan logic.
  - `android/app/src/main/java/com/fipsdroid/BleDemoActivity.kt:288-321` — `connectToDevice()` with PSM fallback. Reuse but wire to BridgeViewModel instead of performPingPong.

  **API/Type References**:
  - `android/app/src/main/AndroidManifest.xml:24-42` — Current activity registrations. Add FipsActivity, move LAUNCHER intent-filter.
  - `crates/fipsdroid-core/src/types.rs:1-10` — `ConnectionState` enum variants for UI state mapping

  **Acceptance Criteria**:
  - [ ] `FipsActivity.kt` exists at `android/app/src/main/java/com/fipsdroid/FipsActivity.kt`
  - [ ] `AndroidManifest.xml` has FipsActivity with MAIN/LAUNCHER intent-filter
  - [ ] BleDemoActivity retains its registration but without LAUNCHER
  - [ ] `./gradlew assembleDebug` succeeds
  - [ ] APK installs and opens FipsActivity by default

  **QA Scenarios**:

  ```
  Scenario: FipsActivity launches as default
    Tool: Bash
    Preconditions: APK built and installed
    Steps:
      1. Run: adb shell am force-stop com.fipsdroid
      2. Run: adb install -r /Users/macbook/src/fipsdroid/android/app/build/outputs/apk/debug/app-debug.apk
      3. Run: adb shell am start -n com.fipsdroid/.FipsActivity
      4. Run: adb logcat -d -s FipsBridge:V | tail -5
    Expected Result: Logcat shows "FipsActivity created" and build version info
    Failure Indicators: ActivityNotFoundException, crash, BleDemoActivity launches instead
    Evidence: .sisyphus/evidence/task-5-fipsactivity-launch.txt

  Scenario: FipsActivity shows connection state UI
    Tool: Bash
    Preconditions: FipsActivity is running
    Steps:
      1. Run: adb shell uiautomator dump /dev/tty 2>/dev/null | grep -i "fips\|bridge\|scan\|heartbeat\|disconnect"
    Expected Result: UI contains "FIPS Bridge" header, "Start Scan" button, connection state text
    Failure Indicators: Missing UI elements, blank screen, crash
    Evidence: .sisyphus/evidence/task-5-ui-elements.txt
  ```

  **Commit**: YES (grouped with Task 4)
  - Message: `feat(android): wire BLE to FipsDroidBridge with debug UI`
  - Files: `android/app/src/main/java/com/fipsdroid/FipsActivity.kt`, `android/app/src/main/AndroidManifest.xml`
  - Pre-commit: `cd android && ./gradlew assembleDebug`

---

- [ ] 6. End-to-End Integration Test — Noise IK Handshake + Heartbeats

  **What to do**:
  - Start all 3 components and verify end-to-end communication:
    1. **Start fips daemon** on Mac:
       ```bash
       cd /Users/macbook/src/fips
       cargo run -- --config prototype/test-fips-config.toml 2>&1 | tee /tmp/fips-daemon.log &
       ```
    2. **Start Swift relay** on Mac:
       ```bash
       /tmp/fips-relay 2>&1 &  # or: /tmp/fips-relay 127.0.0.1:4443
       ```
       Wait for "L2CAP channel published with PSM: NN" in /tmp/fips-l2cap.log
    3. **Install and start Android APK**:
       ```bash
       adb shell am force-stop com.fipsdroid
       adb install -r android/app/build/outputs/apk/debug/app-debug.apk
       adb shell am start -n com.fipsdroid/.FipsActivity --ez auto_scan true
       ```
    4. **Monitor all 3 log streams** simultaneously:
       - `tail -f /tmp/fips-daemon.log` (fips daemon)
       - `tail -f /tmp/fips-l2cap.log` (Swift relay)
       - `adb logcat -v time -s FipsBridge:V BleDemo:V BleConnectionManager:V`
    5. **Verify handshake**: Within 30 seconds, expect to see in logs:
       - Swift relay: "BLE→TCP: 114 bytes (phase 1)" (msg1 forwarded)
       - Swift relay: "TCP→BLE: 69 bytes (phase 2)" (msg2 forwarded back)
       - fips daemon: handshake completion log
       - Android logcat: state transition to "Established"
    6. **Verify heartbeats**: After handshake, within 60 seconds, expect:
       - Android logcat: heartbeat sent/received counts incrementing
       - fips daemon: heartbeat log entries for the Android peer
    7. **Capture evidence**: Save all log outputs to evidence files

  **Must NOT do**:
  - Do NOT modify any code in this task — this is verification only
  - Do NOT skip any of the 3 components

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Multi-process coordination, log analysis across 3 systems, timing-sensitive verification
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 3 (sequential after all Wave 1+2 tasks)
  - **Blocks**: Task 7
  - **Blocked By**: Tasks 2, 3, 4, 5

  **References**:

  **Pattern References**:
  - Task 2 output: test-fips-config.toml location and content
  - Task 3 output: Swift relay binary at /tmp/fips-relay
  - Task 5 output: FipsActivity in APK

  **External References**:
  - ADB commands: `adb shell am force-stop com.fipsdroid`, `adb install -r`, `adb logcat -v time -s`
  - fips daemon: `cargo run -- --config <path>`

  **Acceptance Criteria**:
  - [ ] All 3 components start without errors
  - [ ] Swift relay logs show BLE→TCP forwarding of msg1 (114 bytes)
  - [ ] Swift relay logs show TCP→BLE forwarding of msg2 (69 bytes)
  - [ ] Android logcat shows state transition: Connecting → Handshaking → Established
  - [ ] Android logcat shows heartbeat_received_count > 0
  - [ ] fips daemon logs show handshake completion for Android peer

  **QA Scenarios**:

  ```
  Scenario: End-to-end Noise IK handshake completes
    Tool: Bash (multiple terminals via tmux)
    Preconditions: fips daemon config exists, Swift relay compiled, APK installed
    Steps:
      1. Start fips daemon: cd /Users/macbook/src/fips && cargo run -- --config prototype/test-fips-config.toml &
      2. Start Swift relay: /tmp/fips-relay &
      3. Wait 3 seconds for both to initialize
      4. Start Android app: adb shell am start -n com.fipsdroid/.FipsActivity --ez auto_scan true
      5. Wait 30 seconds for BLE scan + connect + handshake
      6. Check Swift relay log: grep "phase 1\|phase 2\|msg1\|msg2" /tmp/fips-l2cap.log
      7. Check Android logcat: adb logcat -d -s FipsBridge:V | grep -i "established\|handshake"
      8. Check fips daemon log: grep -i "handshake" /tmp/fips-daemon.log
    Expected Result: msg1 (114 bytes) forwarded BLE→TCP, msg2 (69 bytes) forwarded TCP→BLE, both sides report handshake complete
    Failure Indicators: No BLE connection, relay doesn't forward, handshake timeout, "Error" state
    Evidence: .sisyphus/evidence/task-6-handshake.txt

  Scenario: Heartbeats exchange after handshake
    Tool: Bash
    Preconditions: Handshake completed (from scenario above)
    Steps:
      1. Wait 60 seconds after handshake completion
      2. Run: adb logcat -d -s FipsBridge:V | grep -i "heartbeat"
      3. Run: grep -i "heartbeat" /tmp/fips-daemon.log
    Expected Result: Android shows heartbeat_sent_count >= 1 AND heartbeat_received_count >= 1. Daemon shows heartbeat activity.
    Failure Indicators: Zero heartbeats, connection dropped after handshake, "Error" state
    Evidence: .sisyphus/evidence/task-6-heartbeats.txt

  Scenario: Error case — relay not running
    Tool: Bash
    Preconditions: fips daemon running, relay NOT running
    Steps:
      1. Start Android app: adb shell am start -n com.fipsdroid/.FipsActivity --ez auto_scan true
      2. Wait 15 seconds
      3. Check Android logcat: adb logcat -d -s FipsBridge:V | grep -i "error\|failed\|timeout"
    Expected Result: Android reports connection error or timeout (cannot find BLE peripheral)
    Failure Indicators: App crashes instead of reporting error gracefully
    Evidence: .sisyphus/evidence/task-6-no-relay-error.txt
  ```

  **Commit**: NO (verification only — no code changes)

---

- [ ] 7. Final Git Commit + Push + Issue Updates

  **What to do**:
  - In `fipsdroid` repo:
    1. `git add -A && git status` — review all changes
    2. Commit with message: `feat(fips): end-to-end FIPS bridge - Noise IK handshake over BLE relay`
    3. `git push origin main`
  - In `fips` repo:
    1. `git add -A && git status` — review all changes
    2. Commit with message: `feat(relay): BLE↔TCP relay + test config for Android FIPS bridge`
    3. `git push origin macos-support`
  - Update GitHub issues (if any open issues related to BLE/Android/FIPS bridge — check with `gh issue list`)
  - Create a summary comment on relevant issues documenting:
    - What was accomplished (BLE↔TCP relay, Noise IK handshake, heartbeats)
    - Architecture diagram (text)
    - How to reproduce (commands)

  **Must NOT do**:
  - Do NOT force push
  - Do NOT close issues unless explicitly confirmed working end-to-end
  - Do NOT commit evidence files to git (they're in .sisyphus/ which should be gitignored)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Git operations and GitHub issue updates
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 3 (after Task 6)
  - **Blocks**: F1-F4
  - **Blocked By**: Task 6

  **References**:
  - `fipsdroid` remote: `origin → git@github.com:Amperstrand/fipsdroid.git`
  - `fips` remote: `origin → git@github.com:Amperstrand/fips.git`

  **Acceptance Criteria**:
  - [ ] All changes committed in both repos
  - [ ] Both repos pushed to GitHub
  - [ ] `git status` clean in both repos
  - [ ] GitHub issues updated (if applicable)

  **QA Scenarios**:

  ```
  Scenario: All changes committed and pushed
    Tool: Bash
    Preconditions: End-to-end test passed
    Steps:
      1. Run: git -C /Users/macbook/src/fipsdroid status
      2. Run: git -C /Users/macbook/src/fips status
      3. Run: git -C /Users/macbook/src/fipsdroid log --oneline -3
      4. Run: git -C /Users/macbook/src/fips log --oneline -3
    Expected Result: Both repos show clean working tree, latest commits match expected messages, branches up to date with origin
    Failure Indicators: Uncommitted changes, unpushed commits, wrong branch
    Evidence: .sisyphus/evidence/task-7-git-final.txt
  ```

  **Commit**: YES (see "What to do" above for commit messages)

---

## Final Verification Wave (MANDATORY — after ALL implementation tasks)

> 4 review agents run in PARALLEL. ALL must APPROVE. Present consolidated results to user and get explicit "okay" before completing.

- [ ] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists (read file, curl endpoint, run command). For each "Must NOT Have": search codebase for forbidden patterns — reject with file:line if found. Check evidence files exist in .sisyphus/evidence/. Compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [ ] F2. **Code Quality Review** — `unspecified-high`
  Run `cargo check -p fipsdroid-core`, `cargo test -p fipsdroid-core`, `swiftc prototype/main.swift` (in fips repo). Review all changed files for: `as any`/`@ts-ignore`, empty catches, debug-only code left in prod, unused imports. Check AI slop: excessive comments, over-abstraction, generic variable names.
  Output: `Build [PASS/FAIL] | Tests [N pass/N fail] | Files [N clean/N issues] | VERDICT`

- [ ] F3. **Real Manual QA** — `unspecified-high`
  Start from clean state. Execute EVERY QA scenario from EVERY task — follow exact steps, capture evidence. Test cross-task integration (BLE→bridge→relay→daemon working together). Test edge cases: relay restart, APK force-stop and restart. Save to `.sisyphus/evidence/final-qa/`.
  Output: `Scenarios [N/N pass] | Integration [N/N] | Edge Cases [N tested] | VERDICT`

- [ ] F4. **Scope Fidelity Check** — `deep`
  For each task: read "What to do", read actual diff (git log/diff). Verify 1:1 — everything in spec was built (no missing), nothing beyond spec was built (no creep). Check "Must NOT do" compliance. Detect cross-task contamination: Task N touching Task M's files. Flag unaccounted changes.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

- **T0**: Push existing commits (no new commit)
- **T1**: `fix(bridge): skip pubkey exchange, use pre-configured peer key` — bridge.rs
- **T2**: `chore(config): add test keypairs and fips daemon TCP config` — config files
- **T3**: `feat(relay): upgrade Swift L2CAP probe to BLE↔TCP relay with FMP framing` — prototype/main.swift
- **T4+T5**: `feat(android): wire BLE to FipsDroidBridge with debug UI` — BridgeViewModel.kt, FipsActivity.kt, AndroidManifest.xml
- **T6**: `test(e2e): verify end-to-end Noise IK handshake and heartbeats` — evidence files
- **T7**: Final push + issue updates

---

## Success Criteria

### Verification Commands
```bash
# Rust tests pass
cargo test -p fipsdroid-core  # Expected: 15+ tests pass, 0 failures

# Swift relay compiles
swiftc /Users/macbook/src/fips/prototype/main.swift -o /tmp/fips-relay  # Expected: no errors

# Android APK builds
cd /Users/macbook/src/fipsdroid/android && ./gradlew assembleDebug  # Expected: BUILD SUCCESSFUL

# End-to-end handshake (check fips daemon logs)
grep "handshake" /tmp/fips-daemon.log  # Expected: handshake completed log line

# End-to-end heartbeat (check Android logcat)
adb logcat -d -s BleDemo:V | grep -i heartbeat  # Expected: heartbeat count > 0
```

### Final Checklist
- [ ] All "Must Have" present
- [ ] All "Must NOT Have" absent
- [ ] All Rust tests pass
- [ ] Swift relay compiles and runs
- [ ] Android APK installs and starts
- [ ] Noise IK handshake completes (verified in logs)
- [ ] Heartbeats exchanged (verified in logs)
- [ ] All commits pushed to GitHub
