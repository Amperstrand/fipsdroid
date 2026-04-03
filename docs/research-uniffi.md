# UniFFI Async and Callback Patterns Research

## Overview

UniFFI is Mozilla's toolkit for building cross-platform software components in Rust, generating FFI bindings for multiple languages including Kotlin. This research documents async configuration, callback interfaces, and FFI error propagation patterns.

## Async Configuration

### Runtime Configuration

#### UniFFI Export Attribute

```rust
#[uniffi::export(async_runtime = "tokio")]
pub async fn connect_l2cap(device_id: String) -> Result<ConnectionHandle, L2CapError> {
    // Async implementation using tokio
    tokio::task::spawn(async move {
        // L2CAP connection logic
    }).await?
    Ok(handle)
}
```

**Key Points:**
- `async_runtime = "tokio"` tells UniFFI to use tokio runtime for this function
- Alternative runtimes: `"async_std"` (older), `"tokio"` (default)
- Must configure tokio runtime in Kotlin bindings

#### Kotlin Bindings Configuration

**uniffi.toml configuration:**
```toml
[bindings.kotlin]
# Configure Kotlin target version
kotlin_target_version = "1.8"

# Enable Android optimizations
android = true

# Use SystemCleaner for better Android support
android_cleaner = "android"

# Custom package mapping if needed
[bindings.kotlin.external_packages]
# Map Rust crates to Kotlin packages
```

### Async Function Mapping

**Rust to Kotlin Conversion:**

```rust
// Rust - Async function
#[uniffi::export(async_runtime = "tokio")]
pub async fn read_l2cap_data(handle: ConnectionHandle) -> Result<Vec<u8>, L2CapError> {
    // Simulate async read operation
    tokio::time::sleep(Duration::from_millis(100)).await;
    Ok(vec
![0x01, 0x02, 0x03])
}
```

```kotlin
// Kotlin - Generated code (simplified)
suspend fun readL2capData(handle: ConnectionHandle): Result<List<Byte>> {
    // Generated coroutine for FFI call
    return kotlinx.coroutines.withContext(Dispatchers.IO) {
        // FFI call implementation
    }
}
```

**Key Mapping Rules:**
1. `async fn` → Kotlin `suspend fun`
2. Returns `Future<T>` → Returns `T` (automatically awaited)
3. Async errors → Wrapped in `Result<T, Error>`
4. Must run on IO dispatcher (Dispatchers.IO for Android)

### Async Runtime Requirements

**Rust Side:**
```toml
[dependencies]
uniffi = { version = "0.31", features = ["tokio"] }
tokio = { version = "1.28", features = ["full"] }
```

**Kotlin Side:**
```kotlin
// Ensure tokio runtime is initialized
// Usually done by UniFFI-generated code
// But manual setup may be needed for complex scenarios

val uniffiRuntime = UniffiRuntime()
```

## Callback Interface Pattern

### Defining Callback Interface in Rust

```rust
// Callback trait for receiving L2CAP data updates
#[uniffi::export(callback_interface)]
pub trait L2CapDataCallback {
    fn on_data(&self, data: Vec<u8>);
    fn on_error(&self, error: L2CapError);
    fn on_connected(&self);
    fn on_disconnected(&self);
}

// Usage with async streaming
#[uniffi::export]
pub struct L2CapClient {
    device: BluetoothDevice,
    callback: Box<dyn L2CapDataCallback>,
}

#[uniffi::export]
impl L2CapClient {
    #[uniffi::export(async_runtime = "tokio")]
    pub async fn start_streaming(&self) -> Result<(), L2CapError> {
        // Spawn background task for streaming
        tokio::spawn(async move {
            loop {
                // Read data from L2CAP socket
                let data = read_from_l2cap().await?;

                // Call callback with data
                self.callback.on_data(data);

                // Check for errors
                if let Err(e) = &data {
                    self.callback.on_error(e.clone());
                    break;
                }
            }
        });

        Ok(())
    }
}
```

### UDL Definition for Callback Interface

```webidl
// Callback interface definition in UDL
callback interface L2CapDataCallback {
    void onData(array<octet> data);
    void onError(L2CapError error);
    void onConnected();
    void onDisconnected();
};

interface L2CapClient {
    constructor(BluetoothDevice device, L2CapDataCallback callback);
    [Async]
    Future<void> startStreaming();
};
```

### Kotlin Implementation of Callback Interface

```kotlin
// Kotlin class implementing the callback interface
class FipsL2CapCallback : L2CapDataCallback {

    override fun onData(data: List<Byte>) {
        // Process incoming L2CAP data
        val byteArray = ByteArray(data.size)
        data.forEachIndexed { index, byte ->
            byteArray[index] = byte
        }

        // Example: Log to console
        Log.d(TAG, "Received L2CAP data: ${data.joinToString(", ")}")

        // Example: Forward to higher-level handler
        handleL2capData(byteArray)
    }

    override fun onError(error: L2CapError) {
        Log.e(TAG, "L2CAP error: ${error.message}", error.cause)

        // Example: Report error to UI
        reportErrorToUI(error)
    }

    override fun onConnected() {
        Log.d(TAG, "L2CAP connected")

        // Example: Update UI to show connected state
        updateConnectionStatus(Connected)
    }

    override fun onDisconnected() {
        Log.d(TAG, "L2CAP disconnected")

        // Example: Clean up and notify
        cleanupConnection()
        updateConnectionStatus(Disconnected)
    }

    companion object {
        private const val TAG = "FipsL2CapCallback"
    }
}

// Usage in client code
fun connectFipsDevice(device: BluetoothDevice) {
    val callback = FipsL2CapCallback()

    // Pass callback to FFI Rust code
    val client = L2CapClient(device, callback)

    // Start streaming asynchronously
    lifecycleScope.launch(Dispatchers.IO) {
        client.startStreaming().getOrNull()
    }
}
```

## Error Propagation Across FFI

### Defining Custom Error Type

```rust
use thiserror::Error;

#[derive(Error, Debug)]
pub enum L2CapError {
    #[error("Device not connected: {0}")]
    NotConnected(String),

    #[error("Connection timeout after {0}ms")]
    Timeout(u64),

    #[error("Invalid PSM: {0}")]
    InvalidPsm(u16),

    #[error("IO error: {0}")]
    IoError(#[from] std::io::Error),

    #[error("Bluetooth permission denied")]
    PermissionDenied,

    #[error("Unknown error: {0}")]
    Unknown(String),
}

// Export error for FFI
#[derive(uniffi::Error)]
pub enum UniFFIL2CapError {
    NotConnected { message: String },
    Timeout { duration_ms: u64 },
    InvalidPsm { psm: u16 },
    IoError { message: String },
    PermissionDenied,
    Unknown { message: String },
}

#[uniffi::export]
impl UniFFIL2CapError {
    pub fn message(&self) -> String {
        self.to_string()
    }
}
```

### Throwing Errors from Rust to Kotlin

**Rust side:**
```rust
#[uniffi::export]
pub fn connect_device(device_id: String) -> Result<(), UniFFIL2CapError> {
    // Check if device is connected
    if !device.is_connected() {
        return Err(UniFFIL2CapError::NotConnected {
            message: "Device not connected".to_string()
        });
    }

    // Check for permission
    if !has_permission() {
        return Err(UniFFIL2CapError::PermissionDenied);
    }

    // Perform connection
    match connect_to_device(device_id).await {
        Ok(handle) => {
            // Connection successful
            Ok(())
        }
        Err(e) => Err(UniFFIL2CapError::IoError {
            message: e.to_string()
        }),
    }
}
```

**Kotlin side (generated FFI code):**
```kotlin
// Generated by UniFFI - automatically handles error propagation
suspend fun connectDevice(deviceId: String): Result<Unit, UniFFIL2CapError> {
    return kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            // FFI call
            uniffi_example_library_connect_device(deviceId)

            Result.success(Unit)
        } catch (e: Exception) {
            // Convert to UniFFI error
            Result.failure(e.toUniFFIL2CapError())
        }
    }
}

// Manual conversion (if needed)
private fun Exception.toUniFFIL2CapError(): UniFFIL2CapError {
    return when {
        this is SecurityException -> UniFFIL2CapError.PermissionDenied
        this.message?.contains("timeout") == true -> {
            UniFFIL2CapError.Timeout {
                duration_ms = 5000
            }
        }
        else -> UniFFIL2CapError.Unknown {
            message = this.message ?: "Unknown error"
        }
    }
}
```

### Handling Unexpected Errors

**Foreign Trait Implementation:**
```rust
// If implementing foreign trait (not callback interface)
impl ForeignTrait for MyForeignTraitImpl {
    fn method(&self, arg: u32) -> Result<u32, uniffi::UnexpectedUniFFICallbackError> {
        // Implement logic
        Ok(arg + 1)
    }
}

// Kotlin side - implement error handling
class MyForeignTraitImpl : ForeignTrait {
    override fun method(arg: Int): Result<Int> {
        return try {
            // Call FFI
            uniffi_example_library_foreign_trait_method(arg)
            Result.success(arg + 1)
        } catch (e: Exception) {
            // Convert unexpected errors to our error type
            Result.failure(UniFFIL2CapError.Unknown {
                message = e.message ?: "Unknown error"
            })
        }
    }
}
```

## Async Streaming with Callbacks

### Complete Pattern: Heartbeat Status Updates

```rust
use tokio::sync::broadcast;
use std::sync::Arc;

// Callback interface for heartbeat status
#[uniffi::export(callback_interface)]
pub trait HeartbeatCallback {
    fn on_heartbeat(&self, status: HeartbeatStatus);
    fn on_connection_lost(&self);
}

#[uniffi::export]
pub struct FipsDevice {
    device: BluetoothDevice,
    callback: Arc<Mutex<Box<dyn HeartbeatCallback>>>,
}

#[uniffi::export]
impl FipsDevice {
    #[uniffi::export(async_runtime = "tokio")]
    pub async fn start_heartbeat(&self) -> Result<(), L2CapError> {
        // Create broadcast channel for heartbeat events
        let (tx, mut rx) = broadcast::channel(100);

        // Spawn background task for heartbeat
        tokio::spawn(async move {
            loop {
                // Send heartbeat data
                tx.send(HeartbeatStatus {
                    timestamp: Instant::now().as_millis(),
                    battery_level: get_battery_level(),
                    signal_strength: get_signal_strength(),
                }).await;

                // Wait for next heartbeat
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
        });

        // Spawn task to forward heartbeats to callback
        tokio::spawn(async move {
            while let Ok(status) = rx.recv().await {
                let callback = callback.lock().await;

                // Call Kotlin callback
                callback.on_heartbeat(status);

                if !callback.is_connected() {
                    break; // Callback disconnected
                }
            }
        });

        Ok(())
    }
}
```

**Kotlin implementation:**
```kotlin
class FipsHeartbeatCallback : HeartbeatCallback {

    override fun onHeartbeat(status: HeartbeatStatus) {
        // Update UI with heartbeat data
        runOnUiThread {
            updateBatteryLevel(status.batteryLevel)
            updateSignalStrength(status.signalStrength)
            updateHeartbeatTimestamp(status.timestamp)
        }
    }

    override fun onConnectionLost() {
        runOnUiThread {
            Log.e(TAG, "Connection lost, calling cleanup")
            cleanupAndDisconnect()
        }
    }

    companion object {
        private const val TAG = "FipsHeartbeat"
    }
}

// Usage
fun startFipsHeartbeat(device: BluetoothDevice) {
    val callback = FipsHeartbeatCallback()
    val fipsDevice = FipsDevice(device, callback)

    lifecycleScope.launch(Dispatchers.IO) {
        try {
            fipsDevice.startHeartbeat().getOrThrow()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start heartbeat", e)
            handleConnectionError(e)
        }
    }
}
```

## Error Handling Best Practices

### 1. Always Return `Result<T, Error>`

```rust
// CORRECT
#[uniffi::export]
pub fn connect(psm: u16) -> Result<ConnectionHandle, L2CapError> {
    if !is_valid_psm(psm) {
        return Err(L2CapError::InvalidPsm(psm));
    }
    Ok(ConnectionHandle::new(psm))
}

// WRONG
#[uniffi::export]
pub fn connect(psm: u16) -> ConnectionHandle {
    // Crash on error!
    ConnectionHandle::new(psm)
}
```

### 2. Implement `From` for Error Conversions

```rust
// Convert std::io::Error to UniFFI error
impl From<std::io::Error> for UniFFIL2CapError {
    fn from(err: std::io::Error) -> Self {
        UniFFIL2CapError::IoError {
            message: err.to_string()
        }
    }
}

// Convert anyhow::Error
impl From<anyhow::Error> for UniFFIL2CapError {
    fn from(err: anyhow::Error) -> Self {
        UniFFIL2CapError::Unknown {
            message: err.to_string()
        }
    }
}
```

### 3. Handle Callback Errors Gracefully

```kotlin
// Kotlin callback should handle errors internally
class FipsDataCallback : FipsDataCallback {
    override fun onData(data: List<Byte>) {
        try {
            processL2capData(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process L2CAP data", e)
            // Report error to Rust if needed
            reportProcessingError(e)
        }
    }
}
```

### 4. Use Suspend Functions with Dispatchers.IO

```kotlin
// CORRECT - Use coroutine dispatcher
suspend fun connectL2cap(device: BluetoothDevice): Result<Unit> {
    return withContext(Dispatchers.IO) {
        try {
            // FFI call
            uniffi_library_connect(device.address)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e.toUniFFIL2CapError())
        }
    }
}

// WRONG - Blocking call on main thread
fun connectL2cap(device: BluetoothDevice): Result<Unit> {
    return try {
        // Blocks main thread!
        uniffi_library_connect(device.address)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e.toUniFFIL2CapError())
    }
}
```

## Configuration Files

### uniffi.toml

```toml
[package]
name = "fipsdroid-ffi"
version = "0.1.0"
edition = "2021"

[dependencies]
uniffi = { version = "0.31", features = ["tokio"] }
tokio = { version = "1.28", features = ["full"] }
thiserror = "1.0"

[build-dependencies]
uniffi = { version = "0.31", features = ["cli"] }

[lib]
name = "fipsdroid_ffi"
crate-type = ["cdylib"]

[[bin]]
name = "fipsdroid-bindgen"
path = "src/uniffi-bindgen.rs"

[uniffi]
bindings = ["kotlin"]

[bindings.kotlin]
package_name = "com.example.fipsdroid"
kotlin_target_version = "1.8"
android = true
android_cleaner = "android"

# Custom type mapping
[bindings.kotlin.custom_types.L2CapError]
type_name = "UniFFIL2CapError"
imports = ["com.example.fipsdroid.L2CapError"]

lift = "UniFFIL2CapError.liftFromRust({})"
lower = "{}.lowerToRust()"
```

## Limitations and Gotchas

### 1. `async_runtime` Attribute Scope

**Issue:** `async_runtime = "tokio"` applies only to the specific function/method.

```rust
// Only this function uses tokio
#[uniffi::export(async_runtime = "tokio")]
pub async fn async_operation() { }

// Other methods use default runtime
pub async fn sync_operation() { }
```

**Solution:** Mark all async functions that use tokio with the attribute.

### 2. Callback Interface Construction

```rust
// CORRECT - Box<dyn Callback> for callback interfaces
#[uniffi::export(callback_interface)]
pub trait MyCallback {
    fn on_data(&self, data: String);
}

#[uniffi::export]
impl MyClass {
    pub fn new(callback: Box<dyn MyCallback>) -> Self {
        Self { callback }
    }
}
```

### 3. Runtime Initialization

Kotlin must have tokio runtime initialized:

```kotlin
// UniFFI-generated code typically handles this
// But manual setup may be needed:
val runtime = TokioRuntime()
val uniffiInstance = UniffiInstance(runtime)
```

## Summary

- **Async**: Use `#[uniffi::export(async_runtime = "tokio")]` for async functions
- **Callbacks**: Use `#[uniffi::export(callback_interface)]` for callback traits
- **Errors**: Define custom error types with `#[derive(uniffi::Error)]`
- **Threading**: Kotlin uses `withContext(Dispatchers.IO)` for async calls
- **Streaming**: Use broadcast channels + tokio tasks for callback-driven streaming
- **Safety**: Always wrap FFI calls in `Result<T, Error>` and handle exceptions

## References

- [UniFFI Futures Documentation](https://mozilla.github.io/uniffi-rs/internals/async-overview.html)
- [UniFFI Callback Interfaces](https://mozilla.github.io/uniffi-rs/manual/proc_macro/index.html)
- [UniFFI Error Handling](https://mozilla.github.io/uniffi-rs/manual/proc_macro/errors.html)
- [UniFFI Kotlin Configuration](https://mozilla.github.io/uniffi-rs/manual/kotlin/configuration.html)
