//! The JNI surface of `app.bellterm.core.Native`. Sessions cross the
//! boundary as registry IDs rather than pointers, so no handle can dangle or
//! be freed twice; strings are parsed into domain types here and nowhere else.
//!
//! JNI resolves `Java_…` symbols by their exact names, so each export must be
//! `#[unsafe(no_mangle)]`; that attribute is the only unsafe code in this
//! crate, and every function body is safe Rust over the `jni` crate.
#![allow(unsafe_code)]

use crate::{
    Error, Result, config,
    input::Touch,
    keys,
    session::{self, Session},
    ssh_config,
    theme::Theme,
};
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jlong, jstring},
};
use std::{
    collections::HashMap,
    sync::{
        Arc, LazyLock, Mutex, PoisonError,
        atomic::{AtomicI64, Ordering},
    },
    time::Duration,
};
use zeroize::Zeroizing;

static SESSIONS: LazyLock<Mutex<HashMap<i64, Arc<Session>>>> = LazyLock::new(Mutex::default);
static NEXT_ID: AtomicI64 = AtomicI64::new(1);

fn session(id: jlong) -> Result<Arc<Session>> {
    SESSIONS
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .get(&id)
        .cloned()
        .ok_or(Error::NoSession)
}

fn string(env: &mut JNIEnv, value: &JString) -> Result<String> {
    Ok(env.get_string(value)?.into())
}

fn optional(env: &mut JNIEnv, value: &JString) -> Result<Option<String>> {
    if value.is_null() {
        return Ok(None);
    }
    string(env, value).map(Some)
}

fn new_string(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Raise `error` as a Java `IOException`; the caller returns a placeholder.
fn throw(env: &mut JNIEnv, error: &Error) {
    let _ = env.throw_new("java/io/IOException", error.to_string());
}

fn u16_of(value: jint) -> u16 {
    u16::try_from(value.max(0)).unwrap_or(u16::MAX)
}

fn u32_of(value: jint) -> u32 {
    u32::try_from(value.max(0)).unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_init(_env: JNIEnv, _class: JClass) {
    #[cfg(target_os = "android")]
    {
        use tracing_subscriber::layer::SubscriberExt as _;
        let subscriber = tracing_subscriber::registry().with(paranoid_android::layer("Bellterm"));
        let _ = tracing::subscriber::set_global_default(subscriber);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_generateKey(
    mut env: JNIEnv,
    _class: JClass,
    comment: JString,
) -> jstring {
    let result = string(&mut env, &comment).and_then(|comment| keys::generate(&comment));
    match result {
        Ok(identity) => new_string(&mut env, &identity.private),
        Err(error) => {
            throw(&mut env, &error);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_publicKey(
    mut env: JNIEnv,
    _class: JClass,
    private: JString,
) -> jstring {
    let result = string(&mut env, &private)
        .map(Zeroizing::new)
        .and_then(|private| keys::public_of(&private));
    match result {
        Ok(public) => new_string(&mut env, &public),
        Err(error) => {
            throw(&mut env, &error);
            std::ptr::null_mut()
        }
    }
}

/// Start connecting with the JSON document `config.rs` describes. The text
/// holds secrets: it is parsed into zeroizing types and not kept.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_connect(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jlong {
    let result = (|| {
        let json = Zeroizing::new(string(&mut env, &config_json)?);
        let session = Session::connect(config::parse(&json)?)?;
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        SESSIONS
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .insert(id, Arc::new(session));
        Ok(id)
    })();
    result.unwrap_or_else(|error| {
        throw(&mut env, &error);
        0
    })
}

/// Import key text; returns `{"private": …, "info": KeyInfo}`, the private
/// key decrypted for storage.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_importKey(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    passphrase: JString,
) -> jstring {
    let result = (|| {
        let text = Zeroizing::new(string(&mut env, &text)?);
        let passphrase = optional(&mut env, &passphrase)?.map(Zeroizing::new);
        let (private, info) = keys::import(&text, passphrase.as_deref().map(String::as_str))?;
        Ok::<_, Error>(Zeroizing::new(
            serde_json::json!({"private": private.as_str(), "info": info}).to_string(),
        ))
    })();
    match result {
        Ok(json) => new_string(&mut env, &json),
        Err(error) => {
            throw(&mut env, &error);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_certificateInfo(
    mut env: JNIEnv,
    _class: JClass,
    certificate: JString,
    private: JString,
) -> jstring {
    let result = (|| {
        let certificate = string(&mut env, &certificate)?;
        let private = Zeroizing::new(string(&mut env, &private)?);
        Ok::<_, Error>(serde_json::to_string(&keys::certificate(
            &certificate,
            &private,
        )?)?)
    })();
    match result {
        Ok(json) => new_string(&mut env, &json),
        Err(error) => {
            throw(&mut env, &error);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_parseSshConfig(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jstring {
    let result = string(&mut env, &text)
        .and_then(|text| Ok(serde_json::to_string(&ssh_config::parse(&text))?));
    match result {
        Ok(json) => new_string(&mut env, &json),
        Err(error) => {
            throw(&mut env, &error);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_nextEvent(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    timeout_ms: jint,
) -> jstring {
    let Ok(session) = session(id) else {
        return new_string(&mut env, r#"{"type":"closed"}"#);
    };
    let timeout = Duration::from_millis(u64::from(u32_of(timeout_ms)));
    match session.next_event(timeout) {
        Some(event) => new_string(&mut env, &event.to_json()),
        None => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_takeFrame(
    env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jbyteArray {
    let Some(frame) = session(id).ok().and_then(|s| s.take_frame()) else {
        return std::ptr::null_mut();
    };
    env.byte_array_from_slice(&frame.pack())
        .map(|array| array.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_takeView(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jstring {
    match session(id).ok().and_then(|s| s.take_view()) {
        Some(view) => new_string(&mut env, &view),
        None => std::ptr::null_mut(),
    }
}

/// Input failures (not yet connected, a full queue) are logged and dropped:
/// a keystroke is not worth an exception.
fn input(result: Result<()>) {
    if let Err(error) = result {
        tracing::debug!(%error, "input not sent");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_key(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    pane: JString,
    key_code: jint,
    meta: jint,
    unicode: jint,
    repeat: jboolean,
) -> jboolean {
    let result = (|| {
        let pane = optional(&mut env, &pane)?;
        let unicode = u32::try_from(unicode)
            .ok()
            .and_then(char::from_u32)
            .filter(|c| *c != '\0');
        session(id)?.key(pane.as_deref(), key_code, meta, unicode, repeat == JNI_TRUE)
    })();
    match result {
        Ok(true) => JNI_TRUE,
        Ok(false) => JNI_FALSE,
        Err(error) => {
            tracing::debug!(%error, "key not sent");
            JNI_FALSE
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_chord(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    pane: JString,
    codepoint: jint,
    meta: jint,
) {
    input((|| {
        let pane = optional(&mut env, &pane)?;
        let Some(ch) = u32::try_from(codepoint).ok().and_then(char::from_u32) else {
            return Ok(());
        };
        session(id)?.chord(pane.as_deref(), ch, meta)
    })());
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_text(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    pane: JString,
    text: JString,
) {
    input((|| {
        let pane = optional(&mut env, &pane)?;
        let text = string(&mut env, &text)?;
        session(id)?.text_input(pane.as_deref(), &text)
    })());
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_paste(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    pane: JString,
    text: JString,
) {
    input((|| {
        let pane = optional(&mut env, &pane)?;
        let text = string(&mut env, &text)?;
        session(id)?.paste(pane.as_deref(), &text)
    })());
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_touch(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    pane: JString,
    kind: jint,
    column: jint,
    row: jint,
    lines: jint,
) {
    input((|| {
        let pane = string(&mut env, &pane)?;
        let kind = match kind {
            1 => Touch::ScrollUp,
            2 => Touch::ScrollDown,
            _ => Touch::Tap,
        };
        session(id)?.touch(&pane, kind, u16_of(column), u16_of(row), u16_of(lines))
    })());
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_resize(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
    cols: jint,
    rows: jint,
    cell_width: jint,
    cell_height: jint,
) {
    input((|| {
        session(id)?.resize(session::options(
            u16_of(cols),
            u16_of(rows),
            u32_of(cell_width),
            u32_of(cell_height),
        ))
    })());
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_request(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    method: JString,
    params: JString,
) -> jstring {
    let result = (|| {
        let method = string(&mut env, &method)?;
        let params = serde_json::from_str(&string(&mut env, &params)?)?;
        session(id)?.request(&method, params)
    })();
    match result {
        Ok(request_id) => new_string(&mut env, &request_id),
        Err(error) => {
            throw(&mut env, &error);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_setTheme(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    theme_json: JString,
) {
    let result = (|| {
        let theme: Theme = serde_json::from_str(&string(&mut env, &theme_json)?)?;
        session(id)?.set_theme(theme);
        Ok::<_, Error>(())
    })();
    if let Err(error) = result {
        tracing::warn!(%error, "theme not applied");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_selectionText(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    start_column: jint,
    start_row: jint,
    end_column: jint,
    end_row: jint,
) -> jstring {
    let text = session(id)
        .map(|session| {
            session.text(
                (u16_of(start_column), u16_of(start_row)),
                (u16_of(end_column), u16_of(end_row)),
            )
        })
        .unwrap_or_default();
    new_string(&mut env, &text)
}

/// Drop the session. Its worker stops on its own thread; nothing is joined
/// here, so the UI thread never waits on the network.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bellterm_core_Native_disconnect(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    let session = SESSIONS
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .remove(&id);
    if let Some(session) = session {
        session.disconnect();
    }
}
