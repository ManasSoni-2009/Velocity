use crate::error::ProtoError;
use byteorder::{BigEndian, ByteOrder};
use chacha20poly1305::aead::{AeadInPlace, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce, Tag};
use hkdf::Hkdf;
use sha2::Sha256;

pub const KEY_SIZE: usize = 32;
pub const NONCE_SIZE: usize = 12;
pub const TAG_SIZE: usize = 16;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Direction {
    ClientToServer = 0,
    ServerToClient = 1,
}

pub struct AeadCipher {
    cipher: ChaCha20Poly1305,
}

impl AeadCipher {
    pub fn new(key: &[u8; KEY_SIZE]) -> Self {
        let key = Key::from_slice(key);
        Self {
            cipher: ChaCha20Poly1305::new(key),
        }
    }

    /// Derive a 32-byte session key from a pre-shared key (PSK) and session_id
    pub fn derive_key(psk: &[u8], session_id: u64) -> Result<[u8; KEY_SIZE], ProtoError> {
        let salt = session_id.to_be_bytes();
        let hk = Hkdf::<Sha256>::new(Some(&salt), psk);
        let mut okm = [0u8; KEY_SIZE];
        hk.expand(b"velocity-vl01-aead", &mut okm)
            .map_err(|e| ProtoError::KeyDerivationFailed(format!("{:?}", e)))?;
        Ok(okm)
    }

    pub fn from_psk(psk: &[u8], session_id: u64) -> Result<Self, ProtoError> {
        let key = Self::derive_key(psk, session_id)?;
        Ok(Self::new(&key))
    }

    /// Construct deterministic 96-bit nonce from direction, path_id, and seq_num
    pub fn make_nonce(direction: Direction, path_id: u8, seq_num: u64) -> [u8; NONCE_SIZE] {
        let mut nonce = [0u8; NONCE_SIZE];
        nonce[0] = direction as u8;
        nonce[1] = path_id;
        nonce[2] = 0;
        nonce[3] = 0;
        BigEndian::write_u64(&mut nonce[4..12], seq_num);
        nonce
    }

    /// Encrypt plaintext in-place.
    /// `buffer` must contain plaintext at `buffer[..plaintext_len]` and have capacity for `plaintext_len + TAG_SIZE`.
    /// Returns total ciphertext length (plaintext_len + TAG_SIZE).
    pub fn encrypt_in_place(
        &self,
        direction: Direction,
        path_id: u8,
        seq_num: u64,
        aad: &[u8],
        buffer: &mut [u8],
        plaintext_len: usize,
    ) -> Result<usize, ProtoError> {
        if buffer.len() < plaintext_len + TAG_SIZE {
            return Err(ProtoError::BufferTooSmall {
                required: plaintext_len + TAG_SIZE,
                available: buffer.len(),
            });
        }

        let nonce_bytes = Self::make_nonce(direction, path_id, seq_num);
        let nonce = Nonce::from_slice(&nonce_bytes);

        let tag = self
            .cipher
            .encrypt_in_place_detached(nonce, aad, &mut buffer[..plaintext_len])
            .map_err(|_| ProtoError::DecryptionFailed)?;

        buffer[plaintext_len..plaintext_len + TAG_SIZE].copy_from_slice(&tag);
        Ok(plaintext_len + TAG_SIZE)
    }

    /// Decrypt ciphertext in-place.
    /// `buffer[..ciphertext_len]` contains payload + 16-byte tag.
    /// Returns plaintext length (ciphertext_len - TAG_SIZE).
    pub fn decrypt_in_place(
        &self,
        direction: Direction,
        path_id: u8,
        seq_num: u64,
        aad: &[u8],
        buffer: &mut [u8],
        ciphertext_len: usize,
    ) -> Result<usize, ProtoError> {
        if ciphertext_len < TAG_SIZE {
            return Err(ProtoError::BufferTooSmall {
                required: TAG_SIZE,
                available: ciphertext_len,
            });
        }

        let plaintext_len = ciphertext_len - TAG_SIZE;
        let nonce_bytes = Self::make_nonce(direction, path_id, seq_num);
        let nonce = Nonce::from_slice(&nonce_bytes);

        let mut tag_bytes = [0u8; TAG_SIZE];
        tag_bytes.copy_from_slice(&buffer[plaintext_len..ciphertext_len]);
        let tag = Tag::from_slice(&tag_bytes);

        self.cipher
            .decrypt_in_place_detached(nonce, aad, &mut buffer[..plaintext_len], tag)
            .map_err(|_| ProtoError::DecryptionFailed)?;

        Ok(plaintext_len)
    }
}
