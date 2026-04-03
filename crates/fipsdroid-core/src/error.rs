use thiserror::Error;

#[derive(Debug, Error)]
pub enum FipsDroidError {
    #[error("BLE unavailable")]
    BleUnavailable,

    #[error("Connection failed: {0}")]
    ConnectionFailed(String),

    #[error("Handshake failed: {0}")]
    HandshakeFailed(String),

    #[error("Timeout")]
    Timeout,

    #[error("Transport closed")]
    TransportClosed,

    #[error("Invalid peer key")]
    InvalidPeerKey,

    #[error("Protocol error: {0}")]
    ProtocolError(String),
}
