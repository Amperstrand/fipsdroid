use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::sync::mpsc;
use tokio::time::timeout;

use crate::error::FipsDroidError;
use crate::node::FipsDroidNode;
use crate::transport::BleTransport;
use crate::types::{ConnectionState, HeartbeatStatus, NodeConfig};

const PUBKEY_EXCHANGE_TIMEOUT_SECS: u64 = 5;

#[uniffi::export(callback_interface)]
pub trait FipsDroidCallback: Send + Sync {
    fn on_state_changed(&self, state: ConnectionState);
    fn on_heartbeat(&self, status: HeartbeatStatus);
    fn on_error(&self, error: String);
}

struct BridgeInner {
    state: ConnectionState,
    heartbeat_status: HeartbeatStatus,
    callback: Option<Box<dyn FipsDroidCallback>>,
    shutdown_tx: Option<tokio::sync::oneshot::Sender<()>>,
    running: bool,
    incoming_tx: Option<mpsc::Sender<Vec<u8>>>,
    outgoing_rx: Option<Mutex<mpsc::Receiver<Vec<u8>>>>,
}

#[derive(uniffi::Object)]
pub struct FipsDroidBridge {
    runtime: tokio::runtime::Runtime,
    peer_address: String,
    peer_pubkey: Vec<u8>,
    local_privkey: Vec<u8>,
    inner: Arc<Mutex<BridgeInner>>,
}

#[uniffi::export]
impl FipsDroidBridge {
    #[uniffi::constructor]
    pub fn new(
        peer_address: String,
        peer_pubkey: Vec<u8>,
        local_privkey: Vec<u8>,
    ) -> Result<Self, FipsDroidError> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .map_err(|e| FipsDroidError::RuntimeError {
                message: format!("Failed to create tokio runtime: {e}"),
            })?;

        let inner = Arc::new(Mutex::new(BridgeInner {
            state: ConnectionState::Disconnected,
            heartbeat_status: HeartbeatStatus {
                sent_count: 0,
                received_count: 0,
                last_received: None,
            },
            callback: None,
            shutdown_tx: None,
            running: false,
            incoming_tx: None,
            outgoing_rx: None,
        }));

        Ok(Self {
            runtime,
            peer_address,
            peer_pubkey,
            local_privkey,
            inner,
        })
    }

    pub fn start(&self, callback: Box<dyn FipsDroidCallback>) -> Result<(), FipsDroidError> {
        let mut inner = self.inner.lock().map_err(|e| FipsDroidError::RuntimeError {
            message: format!("Lock poisoned: {e}"),
        })?;

        if inner.running {
            return Err(FipsDroidError::AlreadyRunning);
        }

        // Create channels for BLE transport
        let (incoming_tx, incoming_rx) = mpsc::channel::<Vec<u8>>(64);
        let (outgoing_tx, outgoing_rx) = mpsc::channel::<Vec<u8>>(64);
        let (close_tx, _close_rx) = mpsc::channel::<()>(1);

        inner.callback = Some(callback);
        inner.incoming_tx = Some(incoming_tx);
        inner.outgoing_rx = Some(Mutex::new(outgoing_rx));

        let (shutdown_tx, mut shutdown_rx) = tokio::sync::oneshot::channel::<()>();
        inner.shutdown_tx = Some(shutdown_tx);
        inner.running = true;

        inner.state = ConnectionState::Connecting;
        if let Some(ref cb) = inner.callback {
            cb.on_state_changed(ConnectionState::Connecting);
        }

        let inner_clone = Arc::clone(&self.inner);
        let peer_address = self.peer_address.clone();

        // Convert Vec<u8> keys to fixed-size arrays
        let local_secret: [u8; 32] = {
            let key = &self.local_privkey;
            let mut arr = [0u8; 32];
            let len = key.len().min(32);
            arr[..len].copy_from_slice(&key[..len]);
            arr
        };

        let _peer_pubkey: [u8; 33] = {
            let key = &self.peer_pubkey;
            let mut arr = [0u8; 33];
            let len = key.len().min(33);
            arr[..len].copy_from_slice(&key[..len]);
            arr
        };

        self.runtime.spawn(async move {
            // Create transport
            let mut transport = BleTransport::new(incoming_rx, outgoing_tx, close_tx);

            // Create state channel for node
            let (state_tx, mut state_rx) = mpsc::channel::<ConnectionState>(16);

            // Derive local pubkey from local_secret
            let local_pubkey = match microfips_core::noise::ecdh_pubkey(&local_secret) {
                Ok(pk) => pk,
                Err(e) => {
                    let err = FipsDroidError::HandshakeFailed {
                        message: format!("Failed to derive local pubkey: {:?}", e),
                    };
                    if let Ok(mut inner) = inner_clone.lock() {
                        if let Some(ref cb) = inner.callback {
                            cb.on_error(err.to_string());
                        }
                        inner.state = ConnectionState::Error(err.to_string());
                        inner.running = false;
                    }
                    return;
                }
            };

            // Pubkey exchange with timeout
            // Extract 32 bytes from compressed 33-byte pubkey (skip prefix byte)
            let mut local_pubkey_32: [u8; 32] = [0u8; 32];
            local_pubkey_32.copy_from_slice(&local_pubkey[1..]);
            
            let pubkey_exchange_result = timeout(
                Duration::from_secs(PUBKEY_EXCHANGE_TIMEOUT_SECS),
                async {
                    // Send our pubkey (32 bytes)
                    transport.send_pubkey(&local_pubkey_32).await?;
                    // Receive peer's pubkey
                    transport.recv_pubkey().await
                },
            )
            .await;

            let peer_pubkey_received = match pubkey_exchange_result {
                Ok(Ok(pk)) => pk,
                Ok(Err(e)) => {
                    let err_msg = if matches!(e, FipsDroidError::InvalidPeerKey) {
                        "Invalid peer key".to_string()
                    } else {
                        format!("Pubkey exchange failed: {e}")
                    };
                    if let Ok(mut inner) = inner_clone.lock() {
                        if let Some(ref cb) = inner.callback {
                            cb.on_error(err_msg.clone());
                        }
                        inner.state = ConnectionState::Error(err_msg);
                        inner.running = false;
                    }
                    return;
                }
                Err(_) => {
                    let err_msg = format!(
                        "Pubkey exchange timed out after {} seconds",
                        PUBKEY_EXCHANGE_TIMEOUT_SECS
                    );
                    if let Ok(mut inner) = inner_clone.lock() {
                        if let Some(ref cb) = inner.callback {
                            cb.on_error(err_msg.clone());
                        }
                        inner.state = ConnectionState::Error(err_msg);
                        inner.running = false;
                    }
                    return;
                }
            };

            // Convert peer pubkey from [u8; 32] to [u8; 33] (prepend 0x02 for compressed format)
            let mut peer_pubkey_33: [u8; 33] = [0x02; 33];
            peer_pubkey_33[1..].copy_from_slice(&peer_pubkey_received);

            // Emit Handshaking state
            if state_tx.send(ConnectionState::Handshaking).await.is_err() {
                if let Ok(mut inner) = inner_clone.lock() {
                    inner.state = ConnectionState::Error("State channel closed".to_string());
                    inner.running = false;
                }
                return;
            }

            // Create node config
            let config = NodeConfig {
                peer_address,
                psm: 0x0085,
            };

            // Create node with explicit keys
            let mut node = match FipsDroidNode::new(
                config,
                transport,
                state_tx,
                local_secret,
                peer_pubkey_33,
            ) {
                Ok(node) => node,
                Err(e) => {
                    let err_msg = format!("Failed to create node: {e}");
                    if let Ok(mut inner) = inner_clone.lock() {
                        if let Some(ref cb) = inner.callback {
                            cb.on_error(err_msg.clone());
                        }
                        inner.state = ConnectionState::Error(err_msg);
                        inner.running = false;
                    }
                    return;
                }
            };

            // Run node in background task
            let node_task = tokio::spawn(async move {
                if let Err(e) = node.run().await {
                    // Node run failed - this is unexpected as run() typically loops
                    eprintln!("Node run failed: {e}");
                }
            });

            // State forwarding loop
            loop {
                tokio::select! {
                    _ = &mut shutdown_rx => {
                        node_task.abort();
                        break;
                    }
                    Some(state) = state_rx.recv() => {
                        if let Ok(mut inner) = inner_clone.lock() {
                            inner.state = state.clone();
                            if let Some(ref cb) = inner.callback {
                                cb.on_state_changed(state);
                            }
                        }
                    }
                    else => break,
                }
            }

            // Cleanup on exit
            if let Ok(mut inner) = inner_clone.lock() {
                inner.state = ConnectionState::Disconnected;
                inner.running = false;
                inner.incoming_tx = None;
                inner.outgoing_rx = None;
                if let Some(ref cb) = inner.callback {
                    cb.on_state_changed(ConnectionState::Disconnected);
                }
            }
        });

        Ok(())
    }

    pub fn stop(&self) -> Result<(), FipsDroidError> {
        let mut inner = self.inner.lock().map_err(|e| FipsDroidError::RuntimeError {
            message: format!("Lock poisoned: {e}"),
        })?;

        if !inner.running {
            return Err(FipsDroidError::NotRunning);
        }

        inner.state = ConnectionState::Disconnecting;
        if let Some(ref cb) = inner.callback {
            cb.on_state_changed(ConnectionState::Disconnecting);
        }

        if let Some(tx) = inner.shutdown_tx.take() {
            let _ = tx.send(());
        }

        Ok(())
    }

    pub fn feed_incoming(&self, data: Vec<u8>) -> Result<(), FipsDroidError> {
        let inner = self.inner.lock().map_err(|e| FipsDroidError::RuntimeError {
            message: format!("Lock poisoned: {e}"),
        })?;

        if let Some(ref tx) = inner.incoming_tx {
            tx.try_send(data).map_err(|_| FipsDroidError::TransportClosed)?;
        }

        Ok(())
    }

    pub fn poll_outgoing(&self) -> Option<Vec<u8>> {
        let inner = self.inner.lock().ok()?;
        let rx = inner.outgoing_rx.as_ref()?;
        let mut rx = rx.lock().ok()?;
        rx.try_recv().ok()
    }

    pub fn get_state(&self) -> ConnectionState {
        self.inner
            .lock()
            .map(|inner| inner.state.clone())
            .unwrap_or(ConnectionState::Error("Lock poisoned".to_string()))
    }

    pub fn get_heartbeat_status(&self) -> HeartbeatStatus {
        self.inner
            .lock()
            .map(|inner| inner.heartbeat_status.clone())
            .unwrap_or(HeartbeatStatus {
                sent_count: 0,
                received_count: 0,
                last_received: None,
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, Ordering};

    struct MockCallback {
        state_changed: Arc<AtomicBool>,
    }

    impl MockCallback {
        fn new() -> (Self, Arc<AtomicBool>) {
            let flag = Arc::new(AtomicBool::new(false));
            (
                Self {
                    state_changed: Arc::clone(&flag),
                },
                flag,
            )
        }
    }

    impl FipsDroidCallback for MockCallback {
        fn on_state_changed(&self, _state: ConnectionState) {
            self.state_changed.store(true, Ordering::SeqCst);
        }

        fn on_heartbeat(&self, _status: HeartbeatStatus) {}

        fn on_error(&self, _error: String) {}
    }

    #[test]
    fn test_bridge_creation() {
        let bridge = FipsDroidBridge::new(
            "00:11:22:33:44:55".to_string(),
            vec![0u8; 32],
            vec![0u8; 32],
        );
        assert!(bridge.is_ok(), "FipsDroidBridge::new() should return Ok");
    }

    #[test]
    fn test_initial_state() {
        let bridge = FipsDroidBridge::new(
            "00:11:22:33:44:55".to_string(),
            vec![0u8; 32],
            vec![0u8; 32],
        )
        .unwrap();

        match bridge.get_state() {
            ConnectionState::Disconnected => {}
            other => panic!("Expected Disconnected, got {:?}", other),
        }
    }

    #[test]
    fn test_initial_heartbeat_status() {
        let bridge = FipsDroidBridge::new(
            "00:11:22:33:44:55".to_string(),
            vec![0u8; 32],
            vec![0u8; 32],
        )
        .unwrap();

        let status = bridge.get_heartbeat_status();
        assert_eq!(status.sent_count, 0);
        assert_eq!(status.received_count, 0);
        assert!(status.last_received.is_none());
    }

    #[test]
    fn test_start_changes_state_to_connecting() {
        let bridge = FipsDroidBridge::new(
            "00:11:22:33:44:55".to_string(),
            vec![0u8; 32],
            vec![0u8; 32],
        )
        .unwrap();

        let (callback, state_changed) = MockCallback::new();
        let result = bridge.start(Box::new(callback));
        assert!(result.is_ok());

        assert!(state_changed.load(Ordering::SeqCst));

        match bridge.get_state() {
            ConnectionState::Connecting => {}
            other => panic!("Expected Connecting, got {:?}", other),
        }

        let _ = bridge.stop();
    }

    #[test]
    fn test_double_start_returns_error() {
        let bridge = FipsDroidBridge::new(
            "00:11:22:33:44:55".to_string(),
            vec![0u8; 32],
            vec![0u8; 32],
        )
        .unwrap();

        let (cb1, _) = MockCallback::new();
        let (cb2, _) = MockCallback::new();

        assert!(bridge.start(Box::new(cb1)).is_ok());
        assert!(bridge.start(Box::new(cb2)).is_err());

        let _ = bridge.stop();
    }

    #[test]
    fn test_stop_without_start_returns_error() {
        let bridge = FipsDroidBridge::new(
            "00:11:22:33:44:55".to_string(),
            vec![0u8; 32],
            vec![0u8; 32],
        )
        .unwrap();

        assert!(bridge.stop().is_err());
    }

    #[test]
    fn test_stop_signals_shutdown() {
        let bridge = FipsDroidBridge::new(
            "00:11:22:33:44:55".to_string(),
            vec![0u8; 32],
            vec![0u8; 32],
        )
        .unwrap();

        let (callback, _) = MockCallback::new();
        bridge.start(Box::new(callback)).unwrap();

        let result = bridge.stop();
        assert!(result.is_ok());

        match bridge.get_state() {
            ConnectionState::Disconnecting => {}
            other => panic!("Expected Disconnecting, got {:?}", other),
        }
    }

    #[test]
    fn test_feed_incoming_before_start() {
        let bridge = FipsDroidBridge::new(
            "00:11:22:33:44:55".to_string(),
            vec![0u8; 32],
            vec![0u8; 32],
        )
        .unwrap();

        let result = bridge.feed_incoming(vec![1, 2, 3]);
        assert!(result.is_ok());
    }

    #[test]
    fn test_poll_outgoing_before_start_returns_none() {
        let bridge = FipsDroidBridge::new(
            "00:11:22:33:44:55".to_string(),
            vec![0u8; 32],
            vec![0u8; 32],
        )
        .unwrap();

        assert!(bridge.poll_outgoing().is_none());
    }
}
