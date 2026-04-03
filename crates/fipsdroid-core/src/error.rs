use thiserror::Error;

#[derive(Debug, Error, uniffi::Error)]
pub enum FipsDroidError {
    #[error("Runtime error: {message}")]
    RuntimeError { message: String },

    #[error("Bridge is already running")]
    AlreadyRunning,

    #[error("Bridge is not running")]
    NotRunning,

    #[error("BLE unavailable")]
    BleUnavailable,

    #[error("Connection failed: {message}")]
    ConnectionFailed { message: String },

    #[error("Handshake failed: {message}")]
    HandshakeFailed { message: String },

    #[error("Timeout")]
    Timeout,

    #[error("Transport closed")]
    TransportClosed,

    #[error("Invalid peer key")]
    InvalidPeerKey,

    #[error("Protocol error: {message}")]
    ProtocolError { message: String },
}
