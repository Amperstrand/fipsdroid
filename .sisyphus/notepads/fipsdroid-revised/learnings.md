# Learnings

## Task 14: Fork jmcorgan/fips + macOS Build Verification

### Completed Actions
- ✅ Forked `jmcorgan/fips` to `Amperstrand/fips` (already existed)
- ✅ Cloned to `~/src/fips`
- ✅ Created branch `feature/corebluetooth`

### Code Structure Verification
- ✅ All 4 BLE traits verified in `src/transport/ble/io.rs`:
  - `pub trait BleStream` (line 16)
  - `pub trait BleAcceptor` (line 53)
  - `pub trait BleScanner` (line 53)
  - `pub trait BleIo` (line 67)

### Cargo.toml Verification
- ✅ Feature flags confirmed:
  - `default = ["tui", "ble"]`
  - `tui = ["dep:ratatui"]`
  - `ble = ["dep:bluer"]`

### Build Issues (macOS)
- ❌ **Cargo build fails due to tun/netlink incompatibility**
  - `tun = { version = "0.8.5", features = ["async"] }` pulls in `netlink-sys`
  - `netlink-sys` uses Linux-specific constants (PF_NETLINK, SOCK_CLOEXEC, etc.)
  - These don't exist in macOS's `libc` (uses PF_HYLINK, O_CLOEXEC instead)

### Resolution Strategy
- Replace `tun` with `tun2` (macOS-compatible)
- Update `rtnetlink` to use `netlink-packet-route` with platform guards
- Or implement custom TUN device on macOS using `tun2` crate

### Links
- Fork: https://github.com/Amperstrand/fips
- Branch: `feature/corebluetooth`

## Task 12: Debug UI — Connection State Display

### Completed Actions
- ✅ Created `ConnectionStateIndicator.kt` with colored circle indicator + state label
- ✅ Created `DebugScreen.kt` with:
  - Peer address display (hardcoded)
  - ConnectionStateIndicator for state visualization
  - HeartbeatStatus counter with time formatting
  - Connect/Disconnect buttons with visibility logic
  - Error message display (conditional)
  - Optional log view (LazyColumn, last 50 lines)
- ✅ Created `DebugScreenState` class for state management (placeholder until Task 11 ViewModel is wired)
- ✅ Updated `MainActivity.kt` to use DebugScreen with BlePermissionHandler
- ✅ Temporarily disabled `FipsDroidViewModel.kt` (depends on UniFFI bindings from Task 11)

### ConnectionState Color Mapping
- **Red** (`MaterialTheme.colorScheme.error`): Disconnected, Error
- **Yellow/Amber** (`0xFFFFC107`): Connecting, Connected, Handshaking
- **Orange** (`0xFFFF9800`): Disconnecting
- **Green** (`0xFF4CAF50`): Established

### HeartbeatStatus Formatting
- `formatLastReceived()` converts epoch seconds to "Xs ago", "Xm ago", "Xh ago", or "Never"
- Uses `java.time.Instant` and `java.time.Duration` for time calculations

### Button Visibility Logic
- **Connect button**: Shown when `Disconnected` or `Error`
- **Disconnect button**: Shown when `Connected`, `Established`, `Connecting`, or `Handshaking`

### Build Verification
- ✅ `./gradlew assembleDebug` succeeds
- APK created at: `android/app/build/outputs/apk/debug/app-debug.apk`

### Notes
- Kotlin sealed class used for ConnectionState to match Rust enum semantics (exhaustive when expressions)
- Material3 Card components used for consistent styling
- `FontFamily.Monospace` for peer address and log display
- State management via `remember { mutableStateOf() }` pattern
- Permission handling via existing `BlePermissionHandler` composable

## Task 11: Android↔Rust Integration — ViewModel + Bridge Wiring

### Completed Actions
- ✅ Updated `bridge.rs` with channel-based byte I/O:
  - `feed_incoming(data: Vec<u8>)` - push bytes from Kotlin to Rust transport
  - `poll_outgoing() -> Option<Vec<u8>>` - pull bytes from Rust transport to Kotlin
- ✅ Created `FipsDroidViewModel.kt` with:
  - StateFlows for `connectionState`, `heartbeatStatus`, `logLines`
  - Bridge instance management
  - Byte relay coroutine between BLE L2CAP and Rust bridge
- ✅ Updated `MainActivity.kt` to use ViewModel with Compose state collection
- ✅ Created `FipsDroidViewModelFactory` for dependency injection
- ✅ All 17 Rust tests pass

### Bridge Architecture
```
Kotlin (ViewModel)                 Rust (bridge.rs)
    |                                    |
    | feed_incoming(data)  --------->  incoming_tx (mpsc::Sender)
    |                                    |     |
    |                                    |     v
    |                              BleTransport (incoming_rx)
    |                                    |
    |                              FipsDroidNode.run()
    |                                    |
    |                              BleTransport (outgoing_tx)
    |                                    |     |
    | poll_outgoing()  <-------------  outgoing_rx (mpsc::Receiver)
```

### Key Implementation Details

**Compressed Pubkey Format Conversion:**
- `ecdh_pubkey()` returns `[u8; 33]` (compressed format: prefix + 32 bytes)
- `send_pubkey()` expects `&[u8; 32]`
- Fix: Extract 32 bytes from compressed pubkey: `local_pubkey[1..]`

**Callback Access Pattern:**
- `Box<dyn FipsDroidCallback>` does NOT implement Clone
- Cannot move callback into spawned task
- Solution: Access via `inner_clone.lock().callback.as_ref()`

**NoiseError Formatting:**
- NoiseError doesn't implement Display
- Use `{:?}` debug format instead of `{}`

**ViewModel Byte Relay:**
- Poll-based loop (10ms interval)
- Read available bytes from BLE InputStream → `bridge.feedIncoming()`
- Poll `bridge.pollOutgoing()` → write to BLE OutputStream
- Proper cancellation handling with `isActive` check

### Build Verification
- ✅ `cargo test -p fipsdroid-core` passes (17 tests)
- ✅ `cargo build -p fipsdroid-core` passes

### Android Build Status
- ⚠️ Requires UniFFI-generated Kotlin bindings (`uniffi.fipsdroid_core.*`)
- ⚠️ Requires `lifecycle-runtime-compose` dependency for `collectAsStateWithLifecycle`
- Build will succeed once native library is compiled and bindings generated

### Dependencies Added
- `androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2` (already present)

### Code Patterns
- StateFlow → Compose state: `collectAsStateWithLifecycle()`
- ViewModel factory pattern for parameter injection
- Extension functions for type mapping (Rust → UI types)
- Coroutine-based I/O relay with proper lifecycle management
