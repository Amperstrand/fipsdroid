pub mod ble;
pub mod mock;

pub use ble::BleTransport;
pub use mock::MockBleTransport;

#[cfg(test)]
mod tests {
    use super::ble::BleTransport;
    use super::mock::MockBleTransport;
    use microfips_protocol::transport::Transport;
    use tokio::runtime::Builder;
    use tokio::sync::mpsc;
    use tokio::time::{timeout, Duration};

    #[test]
    fn read_write_roundtrip_via_mock() {
        let rt = Builder::new_current_thread().enable_all().build().unwrap();
        rt.block_on(async {
            let (mut transport, incoming_tx, mut outgoing_rx) = MockBleTransport::new();

            transport.send(b"ping").await.unwrap();
            let out = outgoing_rx.recv().await.unwrap();
            assert_eq!(out, b"ping");

            incoming_tx.send(b"pong".to_vec()).await.unwrap();
            let mut buf = [0u8; 16];
            let n = transport.recv(&mut buf).await.unwrap();
            assert_eq!(n, 4);
            assert_eq!(&buf[..4], b"pong");
        });
    }

    #[test]
    fn pubkey_exchange_format_valid_and_invalid() {
        let rt = Builder::new_current_thread().enable_all().build().unwrap();
        rt.block_on(async {
            let (tx_to_transport, rx_to_transport) = mpsc::channel(4);
            let (tx_from_transport, mut rx_from_transport) = mpsc::channel(4);
            let (close_tx, _close_rx) = mpsc::channel(1);
            let mut transport = BleTransport::new(rx_to_transport, tx_from_transport, close_tx);

            let pubkey = [0xAB; 32];
            transport.send_pubkey(&pubkey).await.unwrap();
            let packet = rx_from_transport.recv().await.unwrap();
            assert_eq!(packet.len(), 33);
            assert_eq!(packet[0], 0x00);
            assert_eq!(&packet[1..], &pubkey);

            let mut valid = vec![0x00];
            valid.extend_from_slice(&pubkey);
            tx_to_transport.send(valid).await.unwrap();
            let got = transport.recv_pubkey().await.unwrap();
            assert_eq!(got, pubkey);

            let mut invalid = vec![0x01];
            invalid.extend_from_slice(&pubkey);
            tx_to_transport.send(invalid).await.unwrap();
            let err = transport.recv_pubkey().await.unwrap_err();
            assert!(matches!(err, crate::FipsDroidError::InvalidPeerKey));
        });
    }

    #[test]
    fn close_and_timeout_behavior() {
        let rt = Builder::new_current_thread().enable_all().build().unwrap();
        rt.block_on(async {
            let (incoming_tx, incoming_rx) = mpsc::channel(4);
            let (outgoing_tx, _outgoing_rx) = mpsc::channel(4);
            let (close_tx, mut close_rx) = mpsc::channel(1);
            let mut transport = BleTransport::new(incoming_rx, outgoing_tx, close_tx);

            transport.close().await.unwrap();
            assert_eq!(close_rx.recv().await, Some(()));

            drop(incoming_tx);
            let mut buf = [0u8; 8];
            let n = transport.recv(&mut buf).await.unwrap();
            assert_eq!(n, 0);

            let (_tx2, rx2) = mpsc::channel(1);
            let (tx_out2, _rx_out2) = mpsc::channel(1);
            let (close_tx2, _close_rx2) = mpsc::channel(1);
            let mut blocked = BleTransport::new(rx2, tx_out2, close_tx2);
            let mut out = [0u8; 8];
            let timed = timeout(Duration::from_millis(20), blocked.recv(&mut out)).await;
            assert!(timed.is_err());
        });
    }
}
