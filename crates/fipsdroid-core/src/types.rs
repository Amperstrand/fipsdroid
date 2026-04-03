use uniffi;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Handshaking,
    Established,
    Disconnecting,
    Error(String),
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PeerInfo {
    pub address: String,
    pub pubkey: Vec<u8>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct HeartbeatStatus {
    pub sent_count: u64,
    pub received_count: u64,
    pub last_received: Option<u64>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct NodeConfig {
    pub peer_address: String,
    pub psm: u16,
}
