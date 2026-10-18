//! Bellterm's Android core. The Kotlin app owns the screens and gestures;
//! this library owns everything below them: SSH to the host, Herdr's
//! remote bridge and protocol (through herdr-client), and the libghostty-vt
//! terminal that turns daemon surfaces into frames the app draws.

mod clipboard;
pub mod config;
mod error;
pub mod input;
mod jni_api;
pub mod keys;
pub mod render;
mod screen;
pub mod session;
pub mod ssh;
pub mod ssh_config;
pub mod theme;
pub mod view;
pub mod vt;

pub use error::{Error, Result};
pub use screen::Screen;
