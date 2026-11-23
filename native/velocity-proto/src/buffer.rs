use parking_lot::Mutex;
use std::ops::{Deref, DerefMut};
use std::sync::Arc;

pub const BUFFER_CAPACITY: usize = 1600;

pub struct PacketBuffer {
    data: Box<[u8; BUFFER_CAPACITY]>,
    len: usize,
}

impl Default for PacketBuffer {
    fn default() -> Self {
        Self::new()
    }
}

impl PacketBuffer {
    pub fn new() -> Self {
        Self {
            data: Box::new([0u8; BUFFER_CAPACITY]),
            len: 0,
        }
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.len
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    #[inline]
    pub fn capacity(&self) -> usize {
        BUFFER_CAPACITY
    }

    #[inline]
    pub fn set_len(&mut self, len: usize) {
        assert!(len <= BUFFER_CAPACITY, "len exceeds BUFFER_CAPACITY");
        self.len = len;
    }

    #[inline]
    pub fn clear(&mut self) {
        self.len = 0;
    }

    #[inline]
    pub fn as_slice(&self) -> &[u8] {
        &self.data[..self.len]
    }

    #[inline]
    pub fn as_mut_slice(&mut self) -> &mut [u8] {
        &mut self.data[..self.len]
    }

    #[inline]
    pub fn raw_storage_mut(&mut self) -> &mut [u8; BUFFER_CAPACITY] {
        &mut self.data
    }
}

impl Deref for PacketBuffer {
    type Target = [u8];

    #[inline]
    fn deref(&self) -> &Self::Target {
        self.as_slice()
    }
}

impl DerefMut for PacketBuffer {
    #[inline]
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.as_mut_slice()
    }
}

pub struct PacketBufferPool {
    pool: Mutex<Vec<PacketBuffer>>,
    max_capacity: usize,
}

impl PacketBufferPool {
    pub fn new(prealloc: usize, max_capacity: usize) -> Arc<Self> {
        let mut vec = Vec::with_capacity(prealloc);
        for _ in 0..prealloc {
            vec.push(PacketBuffer::new());
        }
        Arc::new(Self {
            pool: Mutex::new(vec),
            max_capacity,
        })
    }

    pub fn acquire(self: &Arc<Self>) -> PooledPacket {
        let mut pool = self.pool.lock();
        let mut buf = pool.pop().unwrap_or_else(PacketBuffer::new);
        buf.clear();
        PooledPacket {
            buffer: Some(buf),
            pool: Arc::clone(self),
        }
    }

    fn recycle(&self, mut buf: PacketBuffer) {
        let mut pool = self.pool.lock();
        if pool.len() < self.max_capacity {
            buf.clear();
            pool.push(buf);
        }
    }

    pub fn available(&self) -> usize {
        self.pool.lock().len()
    }
}

pub struct PooledPacket {
    buffer: Option<PacketBuffer>,
    pool: Arc<PacketBufferPool>,
}

impl PooledPacket {
    pub fn take_buffer(&mut self) -> PacketBuffer {
        self.buffer.take().unwrap_or_else(PacketBuffer::new)
    }

    pub fn buffer_mut(&mut self) -> &mut PacketBuffer {
        self.buffer.as_mut().expect("Buffer already taken")
    }

    pub fn buffer(&self) -> &PacketBuffer {
        self.buffer.as_ref().expect("Buffer already taken")
    }
}

impl Deref for PooledPacket {
    type Target = PacketBuffer;

    #[inline]
    fn deref(&self) -> &Self::Target {
        self.buffer()
    }
}

impl DerefMut for PooledPacket {
    #[inline]
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.buffer_mut()
    }
}

impl Drop for PooledPacket {
    fn drop(&mut self) {
        if let Some(buf) = self.buffer.take() {
            self.pool.recycle(buf);
        }
    }
}
