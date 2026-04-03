# Wave 3 Integration Preparation Checklist

## Wave 3 Overview

**Tasks**: 11 (Android↔Rust Integration) + 12 (Debug UI) + 13 (Pre-Handshake Pubkey Exchange)

**Estimated Effort**: Deep integration work
**Parallel Execution**: YES (Tasks 11, 12, 13 can run in parallel)
**Critical Dependencies**:
- Task 6: BLE Transport ✅ Complete
- Task 7: Node Lifecycle ✅ Complete (verified: bridge.rs + node.rs implemented)
- Task 8: UniFFI Bridge ✅ Complete (verified: 3 bridge tests pass)
- Task 9: Android BLE Manager ✅ Complete (verified: BleConnectionManager, BlePermissions, RustBridge in place)
- Task 10: Cargo-ndk Cross-Compilation ✅ Complete (verified: .cargo/config.toml + build-android.sh)

**Completion Status**:
- [x] Task 6: BLE Transport - Complete
- [x] Task 7: Node Lifecycle - Complete
- [x] Task 8: UniFFI Bridge - Complete
- [x] Task 9: Android BLE Manager - Complete
- [x] Task 10: Cargo-ndk - Complete
- [ ] Task 11: Android↔Rust Integration
- [ ] Task 12: Debug UI - Connection State Display
- [ ] Task 13: Pre-Handshake Pubkey Exchange

## Wave 3 Preparation

### Pre-Integration Checks

1. **Cross-Compilation Verification**
   - [ ] Run `cargo ndk -t aarch64-linux-android build` produces `.so` file
   - [ ] Verify `.so` file is valid ARM64 ELF: `file target/aarch64-linux-android/debug/libfipsdroid_core.so`
   - [ ] Copy `.so` to `android/app/src/main/jniLibs/arm64-v8a/`

2. **UniFFI Bindings Generation**
   - [ ] Run UniFFI bindgen to generate Kotlin sources from compiled library
   - [ ] Verify generated Kotlin classes exist in `android/app/src/main/java/`
   - [ ] Test Kotlin compilation: `./gradlew assembleDebug`

3. **Android Project Configuration**
   - [ ] Update `android/app/build.gradle.kts` to load native library
   - [ ] Add `System.loadLibrary("fipsdroid_core")` in Application class
   - [ ] Verify build script copies `.so` files to correct jniLibs directories

### Task 11: Android↔Rust Integration

**What to Wire Up**:
- MainActivity or ViewModel creates `FipsDroidBridge` instance
- Implement `FipsDroidCallback` interface to receive state changes and heartbeats
- BleConnectionManager reads from L2CAP socket → feeds bytes to Rust bridge
- Rust writes bytes → BleConnectionManager writes to L2CAP socket
- Handle lifecycle: Activity stop → bridge.stop() → BLE disconnect

**Key Integration Points**:
```kotlin
// In ViewModel:
val bridge = FipsDroidBridge.new(peerAddress, peerPubkey, localPrivkey)
val connectionState = StateFlow<ConnectionState>(ConnectionState.Disconnected)

// BleConnectionManager:
fun connect(address: String) {
    val socket = device.createInsecureL2capChannel(PSM)
    // Start byte read/write loops
    // Pass bytes to bridge methods
}

// In MainActivity:
val viewModel = ViewModelProvider(this)[FipsDroidViewModel::class.java]
```

**QA Verification**:
- [ ] App builds with native library: `./gradlew assembleDebug`
- [ ] APK contains `libfipsdroid_core.so`
- [ ] App launches without UnsatisfiedLinkError
- [ ] Logcat shows "FipsDroidBridge initialized"
- [ ] Initial state shows "Disconnected"

### Task 12: Debug UI — Connection State Display

**What to Build**:
- DebugScreen.kt with:
  - Peer address display (hardcoded)
  - Connection state indicator (red/yellow/green dots)
  - Heartbeat counter (Sent/Received/Last timestamp)
  - Connect/Disconnect button
  - Error message display
  - Raw log view (last 50 lines)
- ConnectionStateIndicator.kt (reusable component)

**Wire to ViewModel**:
```kotlin
val connectionState by viewModel.connectionState.collectAsState()
val heartbeatStatus by viewModel.heartbeatStatus.collectAsState()
```

**QA Verification**:
- [ ] UI renders all elements on launch
- [ ] State indicator shows Disconnected (red) initially
- [ ] Connect button enables when state is Disconnected
- [ ] State updates on connection attempt (Yellow = Connecting/Handshaking)
- [ ] State updates to Green = Established
- [ ] Heartbeat counters increment when heartbeats received

### Task 13: Pre-Handshake Pubkey Exchange

**What to Implement**:
- Create `crates/fipsdroid-core/src/transport/pubkey.rs`
- Implement protocol: send `[0x00][32-byte local pubkey]`, receive `[0x00][32-byte remote pubkey]`
- Validate: exactly 33 bytes, first byte 0x00, remaining 32 bytes = pubkey
- Integrate with Node lifecycle: connect → pubkey exchange → Node.run()

**Tests to Write**:
- Valid pubkey exchange format
- Invalid first byte → error
- Wrong length message → error
- Timeout on receive → error

**QA Verification**:
- [ ] Pubkey module compiles
- [ ] Tests pass: `cargo test -p fipsdroid-core transport::pubkey`
- [ ] Pubkey exchange works through mock transport

## Wave 3 Execution Strategy

### Parallel Execution
Tasks 11, 12, 13 can be executed in parallel:
- Task 11: Integration layer (bridges everything)
- Task 12: UI (doesn't depend on integration completion)
- Task 13: Protocol detail (depends on Task 6/7 transport node)

### Recommended Agent Profile
- **Task 11**: `deep` (integration complexity)
- **Task 12**: `visual-engineering` (UI creation)
- **Task 13**: `unspecified-high` (protocol wire format)

## Post-Integration Verification

After Wave 3 completes, we will have:
1. ✅ Bridge wired to BLE manager
2. ✅ UI showing connection state and heartbeats
3. ✅ Pubkey exchange before Noise IK handshake
4. Ready for Wave 4: End-to-End test with upstream fips

## Next Steps

1. **Verify cross-compilation** works (Task 10 pre-check)
2. **Generate UniFFI bindings** from compiled Rust code
3. **Prepare Android project** for native library loading
4. **Dispatch Wave 3 tasks** (11, 12, 13) for parallel execution
5. **Run Wave 4 verification** after integration complete

---

**Status**: Ready for Wave 3 execution
**Estimated Completion**: Multi-day (integration complexity)
**Risk**: High (FFI boundary, threading model)
