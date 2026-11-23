use thiserror::Error;

#[derive(Error, Debug, PartialEq, Eq)]
pub enum ProtoError {
    #[error("Buffer too small: required {required}, available {available}")]
    BufferTooSmall { required: usize, available: usize },

    #[error("Invalid magic byte: expected 0x56, got 0x{0:02X}")]
    InvalidMagic(u8),

    #[error("Unsupported protocol version: expected 0x01, got 0x{0:02X}")]
    UnsupportedVersion(u8),

    #[error("Invalid flag combination: 0x{0:02X}")]
    InvalidFlags(u8),

    #[error("Decryption failed / authentication tag mismatch")]
    DecryptionFailed,

    #[error("Key derivation failed: {0}")]
    KeyDerivationFailed(String),

    #[error("Packet exceeds maximum MTU (1600 bytes): size {0}")]
    PacketTooLarge(usize),

    #[error("Malformed payload: {0}")]
    MalformedPayload(String),
}
