# Test Strategy for FIPSDroid End-to-End testing

Date: 2026-04-03

## Summary

**Question**: Can we use a Mac to test FIPSDroid, install fips, build APK, and phone, and test?

**Answer**: No, macOS cannot be used directly. However, Docker + Linux VM provides a viable path.

## Platform Requirements

### fips (upstream)
- **Linux only** - Requires `/dev/net/tun` (kernel TUN device)
- **BlueZ** - Linux Bluetooth stack
- **libdbus** - D-bus library
- **Runt 1.85+**
- **CoreBluetooth** - macOS Bluetooth framework (different API)
- **macOS cannot run fips** - BlueZ/CoreBluetooth incompatibility
- **Architectural mismatch** - fips uses bluer, which requires Linux-specific BlueZ

## Testing Options

### Option 1: Docker Linux VM on Mac ✅ **RECOMMENDED**
- Fast setup (minutes)
- Cheap (free)
- Reproducible
- No Mac hardware changes
- Pass-through Bluetooth dongle to container
- BlueZ pre-installed in container

- fips builds and runs in container
- Isolation from Good testing environment

### Option 2: Raspberry Pi
- Dedicated hardware
- Native Linux + BlueZ
- Good for embedded/production testing
- Requires hardware purchase

### Option 3: Remote Linux Server
- Cloud/VPS
- Production-like environment
- Network latency considerations

## recommended setup

```bash
# Install Docker Desktop for Mac
brew install --cask docker
brew install --cask docker-compose

# Create docker-compose.yml
version: '3.8'
services:
  fips:
    image: ubuntu:22.04
    command: |
      apt-get update && \
      apt-get install -y bluez libdbus-1-dev git curl && \
      git clone https://github.com/jmcorgan/fips.git && \
      cd fips && \
      cargo build --release && \
      ./target/release/fips --peer-addr <phone-mac> --psm 0x0085
    privileged: true
    network_mode: host
    volumes:
      - /dev/net/tun:/dev/net/tun
      - /var/run/dbus:/var/run/dbus
    devices:
      - /dev/vhci:/dev/vhci  # Pass through Bluetooth dongle

  android:
    build: ./android
    privileged: true
    network_mode: host
    depends_on:
      - fips
```

## minimal test sequence

1. **Start fips in Linux VM**: `./target/release/fips --peer-addr <phone-mac>`
2. **Install APK on phone**: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. **Launch app**: tap "Connect" button in UI
4. **Grant permissions**: Accept prompts
5. **Observe logcat**:
   - Connection state transitions: Disconnected → connecting → handshaking → established
   - Heartbeat messages appearing
   - handshake completion logged

6. **verify success**:
   - "Established" state shown
   - Heartbeat counters increment
   - No crashes or errors
   - 30 seconds of stable connection

## key findings
1. **macOS cannot run fips directly** - BlueZ/libdbus required
2. **Docker Linux VM is cheapest path forward** - ~30 minutes setup
3. **Pixel devices work best for L2CAP** - recommended for production testing
4. **Android emulator doesn work for BLE L2CAP support
5. **Linux VM is isolated, cheap, and provides consistent test environment
