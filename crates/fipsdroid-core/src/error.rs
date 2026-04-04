use thiserror::Error;

#[derive(Debug, Error, uniffi::Error)]
pub enum FipsDroidError {
    #[error("Runtime error: {details}")]
    RuntimeError { details: String },

    #[error("Bridge is already running")]
    AlreadyRunning,

    #[error("Bridge is not running")]
    NotRunning,

    #[error("BLE unavailable")]
    BleUnavailable,

    #[error("Connection failed: {details}")]
    ConnectionFailed { details: String },

    #[error("Handshake failed: {details}")]
    HandshakeFailed { details: String },

    #[error("Timeout")]
    Timeout,

    #[error("Transport closed")]
    TransportClosed,

    #[error("Invalid peer key")]
    InvalidPeerKey,

    #[error("Protocol error: {details}")]
    ProtocolError { details: String },
}
