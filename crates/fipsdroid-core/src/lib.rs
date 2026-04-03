mod bridge;
mod error;
mod node;
mod transport;
mod types;

pub use error::FipsDroidError;
pub use types::*;

/// Marker type required by UniFFI derive macros
#[doc(hidden)]
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct UniFfiTag;
