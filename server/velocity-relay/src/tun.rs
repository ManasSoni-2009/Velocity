use std::io;
#[cfg(target_os = "linux")]
use std::process::Command;
use std::sync::{mpsc, Mutex};
use std::time::Duration;
#[cfg(target_os = "linux")]
use tracing::{info, warn};

pub trait TunDevice: Send + Sync {
    fn read_packet(&mut self, buf: &mut [u8]) -> io::Result<usize>;
    fn write_packet(&mut self, buf: &[u8]) -> io::Result<usize>;
}

pub trait TunReader: Send {
    fn read_packet(&mut self, buf: &mut [u8]) -> io::Result<usize>;
}

pub trait TunWriter: Send + Sync {
    fn write_packet(&self, buf: &[u8]) -> io::Result<usize>;
}

/// In-memory Virtual TUN for cross-platform testing and development.
pub struct VirtualTun {
    pub tx: Mutex<mpsc::SyncSender<Vec<u8>>>,
    pub rx: Mutex<mpsc::Receiver<Vec<u8>>>,
}

pub struct VirtualTunReader {
    rx: mpsc::Receiver<Vec<u8>>,
}

pub struct VirtualTunWriter {
    tx: Mutex<mpsc::SyncSender<Vec<u8>>>,
}

impl VirtualTun {
    pub fn new_pair() -> (Self, Self) {
        let (tx1, rx1) = mpsc::sync_channel(256);
        let (tx2, rx2) = mpsc::sync_channel(256);
        (
            Self {
                tx: Mutex::new(tx1),
                rx: Mutex::new(rx2),
            },
            Self {
                tx: Mutex::new(tx2),
                rx: Mutex::new(rx1),
            },
        )
    }

    pub fn split(self) -> (VirtualTunReader, VirtualTunWriter) {
        let rx = self.rx.into_inner().expect("VirtualTun rx mutex poisoned");
        (
            VirtualTunReader { rx },
            VirtualTunWriter { tx: self.tx },
        )
    }
}

impl TunReader for VirtualTunReader {
    fn read_packet(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self.rx.recv_timeout(Duration::from_millis(50)) {
            Ok(data) => {
                let len = data.len().min(buf.len());
                buf[..len].copy_from_slice(&data[..len]);
                Ok(len)
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {
                Err(io::Error::new(io::ErrorKind::WouldBlock, "Timed out"))
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                Err(io::Error::new(io::ErrorKind::UnexpectedEof, "Channel closed"))
            }
        }
    }
}

impl TunWriter for VirtualTunWriter {
    fn write_packet(&self, buf: &[u8]) -> io::Result<usize> {
        if let Ok(tx) = self.tx.lock() {
            let _ = tx.try_send(buf.to_vec());
        }
        Ok(buf.len())
    }
}

impl TunDevice for VirtualTun {
    fn read_packet(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let rx = self.rx.get_mut().map_err(|_| io::Error::other("VirtualTun rx mutex poisoned"))?;
        match rx.recv_timeout(Duration::from_millis(250)) {
            Ok(data) => {
                let len = data.len().min(buf.len());
                buf[..len].copy_from_slice(&data[..len]);
                Ok(len)
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {
                Err(io::Error::new(io::ErrorKind::WouldBlock, "Timed out"))
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                Err(io::Error::new(io::ErrorKind::UnexpectedEof, "Channel closed"))
            }
        }
    }

    fn write_packet(&mut self, buf: &[u8]) -> io::Result<usize> {
        if let Ok(tx) = self.tx.lock() {
            let _ = tx.try_send(buf.to_vec());
        }
        Ok(buf.len())
    }
}

#[cfg(target_os = "linux")]
pub struct LinuxTun {
    read_file: std::fs::File,
    write_file: std::fs::File,
    name: String,
}

#[cfg(target_os = "linux")]
pub struct LinuxTunReader {
    file: std::fs::File,
}

#[cfg(target_os = "linux")]
pub struct LinuxTunWriter {
    file: std::fs::File,
}

#[cfg(target_os = "linux")]
impl LinuxTun {
    pub fn create(name: &str, tun_cidr: &str, mtu: usize) -> io::Result<Self> {
        use std::ffi::CString;
        use std::os::unix::io::FromRawFd;

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

        let write_fd = unsafe { libc::dup(tun_fd) };
        if write_fd < 0 {
            unsafe { libc::close(tun_fd) };
            return Err(io::Error::last_os_error());
        }

        Self::configure_interface(name, tun_cidr, mtu);

        info!("Created and configured Linux TUN interface: {} ({}, MTU {})", name, tun_cidr, mtu);
        let read_file = unsafe { std::fs::File::from_raw_fd(tun_fd) };
        let write_file = unsafe { std::fs::File::from_raw_fd(write_fd) };
        Ok(Self {
            read_file,
            write_file,
            name: name.to_string(),
        })
    }

    fn configure_interface(name: &str, tun_cidr: &str, mtu: usize) {
        if let Err(e) = Command::new("ip")
            .args(["addr", "replace", tun_cidr, "dev", name])
            .status()
        {
            warn!("Failed to assign TUN IP {} on {}: {}", tun_cidr, name, e);
        }
        if let Err(e) = Command::new("ip")
            .args(["link", "set", "dev", name, "mtu", &mtu.to_string(), "txqueuelen", "4096", "up"])
            .status()
        {
            warn!("Failed to bring TUN {} UP: {}", name, e);
        }
    }

    pub fn split(self) -> (LinuxTunReader, LinuxTunWriter) {
        (
            LinuxTunReader { file: self.read_file },
            LinuxTunWriter { file: self.write_file },
        )
    }
}

#[cfg(target_os = "linux")]
impl TunReader for LinuxTunReader {
    fn read_packet(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        use std::io::Read;
        use std::os::unix::io::AsRawFd;
        let mut pfd = libc::pollfd {
            fd: self.file.as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        };
        let ready = unsafe { libc::poll(&mut pfd, 1, 50) };
        if ready < 0 {
            return Err(io::Error::last_os_error());
        }
        if ready == 0 {
            return Err(io::Error::new(io::ErrorKind::WouldBlock, "Poll timeout"));
        }
        self.file.read(buf)
    }
}

#[cfg(target_os = "linux")]
impl TunWriter for LinuxTunWriter {
    fn write_packet(&self, buf: &[u8]) -> io::Result<usize> {
        use std::os::unix::io::AsRawFd;
        let written = unsafe {
            libc::write(
                self.file.as_raw_fd(),
                buf.as_ptr() as *const libc::c_void,
                buf.len(),
            )
        };
        if written < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(written as usize)
        }
    }
}

#[cfg(target_os = "linux")]
impl TunDevice for LinuxTun {
    fn read_packet(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        use std::io::Read;
        self.read_file.read(buf)
    }

    fn write_packet(&mut self, buf: &[u8]) -> io::Result<usize> {
        use std::io::Write;
        self.write_file.write(buf)
    }
}
