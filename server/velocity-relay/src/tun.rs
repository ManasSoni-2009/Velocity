use std::io::{self, Read, Write};
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::info;

pub trait TunDevice: Send + Sync {
    fn read_packet(&mut self, buf: &mut [u8]) -> io::Result<usize>;
    fn write_packet(&mut self, buf: &[u8]) -> io::Result<usize>;
}

/// In-memory Virtual TUN for cross-platform testing and development
pub struct VirtualTun {
    pub tx: mpsc::Sender<Vec<u8>>,
    pub rx: mpsc::Receiver<Vec<u8>>,
}

impl VirtualTun {
    pub fn new_pair() -> (Self, Self) {
        let (tx1, rx1) = mpsc::channel(256);
        let (tx2, rx2) = mpsc::channel(256);
        (
            Self { tx: tx1, rx: rx2 },
            Self { tx: tx2, rx: rx1 },
        )
    }
}

impl TunDevice for VirtualTun {
    fn read_packet(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self.rx.blocking_recv() {
            Some(data) => {
                let len = data.len().min(buf.len());
                buf[..len].copy_from_slice(&data[..len]);
                Ok(len)
            }
            None => Err(io::Error::new(io::ErrorKind::UnexpectedEof, "Channel closed")),
        }
    }

    fn write_packet(&mut self, buf: &[u8]) -> io::Result<usize> {
        let _ = self.tx.blocking_send(buf.to_vec());
        Ok(buf.len())
    }
}

#[cfg(target_os = "linux")]
pub struct LinuxTun {
    file: std::fs::File,
    name: String,
}

#[cfg(target_os = "linux")]
impl LinuxTun {
    pub fn create(name: &str) -> io::Result<Self> {
        use std::os::unix::io::{FromRawFd, AsRawFd};
        use std::ffi::CString;

        let tun_fd = unsafe {
            libc::open(
                b"/dev/net/tun\0".as_ptr() as *const libc::c_char,
                libc::O_RDWR | libc::O_NONBLOCK,
            )
        };
        if tun_fd < 0 {
            return Err(io::Error::last_os_error());
        }

        #[repr(C)]
        struct ifreq {
            ifr_name: [libc::c_char; 16],
            ifr_flags: libc::c_short,
        }

        const IFF_TUN: libc::c_short = 0x0001;
        const IFF_NO_PI: libc::c_short = 0x1000;
        const TUNSETIFF: libc::c_ulong = 0x400454ca;

        let mut ifr: ifreq = unsafe { std::mem::zeroed() };
        ifr.ifr_flags = IFF_TUN | IFF_NO_PI;

        let c_name = CString::new(name).unwrap();
        let bytes = c_name.as_bytes_with_nul();
        for (i, &b) in bytes.iter().take(15).enumerate() {
            ifr.ifr_name[i] = b as libc::c_char;
        }

        let ret = unsafe { libc::ioctl(tun_fd, TUNSETIFF, &mut ifr) };
        if ret < 0 {
            unsafe { libc::close(tun_fd) };
            return Err(io::Error::last_os_error());
        }

        info!("Created Linux TUN interface: {}", name);
        let file = unsafe { std::fs::File::from_raw_fd(tun_fd) };
        Ok(Self {
            file,
            name: name.to_string(),
        })
    }
}

#[cfg(target_os = "linux")]
impl TunDevice for LinuxTun {
    fn read_packet(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.file.read(buf)
    }

    fn write_packet(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.file.write(buf)
    }
}
