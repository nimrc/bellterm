//! The environment of the SSH tools this crate starts. A GUI application on
//! macOS inherits launchd's environment: a `PATH` without Homebrew and the
//! system agent socket, so `ssh` would be the system build and would not see
//! the keys a user's shell configures, such as a security key in another
//! agent. The application supplies how its children get the user's login
//! environment, and every `ssh` and `ssh-keygen` started here uses it.
#[cfg(unix)]
use std::ffi::OsStr;
use std::{process::Command, sync::OnceLock};

static APPLY: OnceLock<fn(&mut Command)> = OnceLock::new();

/// Sets, once per process, how this crate's SSH children get the user's
/// environment. `apply` may block the first time it runs; it is only called
/// where the command is built, which is off the UI thread. Returns `false`
/// when one was already set, which is kept.
pub fn set_command_environment(apply: fn(&mut Command)) -> bool {
    APPLY.set(apply).is_ok()
}

/// `program` with the application's environment applied. On Unix, a `PATH`
/// set on the command is also where `program` is looked up. Windows starts no
/// SSH children, so it never needs one.
#[cfg(unix)]
pub(crate) fn command(program: impl AsRef<OsStr>) -> Command {
    let mut command = Command::new(program);
    if let Some(apply) = APPLY.get() {
        apply(&mut command);
    }
    command
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    // The only test in this binary that sets the process-wide hook.
    #[test]
    fn ssh_children_get_the_application_environment() {
        fn apply(command: &mut Command) {
            command.env("HERDR_TEST_LOGIN_ENVIRONMENT", "1");
        }
        assert!(set_command_environment(apply));
        assert!(!set_command_environment(|_| {}));
        let command = command("ssh");
        assert_eq!(command.get_program(), "ssh");
        assert!(
            command
                .get_envs()
                .any(|(key, value)| key == "HERDR_TEST_LOGIN_ENVIRONMENT"
                    && value == Some(OsStr::new("1")))
        );
    }
}
