Throwaway keys generated with `ssh-keygen` only for the unit tests in
`src/keys/tests.rs`. They grant access to nothing. `encrypted_ed25519` uses
the passphrase `correct horse`. `user-cert.pub` and `user-cert-expired.pub`
certify `user` and were signed by a CA whose private key was discarded.
