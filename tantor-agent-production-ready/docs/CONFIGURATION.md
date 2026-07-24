# Configuration Reference

## `agent`

- `host_id`: stable agent identity. Leave empty to auto-generate and persist in `<data_dir>/host-id`.
- `agent_name`: UI/display name. Leave empty to use the VM hostname.
- `server_url`: required absolute `http://` or `https://` backend URL, including port when needed.
- `cert_file`, `key_file`: optional client certificate/key pair for mTLS.
- `ca_cert`: optional private CA PEM for HTTPS verification.
- `insecure_skip_verify`: default `false`.
- `poll_interval_seconds`: task polling interval, 1–3600 seconds.
- `heartbeat_interval_seconds`: heartbeat interval, 5–3600 seconds.
- `log_level`: `DEBUG`, `INFO`, `WARN`, or `ERROR`.

## `paths`

All paths must be absolute.

- `data_dir`: persistent agent state, including the generated host ID.
- `log_dir`: reserved agent/deployment log workspace.
- `artifacts_dir`: staging location for Kafka and related artifacts.

## `auth`

Supported modes:

- `none`: preserves the existing unauthenticated backend API behavior.
- `bearer`: reads a bearer token from `token` or preferably `token_file`.
- `basic`: uses `username` plus `password` or preferably `password_file`.

When both an inline secret and a secret file are configured, the file value wins.

## `http`

- `request_timeout_seconds`: API request timeout.
- `artifact_timeout_seconds`: artifact download timeout.
- `dial_timeout_seconds`: TCP connection timeout.
- `tls_handshake_timeout_seconds`: TLS handshake timeout.
- `idle_conn_timeout_seconds`: HTTP keep-alive idle timeout.
- `use_environment_proxy`: default `false` for predictable air-gapped routing.

## `privilege`

- `mode: sudo`: service runs non-root and privileged deployment commands use `sudo -n`.
- `mode: direct`: privileged commands are executed directly. This is normally used only when the service itself runs with adequate privileges.
- `sudo_path`: absolute path to `sudo`.

## Configuration precedence

1. Built-in safe defaults.
2. YAML configuration file.
3. `TANTOR_*` environment variables.
4. Automatically generated runtime identity when `host_id`/`agent_name` are empty.

The parser intentionally supports the simple scalar YAML structure shipped with this package. Unknown keys fail fast instead of being silently ignored.
