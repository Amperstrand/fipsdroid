# BLE Hello World: Mac ↔ Android Minimal Demo

## TL;DR

> **Quick Summary**: Prove Mac ↔ Android BLE communication works by building a Swift L2CAP peripheral on Mac that advertises and echoes bytes, and a pure Kotlin Android app that scans, connects, and exchanges a PING/PONG message — no Rust, no FIPS protocol, no UniFFI.
>
> **Deliverables**:
> - Swift BLE peripheral on Mac: advertises service UUID, accepts L2CAP connection, echoes bytes
> - Android APK: scans for Mac's service UUID, connects via L2CAP, sends PING, receives PONG
> - Log evidence from both sides proving bidirectional byte transfer
> - Git commits at each milestone
>
> **Estimated Effort**: Medium (4-8 hours)
> **Parallel Execution**: YES — 2 waves (Mac + Android in parallel after shared foundation)
> **Critical Path**: Swift probe fix → Android APK rebuild (no native .so needed) → BLE scan test → L2CAP connection → PING/PONG exchange

---

## Context

### Original Request
User wants a minimal viable demo proving Mac and Android can communicate over BLE. "Even just a simple hello world BLE test where we can prove that the Mac is talking to the phone over bluetooth would be a big win." Evidence must be captured — compile, install APK, show logs from both sides.

### Prior Work (What Already Exists)
- **Swift L2CAP probe** (`~/src/fips/prototype/main.swift`): Compiles and runs, publishes L2CAP channel, BUT **does NOT advertise BLE** and has **no connection handling**. Must be extended.
- **Android project** (`~/src/fipsdroid/android/`): Full Kotlin/Compose app with BLE permissions, DebugScreen UI, BleConnectionManager with L2CAP socket I/O. APK builds and installs but crashes because it tries to load Rust native libs (.so) that don't exist.
- **BleConnectionManager** (`BleConnectionManager.kt`): Has `connect(address, psm)` method that creates L2CAP channel — but requires a known MAC address (no scanning).
- **BLE permissions** (`BlePermissions.kt`): Fully implemented — BLUETOOTH_CONNECT, BLUETOOTH_SCAN, ACCESS_FINE_LOCATION.
- **Rust core**: 17/17 tests pass, but NOT needed for this demo.
- **cargo-ndk v4.1.2**: Installed, but NOT needed for this demo (no Rust bridge).
- **Samsung SM-G991B (Galaxy S21)**: Connected via ADB (may need reconnection).
- **Mac Bluetooth**: ON, Address 14:7D:DA:7D:4C:31, Chipset BCM_4364B3.

### Interview Summary
**Key Discussions**:
- NDK is NOT installed, but user chose manual download approach
- Metis identified that NDK/Rust/.so is unnecessary for a minimal BLE demo — pure Kotlin is sufficient
- Swift probe is missing `startAdvertising()` and connection handling — critical gap
- Android has no BLE scanning code — needs `BluetoothLeScanner`
- User wants git commits at each success/failure milestone

**Research Findings**:
- Swift probe at `main.swift:18` calls `publishL2CAPChannel` but never `startAdvertising` — Android can't discover Mac
- `BleConnectionManager.kt:90` uses `createInsecureL2capChannel(psm)` — correct API for L2CAP CoC
- APK crash is caused by missing `jniLibs/*.so` — but if we remove the Rust bridge dependency, APK won't crash
- Service UUID defined as `9C90B790-2CC5-42C0-9F87-C9CC40648F4C`, PSM `0x0085`

### Metis Review
**Identified Gaps (addressed)**:
- **Swift probe doesn't advertise**: Added `startAdvertising` + connection handler + echo logic to plan
- **No BLE scanning on Android**: Added `BluetoothLeScanner` implementation to plan  
- **Rust bridge crash**: Solved by removing Rust dependency for demo — pure Kotlin L2CAP
- **ADB device disconnected**: Added reconnection verification step
- **Mac BLE address randomization**: Using service UUID scanning instead of hardcoded MAC address
- **No specific verification criteria**: Added concrete PING/PONG byte sequence with exact log messages

---

## Work Objectives

### Core Objective

Prove bidirectional BLE communication between Mac and Android by building the simplest possible demo: Mac advertises a BLE service with L2CAP channel, Android discovers it and exchanges bytes.

### Concrete Deliverables

1. **Extended Swift BLE peripheral** (`~/src/fips/prototype/main.swift`) — advertises, accepts L2CAP connection, echoes bytes, logs to file
2. **Android BLE demo** — new `BleDemoActivity.kt` with scanning + L2CAP connection + PING/PONG exchange
3. **Working APK** — installs and launches without crash (no Rust .so dependency)
4. **Evidence files** — Mac log at `/tmp/fips-l2cap.log`, Android logcat capture, screenshots

### Definition of Done

- [ ] Mac Swift probe advertises BLE service UUID `9C90B790-2CC5-42C0-9F87-C9CC40648F4C`
- [ ] Mac Swift probe accepts L2CAP connection and echoes received bytes
- [ ] Android APK installs and launches without crash on Samsung SM-G991B
- [ ] Android discovers Mac's BLE advertisement (logged in logcat)
- [ ] Android connects to Mac over L2CAP CoC
- [ ] Android sends "PING" (4 bytes) → Mac receives and logs → Mac sends "PONG" → Android receives and logs
- [ ] Git history shows atomic commits at each milestone

### Must Have

- Swift peripheral with BLE advertisement + L2CAP + echo
- Android BLE scanning filtered by service UUID
- Android L2CAP connection using `createInsecureL2capChannel`
- Bidirectional byte transfer with logged evidence
- File-based logging on Mac side (stdout capture is unreliable with RunLoop)
- Git commits at each milestone

### Must NOT Have (Guardrails)

- ❌ Rust native library (.so) or cargo-ndk build — pure Kotlin demo
- ❌ UniFFI bridge integration — not needed for hello world
- ❌ FIPS protocol, Noise handshake, pubkey exchange — Phase 2
- ❌ ViewModel architecture — direct Activity code is fine
- ❌ Production error handling — basic try/catch + log only
- ❌ Multi-connection support — single peer only
- ❌ Background operation — foreground only
- ❌ Polished UI — logcat + simple text display
- ❌ Auto-reconnection logic
- ❌ Custom MTU negotiation
- ❌ NDK installation (not needed for pure Kotlin demo)
- ❌ Modifying existing Rust core, UniFFI bridge, or ViewModel code

---

## Verification Strategy (MANDATORY)

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed. No exceptions.

### Test Decision
- **Infrastructure exists**: YES (JUnit in build.gradle.kts)
- **Automated tests**: NO — this is a hardware integration demo; verification is via BLE communication evidence
- **Framework**: N/A for unit tests; `adb logcat` + file logging for integration evidence

### QA Policy
Every task MUST include agent-executed QA scenarios.
Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

- **Mac Swift probe**: Use `interactive_bash` (tmux) — run probe, capture log file output
- **Android APK**: Use Bash (`adb shell`, `adb logcat`) — install, launch, capture logs
- **BLE integration**: Use both tmux (Mac probe) + adb logcat (Android) — prove bidirectional comms

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Start Immediately — Mac + Android in parallel):
├── Task 1: Extend Swift L2CAP probe with advertisement + echo [deep]
├── Task 2: Create Android BleDemoActivity with scanning + L2CAP [deep]
├── Task 3: Remove Rust .so dependency from APK build [quick]
└── Task 4: Verify ADB device connection + reconnect if needed [quick]

Wave 2 (After Wave 1 — integration testing):
├── Task 5: Build APK + install on device [quick]
├── Task 6: End-to-end BLE test: Mac ↔ Android PING/PONG [deep]
└── Task 7: Document results + git commit evidence [quick]

Wave FINAL (After ALL tasks — verification):
├── Task F1: Plan compliance audit (oracle)
├── Task F2: Code quality review (unspecified-high)
├── Task F3: Real manual QA (unspecified-high)
└── Task F4: Scope fidelity check (deep)
-> Present results -> Get explicit user okay

Critical Path: Task 1 → Task 5 → Task 6 → Task 7 → F1-F4 → user okay
Parallel Speedup: Wave 1 runs 4 tasks concurrently
Max Concurrent: 4 (Wave 1)
```

### Dependency Matrix

| Task | Depends On | Blocks | Wave |
|------|-----------|--------|------|
| 1 | — | 6 | 1 |
| 2 | — | 5 | 1 |
| 3 | — | 5 | 1 |
| 4 | — | 5, 6 | 1 |
| 5 | 2, 3, 4 | 6 | 2 |
| 6 | 1, 5 | 7 | 2 |
| 7 | 6 | F1-F4 | 2 |

### Agent Dispatch Summary

- **Wave 1**: **4** — T1 → `deep`, T2 → `deep`, T3 → `quick`, T4 → `quick`
- **Wave 2**: **3** — T5 → `quick`, T6 → `deep`, T7 → `quick`
- **FINAL**: **4** — F1 → `oracle`, F2 → `unspecified-high`, F3 → `unspecified-high`, F4 → `deep`

---

## TODOs

---

## Final Verification Wave (MANDATORY — after ALL implementation tasks)

> 4 review agents run in PARALLEL. ALL must APPROVE. Present consolidated results to user and get explicit "okay" before completing.

- [ ] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists (read file, run command). For each "Must NOT Have": search codebase for forbidden patterns — reject with file:line if found. Check evidence files exist in `.sisyphus/evidence/`. Compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [ ] F2. **Code Quality Review** — `unspecified-high`
  Run `./gradlew assembleDebug` + `swiftc` compile check. Review all changed files for: `as any`/`@ts-ignore`, empty catches, console.log in prod, commented-out code, unused imports. Check AI slop: excessive comments, over-abstraction, generic names.
  Output: `Build [PASS/FAIL] | Files [N clean/N issues] | VERDICT`

- [ ] F3. **Real Manual QA** — `unspecified-high`
  Start from clean state. Execute EVERY QA scenario from EVERY task — follow exact steps, capture evidence. Test cross-task integration. Save to `.sisyphus/evidence/final-qa/`.
  Output: `Scenarios [N/N pass] | Integration [N/N] | VERDICT`

- [ ] F4. **Scope Fidelity Check** — `deep`
  For each task: read "What to do", read actual diff (git log/diff). Verify 1:1 — everything in spec was built (no missing), nothing beyond spec was built (no creep). Check "Must NOT do" compliance. Flag unaccounted changes.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | VERDICT`

---

## Commit Strategy

| Commit | Type | Message | Files | Pre-commit Check |
|--------|------|---------|-------|------------------|
| 1 | feat | `feat(mac): add BLE advertisement + L2CAP echo to Swift probe` | `~/src/fips/prototype/main.swift` | `swiftc main.swift -framework CoreBluetooth -framework Foundation -o l2cap-test` |
| 2 | feat | `feat(android): add BleDemoActivity with BLE scanning + L2CAP` | `BleDemoActivity.kt`, `AndroidManifest.xml` | `./gradlew assembleDebug` |
| 3 | fix | `fix(android): remove Rust .so dependency for demo build` | `build.gradle.kts`, `MainActivity.kt` or related | `./gradlew assembleDebug` |
| 4 | feat | `feat(demo): end-to-end BLE PING/PONG evidence` | Evidence files, documentation | N/A |

---

## Success Criteria

### Verification Commands
```bash
# Mac: Swift probe compiles
cd ~/src/fips/prototype && swiftc main.swift -framework CoreBluetooth -framework Foundation -o l2cap-test  # Expected: compiles with 0 errors

# Mac: Probe runs and advertises (check log file)
cat /tmp/fips-l2cap.log  # Expected: "Powered on", "Published PSM: X", "Advertising started"

# Android: APK builds
cd ~/src/fipsdroid/android && ./gradlew assembleDebug  # Expected: BUILD SUCCESSFUL

# Android: APK installs
adb install -r app/build/outputs/apk/debug/app-debug.apk  # Expected: Success

# Android: App launches without crash
adb shell am start -n com.fipsdroid/.BleDemoActivity  # Expected: no crash in logcat

# Android: BLE scan finds Mac
adb logcat -s BleDemo | grep "Found device"  # Expected: shows Mac's address

# Android: L2CAP connected
adb logcat -s BleDemo | grep "L2CAP connected"  # Expected: "L2CAP connected to <address>"

# Bidirectional: PING/PONG
adb logcat -s BleDemo | grep -E "Sent|Received"  # Expected: "Sent: PING" then "Received: PONG"
cat /tmp/fips-l2cap.log | grep -E "Received|Sent"  # Expected: "Received: PING" then "Sent: PONG"
```

### Final Checklist
- [ ] All "Must Have" present
- [ ] All "Must NOT Have" absent
- [ ] Swift probe compiles and advertises
- [ ] APK builds, installs, launches without crash
- [ ] BLE scan discovers Mac
- [ ] L2CAP connection established
- [ ] PING/PONG exchange verified in logs on both sides
- [ ] Git commits at each milestone
