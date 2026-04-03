use microfips_protocol::transport::Transport;
use tokio::sync::mpsc::{Receiver, Sender};

use crate::error::FipsDroidError;

const PUBKEY_PREFIX: u8 = 0x00;
const PUBKEY_LEN: usize = 32;
const PUBKEY_PACKET_LEN: usize = 33;

pub struct BleTransport {
    rx: Receiver<Vec<u8>>,
    tx: Sender<Vec<u8>>,
    close_tx: Sender<()>,
    pending: Vec<u8>,
    pending_pos: usize,
}

impl BleTransport {
    pub fn new(rx: Receiver<Vec<u8>>, tx: Sender<Vec<u8>>, close_tx: Sender<()>) -> Self {
        Self {
            rx,
            tx,
            close_tx,
            pending: Vec::new(),
            pending_pos: 0,
        }
    }

    pub async fn read(&mut self, buf: &mut [u8]) -> Result<usize, FipsDroidError> {
        if buf.is_empty() {
            return Ok(0);
        }

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
                if frame.is_empty() {
                    return Ok(0);
                }

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

    pub async fn write(&mut self, data: &[u8]) -> Result<(), FipsDroidError> {
        self.tx
            .send(data.to_vec())
            .await
            .map_err(|_| FipsDroidError::TransportClosed)
    }

    pub async fn close(&mut self) -> Result<(), FipsDroidError> {
        self.close_tx
            .send(())
            .await
            .map_err(|_| FipsDroidError::TransportClosed)
    }

    pub async fn send_pubkey(&mut self, pubkey: &[u8; PUBKEY_LEN]) -> Result<(), FipsDroidError> {
        let mut msg = [0u8; PUBKEY_PACKET_LEN];
        msg[0] = PUBKEY_PREFIX;
        msg[1..].copy_from_slice(pubkey);
        self.write(&msg).await
    }

    pub async fn recv_pubkey(&mut self) -> Result<[u8; PUBKEY_LEN], FipsDroidError> {
        let mut msg = [0u8; PUBKEY_PACKET_LEN];
        self.read_exact(&mut msg).await?;

        if msg[0] != PUBKEY_PREFIX {
            return Err(FipsDroidError::InvalidPeerKey);
        }

        let mut out = [0u8; PUBKEY_LEN];
        out.copy_from_slice(&msg[1..]);
        Ok(out)
    }

    async fn read_exact(&mut self, out: &mut [u8]) -> Result<(), FipsDroidError> {
        let mut offset = 0usize;
        while offset < out.len() {
            let n = self.read(&mut out[offset..]).await?;
            if n == 0 {
                return Err(FipsDroidError::TransportClosed);
            }
            offset += n;
        }
        Ok(())
    }
}

impl Transport for BleTransport {
    type Error = FipsDroidError;

    async fn wait_ready(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }

    async fn send(&mut self, data: &[u8]) -> Result<(), Self::Error> {
        self.write(data).await
    }

    async fn recv(&mut self, buf: &mut [u8]) -> Result<usize, Self::Error> {
        self.read(buf).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::runtime::Builder;
    use tokio::sync::mpsc;

    #[test]
    fn pubkey_format_roundtrip() {
        let rt = Builder::new_current_thread().enable_all().build().unwrap();
        rt.block_on(async {
            let (tx_in, rx_in) = mpsc::channel(4);
            let (tx_out, mut rx_out) = mpsc::channel(4);
            let (close_tx, _close_rx) = mpsc::channel(1);
            let mut t = BleTransport::new(rx_in, tx_out, close_tx);

            let key = [0x11u8; 32];
            t.send_pubkey(&key).await.unwrap();
            let sent = rx_out.recv().await.unwrap();
            assert_eq!(sent[0], 0x00);
            assert_eq!(&sent[1..], &key);

            tx_in.send(sent).await.unwrap();
            let recv = t.recv_pubkey().await.unwrap();
            assert_eq!(recv, key);
        });
    }
}
