use crate::error::ProtoError;
use byteorder::{BigEndian, ByteOrder};

pub const CONTROL_TYPE_PROBE_REQ: u8 = 0x01;
pub const CONTROL_TYPE_PROBE_RESP: u8 = 0x02;
pub const CONTROL_TYPE_LINK_STATUS: u8 = 0x03;
pub const CONTROL_TYPE_SPEEDTEST_INIT: u8 = 0x10;
pub const CONTROL_TYPE_SPEEDTEST_DATA: u8 = 0x11;
pub const CONTROL_TYPE_SPEEDTEST_REPORT: u8 = 0x12;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProbePayload {
    pub msg_type: u8,
    pub path_id: u8,
    pub probe_id: u32,
    pub send_time_us: u32,
    pub echo_time_us: u32,
}

impl ProbePayload {
    pub const SIZE: usize = 14;

    pub fn new_req(path_id: u8, probe_id: u32, send_time_us: u32) -> Self {
        Self {
            msg_type: CONTROL_TYPE_PROBE_REQ,
            path_id,
            probe_id,
            send_time_us,
            echo_time_us: 0,
        }
    }

    pub fn new_resp(path_id: u8, probe_id: u32, send_time_us: u32, echo_time_us: u32) -> Self {
        Self {
            msg_type: CONTROL_TYPE_PROBE_RESP,
            path_id,
            probe_id,
            send_time_us,
            echo_time_us,
        }
    }

    pub fn encode(&self, dst: &mut [u8]) -> Result<usize, ProtoError> {
        if dst.len() < Self::SIZE {
            return Err(ProtoError::BufferTooSmall {
                required: Self::SIZE,
                available: dst.len(),
            });
        }
        dst[0] = self.msg_type;
        dst[1] = self.path_id;
        BigEndian::write_u32(&mut dst[2..6], self.probe_id);
        BigEndian::write_u32(&mut dst[6..10], self.send_time_us);
        BigEndian::write_u32(&mut dst[10..14], self.echo_time_us);
        Ok(Self::SIZE)
    }

    pub fn decode(src: &[u8]) -> Result<Self, ProtoError> {
        if src.len() < Self::SIZE {
            return Err(ProtoError::BufferTooSmall {
                required: Self::SIZE,
                available: src.len(),
            });
        }
        let msg_type = src[0];
        let path_id = src[1];
        let probe_id = BigEndian::read_u32(&src[2..6]);
        let send_time_us = BigEndian::read_u32(&src[6..10]);
        let echo_time_us = BigEndian::read_u32(&src[10..14]);
        Ok(Self {
            msg_type,
            path_id,
            probe_id,
            send_time_us,
            echo_time_us,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SpeedTestPacket {
    pub test_id: u32,
    pub chunk_index: u32,
    pub total_chunks: u32,
    pub timestamp_us: u32,
}

impl SpeedTestPacket {
    pub const HEADER_SIZE: usize = 16;

    pub fn encode(&self, dst: &mut [u8]) -> Result<usize, ProtoError> {
        if dst.len() < Self::HEADER_SIZE {
            return Err(ProtoError::BufferTooSmall {
                required: Self::HEADER_SIZE,
                available: dst.len(),
            });
        }
        BigEndian::write_u32(&mut dst[0..4], self.test_id);
        BigEndian::write_u32(&mut dst[4..8], self.chunk_index);
        BigEndian::write_u32(&mut dst[8..12], self.total_chunks);
        BigEndian::write_u32(&mut dst[12..16], self.timestamp_us);
        Ok(Self::HEADER_SIZE)
    }

    pub fn decode(src: &[u8]) -> Result<Self, ProtoError> {
        if src.len() < Self::HEADER_SIZE {
            return Err(ProtoError::BufferTooSmall {
                required: Self::HEADER_SIZE,
                available: src.len(),
            });
        }
        let test_id = BigEndian::read_u32(&src[0..4]);
        let chunk_index = BigEndian::read_u32(&src[4..8]);
        let total_chunks = BigEndian::read_u32(&src[8..12]);
        let timestamp_us = BigEndian::read_u32(&src[12..16]);
        Ok(Self {
            test_id,
            chunk_index,
            total_chunks,
            timestamp_us,
        })
    }
}
