use std::sync::{Arc, Mutex};

use crate::error::FipsDroidError;
use crate::types::{ConnectionState, HeartbeatStatus};

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

        inner.callback = Some(callback);

        let (shutdown_tx, shutdown_rx) = tokio::sync::oneshot::channel::<()>();
        inner.shutdown_tx = Some(shutdown_tx);
        inner.running = true;

        inner.state = ConnectionState::Connecting;
        if let Some(ref cb) = inner.callback {
            cb.on_state_changed(ConnectionState::Connecting);
        }

        let inner_clone = Arc::clone(&self.inner);
        let _peer_address = self.peer_address.clone();
        let _peer_pubkey = self.peer_pubkey.clone();
        let _local_privkey = self.local_privkey.clone();

        self.runtime.spawn(async move {
            // TODO: Replace with FipsDroidNode::run() once node module is implemented
            let _ = shutdown_rx.await;

            if let Ok(mut inner) = inner_clone.lock() {
                inner.state = ConnectionState::Disconnected;
                inner.running = false;
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
}
