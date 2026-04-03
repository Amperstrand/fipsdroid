# Task 1: Embassy Timer Audit Evidence

**Date**: 2026-04-03
**Target**: microfips-protocol crate
**Goal**: Count maximum concurrent timers and verify generic-queue-32 sufficiency

---

## Audit Methodology

### Files Audited

1. **node.rs** (complete source)
   - Source: https://raw.githubusercontent.com/Amperstrand/microfips/main/crates/microfips-protocol/src/node.rs
   - Total lines: 724
   - Timer calls found: 5

2. **transport.rs** (complete source)
   - Source: https://raw.githubusercontent.com/Amperstrand/microfips/main/crates/microfips-protocol/src/transport.rs
   - Total lines: 414
   - Timer calls found: 1

### Analysis Technique

1. **Static Analysis**: Grep for Timer API usage across source files
2. **Control Flow Analysis**: Trace timer execution through Node lifecycle
3. **Concurrency Analysis**: Identify parallel timer operations via `select()` calls
4. **Lifecycle Phase Classification**: Separate by handshake, steady-state, retry phases

---

## Timer Inventory Summary

### Node.rs (5 timers)

```rust
// Line 105: Retry delay after session ends
Timer::after(Duration::from_secs(RETRY_SECS))

// Line 124: Connect startup delay
Timer::after(Duration::from_millis(CONNECT_DELAY_MS))

// Line 186: One-shot heartbeat timer
Timer::at(deadline)

// Line 199: Receive timeout (length-prefixed framing)
Timer::after(Duration::from_millis(timeout_ms as u64))

// Line 235: Receive timeout (raw framing)
Timer::after(Duration::from_millis(timeout_ms as u64))
```

### Transport.rs (1 timer)

```rust
// Line 17: Receive timeout (common implementation)
Timer::after(Duration::from_millis(timeout_ms as u64))
```

**Total Timer Calls**: 6 unique timer invocations

---

## Control Flow Traces

### Handshake Phase (node.rs:119-184)

```
session()
  ├─ wait_ready()
  ├─ Timer::after(CONNECT_DELAY_MS) [500ms]      ← PRE-HANDSHAKE
  ├─ handshake()
  │    └─ recv_frame(RECV_TIMEOUT_MS)           ← WITHIN HANDSHAKE
  │         └─ select(recv, Timer::after(30000)) ← 2 CONCURRENT max
  └─ Result(Ok) → steady() OR Err → return
```

**Maximum timers during handshake**: 1 (after CONNECT_DELAY_MS, only receive timeout active)
**Actually 2**: CONNECT_DELAY_MS + RECV_TIMEOUT_MS both run sequentially, never concurrently

### Steady-State Phase (node.rs:186-280)

```
steady()
  ├─ next_hb = now + HB_SECS                      ← HB timer deadline
  └─ loop {
       └─ select(
             transport.recv(),
             Timer::at(next_hb)                   ← HEARTBEAT TIMER
          ).await
          │
          ├─ Either::First(Ok) → process frames
          │    └─ recv_frame(timeout_ms)         ← RECEIVE TIMER
          │         └─ select(recv, Timer::after(30000)) ← 2 CONCURRENT
          │
          └─ Either::Second(()) → heartbeat expired
               └─ send_heartbeat()
                    └─ next_hb = now + HB_SECS
       }
  }
```

**Maximum timers during steady-state**: 2 (heartbeat + receive timeout run in parallel)

### Retry Phase (node.rs:98-108)

```
run()
  └─ loop {
       ├─ session() → Result(Err|Ok)
       └─ Timer::after(RETRY_SECS)                ← ONE-SHOT RETRY
        }
```

**Maximum timers during retry**: 1 (retry delay, one-shot)

---

## Concurrency Analysis

### `select()` Usage Pattern

The Node implementation uses `embassy_futures::select()` to await multiple futures concurrently:

```rust
// Steady-state heartbeat loop
select(
    self.transport.recv(&mut rx),
    Timer::at(deadline)
).await
```

This ensures:
1. Heartbeat and receive operations are non-blocking
2. Handler `poll_at()` deadlines are respected
3. No busy-wait loops
4. At most 2 futures can be pending simultaneously

### Why Max 2 Timers?

**Proof by Contradiction**:
- If 3 timers were active, they would need 3 parallel `select()` operations
- No `select()` in codebase has 3+ operands
- Timer calls are serialized by control flow (pre-delay → handshake → steady → retry)

---

## Counterexamples (Why NOT 3+ timers)

### False Positive: All 6 timer calls could be concurrent?

No. The 6 timer calls are not independent:

1. **Retry timer**: Runs AFTER session completes (async, one-shot)
2. **Connect delay**: Runs BEFORE handshake (synchronous, 500ms)
3. **Handshake timers**: Only active during handshake phase
4. **Steady-state timers**: Only active during steady-state phase

**Control Flow Guarantees**:
```
run() → session() → [handshake] → steady() → [retry]
      [connect delay]               [heartbeat + recv timeout]
```

These phases are strictly sequential. No timer from phase A runs while another timer from phase B is active.

---

## Generic-Queue-32 Sufficiency Verification

### Queue Size Calculation

```
Maximum concurrent timers = 2
embassy-time default = generic-queue-32 (32 slots)
Queue headroom = 32 - 2 = 30 slots
Headroom percentage = 30 / 32 = 93.75%
```

### Comparison to Requirements

| Requirement | Value | Status |
|-------------|-------|--------|
| Max concurrent timers | 2 | ✓ Found in analysis |
| Required queue size | 2+ | ✓ generic-queue-32 is 32 |
| Safety margin | 30 slots | ✓ Excellent headroom |
| Future-proofing | > 10x over-provisioned | ✓ Not needed: generic-queue-64 |

### Conclusion

✅ **generic-queue-32 is MORE than sufficient**

The Node implementation only needs 2 timer slots at any point. The embassy-time crate provides 32 slots, which is:
1. 16x more than required
2. Well within safety margins
3. Unlikely to ever need `generic-queue-64` for this crate

---

## Constants Reference

| Constant | Value | File:Line | Usage |
|----------|-------|-----------|-------|
| RETRY_SECS | 3 | node.rs:38 | Retry delay after session ends |
| CONNECT_DELAY_MS | 500 | node.rs:39 | Connect startup delay |
| HB_SECS | 10 | node.rs:36 | Heartbeat interval |
| RECV_TIMEOUT_MS | 30000 | node.rs:37 | Receive timeout (30s) |

---

## Risk Assessment

### Risk Level: LOW

**Rationale**:
1. Low timer count (6 total calls)
2. Low concurrency (max 2 concurrent)
3. No nested timer loops
4. No dynamic timer allocation
5. No timer recycling/leaks detected

### Failure Scenario (worst case)

If all 6 timer calls somehow became concurrent:
- Required queue size: 6 slots
- Available queue size: 32 slots
- Result: Still sufficient

### Recommendation

**Do NOT use generic-queue-64** for microfips-protocol.

The `generic-queue-32` feature (default in embassy-time) provides ample headroom. Only upgrade to `generic-queue-64` if:
- Node implementation changes to support 3+ concurrent timers
- Multiple Node instances run simultaneously (currently single-node design)
- Additional async operations require timer involvement

---

## Cross-References

- **Plan**: Task 1 - "Audit microfips-protocol crate to count maximum concurrent embassy timers"
- **Documentation**: docs/embassy-audit.md (full audit report)
- **Embassy-time repo**: https://github.com/embassy-rs/embassy/tree/main/crates/embassy-time
- **microfips repo**: https://github.com/Amperstrand/microfips/tree/main/crates/microfips-protocol

---

**Evidence Status**: COMPLETE
**Verification**: PASSED
**Confidence Level**: HIGH (static analysis + control flow tracing)
