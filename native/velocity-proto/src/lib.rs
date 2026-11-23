pub mod buffer;
pub mod control;
pub mod crypto;
pub mod error;
pub mod header;

pub use buffer::{PacketBuffer, PacketBufferPool, PooledPacket, BUFFER_CAPACITY};
pub use control::{ProbePayload, SpeedTestPacket};
pub use crypto::{AeadCipher, Direction, KEY_SIZE, NONCE_SIZE, TAG_SIZE};
pub use error::ProtoError;
pub use header::{flags, Vl01Header, HEADER_SIZE, VL01_MAGIC, VL01_VERSION};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_header_encode_decode() {
        let original = Vl01Header {
            magic: VL01_MAGIC,
            version: VL01_VERSION,
            flags: flags::DATA | flags::SPEEDTEST,
            path_id: 1,
            session_id: 0x0123_4567_89AB_CDEF,
            seq_num: 42,
            ack_base: 40,
            ack_bitmap: 0b11,
            tx_timestamp_us: 1234567,
        };

        let mut buf = [0u8; HEADER_SIZE];
        let bytes_written = original.encode(&mut buf).unwrap();
        assert_eq!(bytes_written, HEADER_SIZE);

        let decoded = Vl01Header::decode(&buf).unwrap();
        assert_eq!(original, decoded);
    }

    #[test]
    fn test_header_invalid_magic_version() {
        let mut buf = [0u8; HEADER_SIZE];
        buf[0] = 0xFF; // bad magic
        assert!(matches!(
            Vl01Header::decode(&buf),
            Err(ProtoError::InvalidMagic(0xFF))
        ));

        buf[0] = VL01_MAGIC;
        buf[1] = 0x99; // bad version
        assert!(matches!(
            Vl01Header::decode(&buf),
            Err(ProtoError::UnsupportedVersion(0x99))
        ));
    }

    #[test]
    fn test_aead_roundtrip_and_tamper_detection() {
        let psk = b"velocity_secret_key_very_secure";
        let session_id = 99999999;
        let cipher = AeadCipher::from_psk(psk, session_id).unwrap();

        let mut header = Vl01Header::new(flags::DATA, 0, session_id, 100);
        let mut header_bytes = [0u8; HEADER_SIZE];
        header.encode(&mut header_bytes).unwrap();

        let payload = b"Hello Velocity Multipath Protocol VL01!";
        let mut packet_buf = [0u8; 1600];
        packet_buf[..payload.len()].copy_from_slice(payload);

        let cipher_len = cipher
            .encrypt_in_place(
                Direction::ClientToServer,
                0,
                100,
                &header_bytes,
                &mut packet_buf,
                payload.len(),
            )
            .unwrap();

        assert_eq!(cipher_len, payload.len() + TAG_SIZE);

        // Tamper with header AAD - must fail verification
        let mut tampered_header = header_bytes;
        tampered_header[3] = 1; // alter path_id
        let mut decrypt_buf = packet_buf;
        let tamper_res = cipher.decrypt_in_place(
            Direction::ClientToServer,
            0,
            100,
            &tampered_header,
            &mut decrypt_buf,
            cipher_len,
        );
        assert!(matches!(tamper_res, Err(ProtoError::DecryptionFailed)));

        // Tamper with payload byte - must fail verification
        let mut tampered_payload = packet_buf;
        tampered_payload[0] ^= 0x01;
        let tamper_res2 = cipher.decrypt_in_place(
            Direction::ClientToServer,
            0,
            100,
            &header_bytes,
            &mut tampered_payload,
            cipher_len,
        );
        assert!(matches!(tamper_res2, Err(ProtoError::DecryptionFailed)));

        // Correct decryption
        let mut success_buf = packet_buf;
        let plain_len = cipher
            .decrypt_in_place(
                Direction::ClientToServer,
                0,
                100,
                &header_bytes,
                &mut success_buf,
                cipher_len,
            )
            .unwrap();
        assert_eq!(&success_buf[..plain_len], payload);
    }

    #[test]
    fn test_packet_buffer_pool() {
        let pool = PacketBufferPool::new(4, 16);
        assert_eq!(pool.available(), 4);

        {
            let mut p1 = pool.acquire();
            assert_eq!(pool.available(), 3);
            p1.raw_storage_mut()[0] = 42;
            p1.set_len(10);
            assert_eq!(p1.len(), 10);
        }
        // Dropped, recycled back to pool
        assert_eq!(pool.available(), 4);
    }
}
