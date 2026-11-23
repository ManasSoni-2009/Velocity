use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::runtime::Runtime;
use tracing::{error, info, warn, Level};
use velocity_core::config::{BondingMode, TunnelConfig};
use velocity_core::speedtest::SpeedTestEngine;
use velocity_core::tunnel::TunnelRuntime;

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
            error!("Invalid TunnelConfig JSON: {:?}", e);
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

    let runtime = match TunnelRuntime::new(tun_fd, config) {
        Ok(r) => r,
        Err(e) => {
            error!("Failed to create TunnelRuntime: {}", e);
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
    socket_fd: jint,
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
            use std::os::fd::FromRawFd;
            let std_sock = unsafe { std::net::UdpSocket::from_raw_fd(socket_fd) };
            let _ = std_sock.set_nonblocking(true);
            match tokio::net::UdpSocket::from_std(std_sock) {
                Ok(tokio_sock) => {
                    wrapper.runtime.register_socket(
                        path_id as u8,
                        tokio_sock,
                        name_str,
                        is_metered != 0,
                    )
                }
                Err(e) => {
                    error!("from_std socket error: {:?}", e);
                    false
                }
            }
        }
        #[cfg(not(unix))]
        {
            // Windows / host dev fallback
            match tokio::net::UdpSocket::bind("0.0.0.0:0").await {
                Ok(tokio_sock) => {
                    wrapper.runtime.register_socket(
                        path_id as u8,
                        tokio_sock,
                        name_str,
                        is_metered != 0,
                    )
                }
                Err(e) => {
                    error!("bind fallback socket error: {:?}", e);
                    false
                }
            }
        }
    });

    if res {
        if let Some(cb) = &wrapper.callback {
            cb.notify_state(2, "Multipath bonded and active");
        }
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
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeGetTelemetryJson(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return env.new_string("{}").unwrap().into_raw();
    }
    let wrapper = unsafe { &mut *(handle as *mut JniTunnelWrapper) };
    let json = wrapper.runtime.get_telemetry_json();

    if let Some(cb) = &wrapper.callback {
        cb.notify_telemetry(&json);
    }

    env.new_string(json).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_velocity_app_nativebridge_VelocityJni_nativeRunSpeedTestJson(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jstring {
    let json_str: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(_) => "{}".to_string(),
    };

    let config: TunnelConfig = serde_json::from_str(&json_str).unwrap_or_default();
    let engine = SpeedTestEngine::new(config);

    let rt = Runtime::new().unwrap();
    let result = rt.block_on(async {
        // Run test on dynamic ephemeral ports
        engine.run_test(0, 0).await
    });

    let res_json = match result {
        Ok(res) => serde_json::to_string(&res).unwrap_or_else(|_| "{}".to_string()),
        Err(e) => format!("{{\"error\":\"{}\"}}", e),
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
    let mut wrapper = unsafe { Box::from_raw(handle as *mut JniTunnelWrapper) };
    wrapper.runtime.stop();
    if let Some(cb) = &wrapper.callback {
        cb.notify_state(0, "Disconnected");
    }
}
