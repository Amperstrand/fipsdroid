use microfips_protocol::transport::Transport;
use tokio::sync::mpsc::{self, Receiver, Sender};

use crate::error::FipsDroidError;

pub struct MockBleTransport {
    rx: Receiver<Vec<u8>>,
    tx: Sender<Vec<u8>>,
    close_tx: Sender<()>,
    pending: Vec<u8>,
    pending_pos: usize,
}

impl MockBleTransport {
    pub fn new() -> (Self, Sender<Vec<u8>>, Receiver<Vec<u8>>) {
        let (incoming_tx, incoming_rx) = mpsc::channel(16);
        let (outgoing_tx, outgoing_rx) = mpsc::channel(16);
        let (close_tx, _close_rx) = mpsc::channel(1);

        (
            Self {
                rx: incoming_rx,
                tx: outgoing_tx,
                close_tx,
                pending: Vec::new(),
                pending_pos: 0,
            },
            incoming_tx,
            outgoing_rx,
        )
    }

    pub async fn close(&mut self) -> Result<(), FipsDroidError> {
        self.close_tx
            .send(())
            .await
            .map_err(|_| FipsDroidError::TransportClosed)
    }
}

impl Transport for MockBleTransport {
    type Error = FipsDroidError;

    async fn wait_ready(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }

    async fn send(&mut self, data: &[u8]) -> Result<(), Self::Error> {
        self.tx
            .send(data.to_vec())
            .await
            .map_err(|_| FipsDroidError::TransportClosed)
    }

    async fn recv(&mut self, buf: &mut [u8]) -> Result<usize, Self::Error> {
        if self.pending_pos < self.pending.len() {
            let rem = &self.pending[self.pending_pos..];
            let n = rem.len().min(buf.len());
            buf[..n].copy_from_slice(&rem[..n]);
            self.pending_pos += n;
            if self.pending_pos >= self.pending.len() {
                self.pending.clear();
                self.pending_pos = 0;
            }
            return Ok(n);
        }

        match self.rx.recv().await {
            Some(frame) => {
                let n = frame.len().min(buf.len());
                buf[..n].copy_from_slice(&frame[..n]);
                if n < frame.len() {
                    self.pending = frame;
                    self.pending_pos = n;
                }
                Ok(n)
            }
            None => Ok(0),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::runtime::Builder;

    #[test]
    fn mock_roundtrip() {
        let rt = Builder::new_current_thread().enable_all().build().unwrap();
        rt.block_on(async {
            let (mut t, incoming_tx, mut outgoing_rx) = MockBleTransport::new();

            t.send(b"abc").await.unwrap();
            assert_eq!(outgoing_rx.recv().await.unwrap(), b"abc");

            incoming_tx.send(b"xyz".to_vec()).await.unwrap();
            let mut buf = [0u8; 8];
            let n = t.recv(&mut buf).await.unwrap();
            assert_eq!(n, 3);
            assert_eq!(&buf[..3], b"xyz");
        });
    }
}
