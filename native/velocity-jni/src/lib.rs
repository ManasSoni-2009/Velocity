use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::runtime::Runtime;
use tokio::sync::mpsc;
use tracing::{error, info, warn, Level};
use velocity_core::config::{BondingMode, TunnelConfig};
use velocity_core::speedtest::SpeedTestEngine;
use velocity_core::tunnel::{probe_server_udp_detailed, TunnelEvent, TunnelRuntime};

static INITIALIZED: AtomicBool = AtomicBool::new(false);

struct JniCallbackHandler {
    jvm: jni::JavaVM,
    callback_global: jni::objects::GlobalRef,
}

impl JniCallbackHandler {
    fn new(env: &mut JNIEnv, callback: &JObject) -> Result<Self, String> {
        let jvm = env.get_java_vm().map_err(|e| format!("{:?}", e))?;
        let callback_global = env.new_global_ref(callback).map_err(|e| format!("{:?}", e))?;
        Ok(Self {
            jvm,
            callback_global,
        })
    }

    fn notify_telemetry(&self, json: &str) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            if let Ok(jstr) = env.new_string(json) {
                let _ = env.call_method(
                    &self.callback_global,
                    "onTelemetryUpdate",
                    "(Ljava/lang/String;)V",
                    &[JValue::Object(&jstr)],
                );
            }
        }
    }

    fn notify_state(&self, state: i32, msg: &str) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            if let Ok(jstr) = env.new_string(msg) {
                let _ = env.call_method(
                    &self.callback_global,
                    "onStateChanged",
                    "(ILjava/lang/String;)V",
                    &[JValue::Int(state), JValue::Object(&jstr)],
                );
            }
        }
    }
}

pub struct JniTunnelWrapper {
    pub runtime: TunnelRuntime,
    pub tokio_rt: Runtime,
    callback: Option<Arc<JniCallbackHandler>>,
}

#[cfg(unix)]
unsafe fn dup_udp_socket(fd: jint) -> Option<tokio::net::UdpSocket> {
    use std::os::fd::FromRawFd;
    if fd < 0 {
        return None;
    }
    let duped = libc::dup(fd);
    if duped < 0 {
        return None;
    }
    let std_sock = std::net::UdpSocket::from_raw_fd(duped);
    let _ = std_sock.set_nonblocking(true);
    tokio::net::UdpSocket::from_std(std_sock).ok()
}

#[no_mangle]
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeInit(
    _env: JNIEnv,
    _class: JClass,
    log_level: jint,
) {
    if !INITIALIZED.swap(true, Ordering::SeqCst) {
        let level = match log_level {
            0 => Level::ERROR,
            1 => Level::WARN,
            2 => Level::INFO,
            3 => Level::DEBUG,
            _ => Level::TRACE,
        };
        let _ = tracing_subscriber::fmt()
            .with_max_level(level)
            .with_ansi(false)
            .try_init();
        info!("Velocity JNI native core initialized with level: {:?}", level);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeStartTunnel(
    mut env: JNIEnv,
    _class: JClass,
    tun_fd: jint,
    config_json: JString,
    callback: JObject,
) -> jlong {
    let json_str: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("Failed to get config string: {:?}", e);
            return 0;
        }
    };

    let config: TunnelConfig = match serde_json::from_str(&json_str) {
        Ok(c) => c,
        Err(e) => {
            error!("Invalid TunnelConfig JSON: {:?} (raw={})", e, json_str);
            return 0;
        }
    };

    let tokio_rt = match Runtime::new() {
        Ok(rt) => rt,
        Err(e) => {
            error!("Failed to initialize Tokio runtime: {:?}", e);
            return 0;
        }
    };

    let callback_handler = match JniCallbackHandler::new(&mut env, &callback) {
        Ok(h) => Some(Arc::new(h)),
        Err(e) => {
            warn!("TelemetryCallback global ref failed: {}", e);
            None
        }
    };

    let (event_tx, mut event_rx) = mpsc::unbounded_channel::<TunnelEvent>();

    let runtime = match TunnelRuntime::new_with_options(tun_fd, config, Some(event_tx), None) {
        Ok(r) => r,
        Err(e) => {
            error!("Failed to create TunnelRuntime: {}", e);
            if let Some(cb) = &callback_handler {
                cb.notify_state(4, &e);
            }
            return 0;
        }
    };

    // Enter Tokio runtime to start housekeeping loop & forward events to Kotlin callback
    let cb_clone = callback_handler.clone();
    tokio_rt.block_on(async {
        runtime.start_housekeeping();
        tokio::spawn(async move {
            while let Some(ev) = event_rx.recv().await {
                if let Some(cb) = &cb_clone {
                    match ev {
                        TunnelEvent::StateChanged { state, message } => {
                            cb.notify_state(state as i32, &message);
                        }
                        TunnelEvent::Telemetry(tel) => {
                            if let Ok(json) = serde_json::to_string(&tel) {
                                cb.notify_telemetry(&json);
                            }
                        }
                    }
                }
            }
        });
    });

    if let Some(cb) = &callback_handler {
        cb.notify_state(1, "Connecting to multipath relay...");
    }

    let wrapper = Box::new(JniTunnelWrapper {
        runtime,
        tokio_rt,
        callback: callback_handler,
    });

    Box::into_raw(wrapper) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeRegisterSocket(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path_id: jint,
    #[allow(unused_variables)] socket_fd: jint,
    link_name: JString,
    is_metered: jboolean,
) -> jboolean {
    if handle == 0 {
        return JNI_FALSE;
    }
    let wrapper = unsafe { &mut *(handle as *mut JniTunnelWrapper) };
    let name_str: String = match env.get_string(&link_name) {
        Ok(s) => s.into(),
        Err(_) => "unknown".to_string(),
    };

    let res = wrapper.tokio_rt.block_on(async {
        #[cfg(unix)]
        {
            match unsafe { dup_udp_socket(socket_fd) } {
                Some(tokio_sock) => wrapper.runtime.register_socket(
                    path_id as u8,
                    tokio_sock,
                    name_str,
                    is_metered != 0,
                ),
                None => {
                    error!("dup_udp_socket failed for fd={}", socket_fd);
                    false
                }
            }
        }
        #[cfg(not(unix))]
        {
            match tokio::net::UdpSocket::bind("0.0.0.0:0").await {
                Ok(tokio_sock) => wrapper.runtime.register_socket(
                    path_id as u8,
                    tokio_sock,
                    name_str,
                    is_metered != 0,
                ),
                Err(e) => {
                    error!("bind fallback socket error: {:?}", e);
                    false
                }
            }
        }
    });

    if res {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeUnregisterSocket(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path_id: jint,
) {
    if handle == 0 {
        return;
    }
    let wrapper = unsafe { &mut *(handle as *mut JniTunnelWrapper) };
    wrapper.runtime.unregister_socket(path_id as u8);
}

#[no_mangle]
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeUpdateLinkMetrics(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path_id: jint,
    rssi_dbm: jint,
    link_speed_mbps: jint,
) {
    if handle == 0 {
        return;
    }
    let wrapper = unsafe { &mut *(handle as *mut JniTunnelWrapper) };
    wrapper
        .runtime
        .update_link_metrics(path_id as u8, rssi_dbm, link_speed_mbps);
}

#[no_mangle]
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeSetMode(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    mode: jint,
) {
    if handle == 0 {
        return;
    }
    let wrapper = unsafe { &mut *(handle as *mut JniTunnelWrapper) };
    wrapper.runtime.set_mode(BondingMode::from(mode));
}

#[no_mangle]
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeUpdateConfig(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    wifi_bias_percent: jint,
    fec_enabled: jboolean,
) {
    if handle == 0 {
        return;
    }
    let wrapper = unsafe { &mut *(handle as *mut JniTunnelWrapper) };
    wrapper
        .runtime
        .update_tuning(wifi_bias_percent.clamp(0, 100) as u8, fec_enabled != 0);
}

#[no_mangle]
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeGetTelemetryJson(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return env.new_string("{}").unwrap().into_raw();
    }
    let wrapper = unsafe { &mut *(handle as *mut JniTunnelWrapper) };
    let json = wrapper.runtime.get_telemetry_json();
    env.new_string(json).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeProbeServerJson(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
    path_id: jint,
    socket_fd: jint,
) -> jstring {
    let json_str: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(_) => "{}".to_string(),
    };

    let config: TunnelConfig = match serde_json::from_str(&json_str) {
        Ok(c) => c,
        Err(e) => {
            let err_json = serde_json::json!({ "reachable": false, "error": format!("Invalid config: {}", e) }).to_string();
            return env.new_string(err_json).unwrap().into_raw();
        }
    };

    let rt = match Runtime::new() {
        Ok(r) => r,
        Err(e) => {
            let err_json = serde_json::json!({ "reachable": false, "error": e.to_string() }).to_string();
            return env.new_string(err_json).unwrap().into_raw();
        }
    };

    let res = rt.block_on(async {
        #[cfg(unix)]
        let sock = unsafe { dup_udp_socket(socket_fd) };
        #[cfg(not(unix))]
        let sock = {
            let _ = socket_fd;
            None
        };
        probe_server_udp_detailed(&config.server_host, config.server_port, &config.psk, path_id as u8, sock, 1500).await
    });

    let out = match res {
        Ok(outcome) => serde_json::json!({
            "reachable": true,
            "rttMs": outcome.rtt_ms.round() as i32,
            "quota": outcome.quota
        })
        .to_string(),
        Err(e) => serde_json::json!({ "reachable": false, "error": e }).to_string(),
    };
    env.new_string(out).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeRunSpeedTestJson(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jstring {
    run_speed_test_internal(&mut env, config_json, -1, -1)
}

#[no_mangle]
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeRunMultipathSpeedTestJson(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
    wifi_fd: jint,
    cell_fd: jint,
) -> jstring {
    run_speed_test_internal(&mut env, config_json, wifi_fd, cell_fd)
}

fn run_speed_test_internal(
    env: &mut JNIEnv,
    config_json: JString,
    wifi_fd: jint,
    cell_fd: jint,
) -> jstring {
    let json_str: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(_) => "{}".to_string(),
    };

    let config: TunnelConfig = match serde_json::from_str(&json_str) {
        Ok(c) => c,
        Err(e) => {
            let err_json = serde_json::json!({ "error": format!("Invalid config: {}", e) }).to_string();
            return env.new_string(err_json).unwrap().into_raw();
        }
    };

    let engine = SpeedTestEngine::new(config);
    let rt = match Runtime::new() {
        Ok(r) => r,
        Err(e) => {
            let err_json = serde_json::json!({ "error": e.to_string() }).to_string();
            return env.new_string(err_json).unwrap().into_raw();
        }
    };

    let result = rt.block_on(async {
        if wifi_fd >= 0 || cell_fd >= 0 {
            #[cfg(unix)]
            let ws = unsafe { dup_udp_socket(wifi_fd) }.map(Arc::new);
            #[cfg(not(unix))]
            let ws = if wifi_fd >= 0 {
                tokio::net::UdpSocket::bind("0.0.0.0:0").await.ok().map(Arc::new)
            } else {
                None
            };

            #[cfg(unix)]
            let cs = unsafe { dup_udp_socket(cell_fd) }.map(Arc::new);
            #[cfg(not(unix))]
            let cs = if cell_fd >= 0 {
                tokio::net::UdpSocket::bind("0.0.0.0:0").await.ok().map(Arc::new)
            } else {
                None
            };

            engine.run_with_sockets(ws, cs).await
        } else {
            engine.run_test(0, 0).await
        }
    });

    let res_json = match result {
        Ok(res) => serde_json::to_string(&res).unwrap_or_else(|_| "{}".to_string()),
        Err(e) => serde_json::json!({ "error": e }).to_string(),
    };

    env.new_string(res_json).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeStopTunnel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let wrapper = unsafe { Box::from_raw(handle as *mut JniTunnelWrapper) };
    wrapper.runtime.stop();
    if let Some(cb) = &wrapper.callback {
        cb.notify_state(0, "Disconnected");
    }
}
