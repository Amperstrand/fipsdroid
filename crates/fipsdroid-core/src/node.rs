use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use microfips_core::noise;
use microfips_protocol::node::{Node, NodeEvent, NodeHandler};
use microfips_protocol::transport::{CryptoRng, RngCore, Transport};
use rand::rngs::OsRng;
use tokio::sync::mpsc::Sender;
use tracing::debug;

use crate::error::FipsDroidError;
use crate::transport::BleTransport;
use crate::types::{ConnectionState, HeartbeatStatus, NodeConfig};

type Result<T> = std::result::Result<T, FipsDroidError>;

pub type DefaultFipsDroidNode = FipsDroidNode<BleTransport, OsRng>;

struct FipsDroidNodeHandler {
    state_tx: Sender<ConnectionState>,
    heartbeat_status: Arc<Mutex<HeartbeatStatus>>,
}

impl FipsDroidNodeHandler {
    fn new(state_tx: Sender<ConnectionState>, heartbeat_status: Arc<Mutex<HeartbeatStatus>>) -> Self {
        Self {
            state_tx,
            heartbeat_status,
        }
    }

    async fn emit_state(&mut self, state: ConnectionState) {
        if let Err(err) = self.state_tx.send(state).await {
            debug!("dropping state update; receiver closed: {err}");
        }
    }

    fn update_heartbeat_sent(&mut self) {
        if let Ok(mut heartbeat) = self.heartbeat_status.lock() {
            heartbeat.sent_count += 1;
        }
    }

    fn update_heartbeat_received(&mut self) {
        if let Ok(mut heartbeat) = self.heartbeat_status.lock() {
            heartbeat.received_count += 1;
            heartbeat.last_received = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .ok()
                .map(|dur| dur.as_secs());
        }
    }
}

impl NodeHandler for FipsDroidNodeHandler {
    async fn on_event(&mut self, event: NodeEvent) {
        match event {
            NodeEvent::Connected => self.emit_state(ConnectionState::Connecting).await,
            NodeEvent::Msg1Sent => self.emit_state(ConnectionState::Handshaking).await,
            NodeEvent::HandshakeOk => self.emit_state(ConnectionState::Established).await,
            NodeEvent::HeartbeatSent => self.update_heartbeat_sent(),
            NodeEvent::HeartbeatRecv => self.update_heartbeat_received(),
            NodeEvent::Disconnected => self.emit_state(ConnectionState::Disconnected).await,
            NodeEvent::Error => {
                self.emit_state(ConnectionState::Error("Protocol error".to_string()))
                    .await
            }
        }
    }

    fn on_message(
        &mut self,
        msg_type: u8,
        payload: &[u8],
        _resp: &mut [u8],
    ) -> microfips_protocol::node::HandleResult {
        debug!(msg_type, payload_len = payload.len(), "received established message");
        microfips_protocol::node::HandleResult::None
    }
}

pub struct FipsDroidNode<T: Transport<Error = FipsDroidError>, R: RngCore + CryptoRng> {
    _config: NodeConfig,
    node: Option<Node<T, R>>,
    handler: Option<FipsDroidNodeHandler>,
    heartbeat_status: Arc<Mutex<HeartbeatStatus>>,
    running: bool,
}

impl<T: Transport<Error = FipsDroidError> + Send + 'static> FipsDroidNode<T, OsRng> {
    pub fn new(config: NodeConfig, transport: T, state_tx: Sender<ConnectionState>) -> Result<Self> {
        let (local_secret, peer_pub) = generate_key_material();
        let node = Node::new(transport, OsRng, local_secret, peer_pub);
        let heartbeat_status = Arc::new(Mutex::new(HeartbeatStatus {
            sent_count: 0,
            received_count: 0,
            last_received: None,
        }));
        let handler = FipsDroidNodeHandler::new(state_tx.clone(), Arc::clone(&heartbeat_status));

        Ok(Self {
            _config: config,
            node: Some(node),
            handler: Some(handler),
            heartbeat_status,
            running: false,
        })
    }
}

impl<T, R> FipsDroidNode<T, R>
where
    T: Transport<Error = FipsDroidError> + Send + 'static,
    R: RngCore + CryptoRng + Send + 'static,
{
    pub fn heartbeat_status(&self) -> HeartbeatStatus {
        self.heartbeat_status
            .lock()
            .map(|status| status.clone())
            .unwrap_or(HeartbeatStatus {
                sent_count: 0,
                received_count: 0,
                last_received: None,
            })
    }

    pub async fn run(&mut self) -> Result<()> {
        if self.running {
            return Err(FipsDroidError::AlreadyRunning);
        }

        self.running = true;

        if let Some(handler) = self.handler.as_mut() {
            handler.emit_state(ConnectionState::Connecting).await;
        }

        let node = self.node.as_mut().ok_or(FipsDroidError::NotRunning)?;
        let handler = self.handler.as_mut().ok_or(FipsDroidError::NotRunning)?;
        node.run(handler).await;

        #[allow(unreachable_code)]
        Ok(())
    }
}

fn generate_key_material() -> ([u8; 32], [u8; 33]) {
    let mut local_secret = [0u8; 32];
    let mut peer_secret = [0u8; 32];

    loop {
        OsRng.fill_bytes(&mut local_secret);
        if noise::ecdh_pubkey(&local_secret).is_ok() {
            break;
        }
    }

    loop {
        OsRng.fill_bytes(&mut peer_secret);
        if let Ok(peer_pub) = noise::ecdh_pubkey(&peer_secret) {
            return (local_secret, peer_pub);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::transport::MockBleTransport;
    use tokio::runtime::Builder;
    use tokio::sync::mpsc;

    #[test]
    fn test_node_creation() {
        let (transport, _incoming_tx, _outgoing_rx) = MockBleTransport::new();
        let (state_tx, _state_rx) = mpsc::channel(8);
        let config = NodeConfig {
            peer_address: "00:11:22:33:44:55".to_string(),
            psm: 0x0085,
        };

        let node = FipsDroidNode::new(config, transport, state_tx);
        assert!(node.is_ok(), "FipsDroidNode::new() should return Ok");
    }

    #[test]
    fn test_state_transitions() {
        let rt = Builder::new_current_thread().enable_all().build().unwrap();
        rt.block_on(async {
            let (transport, _incoming_tx, _outgoing_rx) = MockBleTransport::new();
            let (state_tx, mut state_rx) = mpsc::channel(8);
            let config = NodeConfig {
                peer_address: "00:11:22:33:44:55".to_string(),
                psm: 0x0085,
            };

            let mut node = FipsDroidNode::new(config, transport, state_tx).unwrap();
            let handler = node.handler.as_mut().unwrap();

            handler.on_event(NodeEvent::Connected).await;
            handler.on_event(NodeEvent::Msg1Sent).await;
            handler.on_event(NodeEvent::HandshakeOk).await;

            assert_eq!(state_rx.recv().await, Some(ConnectionState::Connecting));
            assert_eq!(state_rx.recv().await, Some(ConnectionState::Handshaking));
            assert_eq!(state_rx.recv().await, Some(ConnectionState::Established));
        });
    }

    #[test]
    fn test_heartbeat_counting() {
        let rt = Builder::new_current_thread().enable_all().build().unwrap();
        rt.block_on(async {
            let (transport, _incoming_tx, _outgoing_rx) = MockBleTransport::new();
            let (state_tx, _state_rx) = mpsc::channel(8);
            let config = NodeConfig {
                peer_address: "00:11:22:33:44:55".to_string(),
                psm: 0x0085,
            };

            let mut node = FipsDroidNode::new(config, transport, state_tx).unwrap();
            let handler = node.handler.as_mut().unwrap();

            handler.on_event(NodeEvent::HeartbeatSent).await;
            handler.on_event(NodeEvent::HeartbeatSent).await;
            handler.on_event(NodeEvent::HeartbeatRecv).await;

            let status = node.heartbeat_status();
            assert_eq!(status.sent_count, 2);
            assert_eq!(status.received_count, 1);
            assert!(status.last_received.is_some());
        });
    }
}
