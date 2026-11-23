use crate::error::ProtoError;
use byteorder::{BigEndian, ByteOrder};

pub const VL01_MAGIC: u8 = 0x56; // 'V'
pub const VL01_VERSION: u8 = 0x01;
pub const HEADER_SIZE: usize = 40;

/// Packet flags for VL01 framing
pub mod flags {
    pub const DATA: u8 = 0x01;
    pub const CONTROL_PROBE: u8 = 0x02;
    pub const FEC_PARITY: u8 = 0x04;
    pub const DUPLICATE: u8 = 0x08;
    pub const KEEPALIVE: u8 = 0x10;
    pub const SPEEDTEST: u8 = 0x20;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Vl01Header {
    pub magic: u8,
    pub version: u8,
    pub flags: u8,
    pub path_id: u8,
    pub session_id: u64,
    pub seq_num: u64,
    pub ack_base: u64,
    pub ack_bitmap: u64,
    pub tx_timestamp_us: u32,
}

impl Default for Vl01Header {
    fn default() -> Self {
        Self {
            magic: VL01_MAGIC,
            version: VL01_VERSION,
            flags: flags::DATA,
            path_id: 0,
            session_id: 0,
            seq_num: 0,
            ack_base: 0,
            ack_bitmap: 0,
            tx_timestamp_us: 0,
        }
    }
}

impl Vl01Header {
    pub fn new(flags: u8, path_id: u8, session_id: u64, seq_num: u64) -> Self {
        Self {
            magic: VL01_MAGIC,
            version: VL01_VERSION,
            flags,
            path_id,
            session_id,
            seq_num,
            ack_base: 0,
            ack_bitmap: 0,
            tx_timestamp_us: 0,
        }
    }

    pub fn is_data(&self) -> bool {
        (self.flags & flags::DATA) != 0
    }

    pub fn is_control_probe(&self) -> bool {
        (self.flags & flags::CONTROL_PROBE) != 0
    }

    pub fn is_fec_parity(&self) -> bool {
        (self.flags & flags::FEC_PARITY) != 0
    }

    pub fn is_duplicate(&self) -> bool {
        (self.flags & flags::DUPLICATE) != 0
    }

    pub fn is_keepalive(&self) -> bool {
        (self.flags & flags::KEEPALIVE) != 0
    }

    pub fn is_speedtest(&self) -> bool {
        (self.flags & flags::SPEEDTEST) != 0
    }

    /// Encode header into a byte slice of at least HEADER_SIZE (40 bytes)
    pub fn encode(&self, dst: &mut [u8]) -> Result<usize, ProtoError> {
        if dst.len() < HEADER_SIZE {
            return Err(ProtoError::BufferTooSmall {
                required: HEADER_SIZE,
                available: dst.len(),
            });
        }

        dst[0] = self.magic;
        dst[1] = self.version;
        dst[2] = self.flags;
        dst[3] = self.path_id;
        BigEndian::write_u64(&mut dst[4..12], self.session_id);
        BigEndian::write_u64(&mut dst[12..20], self.seq_num);
        BigEndian::write_u64(&mut dst[20..28], self.ack_base);
        BigEndian::write_u64(&mut dst[28..36], self.ack_bitmap);
        BigEndian::write_u32(&mut dst[36..40], self.tx_timestamp_us);

        Ok(HEADER_SIZE)
    }

    /// Decode header from byte slice of at least HEADER_SIZE
    pub fn decode(src: &[u8]) -> Result<Self, ProtoError> {
        if src.len() < HEADER_SIZE {
            return Err(ProtoError::BufferTooSmall {
                required: HEADER_SIZE,
                available: src.len(),
            });
        }

        let magic = src[0];
        if magic != VL01_MAGIC {
            return Err(ProtoError::InvalidMagic(magic));
        }

        let version = src[1];
        if version != VL01_VERSION {
            return Err(ProtoError::UnsupportedVersion(version));
        }

        let flags = src[2];
        let path_id = src[3];
        let session_id = BigEndian::read_u64(&src[4..12]);
        let seq_num = BigEndian::read_u64(&src[12..20]);
        let ack_base = BigEndian::read_u64(&src[20..28]);
        let ack_bitmap = BigEndian::read_u64(&src[28..36]);
        let tx_timestamp_us = BigEndian::read_u32(&src[36..40]);

        Ok(Self {
            magic,
            version,
            flags,
            path_id,
            session_id,
            seq_num,
            ack_base,
            ack_bitmap,
            tx_timestamp_us,
        })
    }
}
