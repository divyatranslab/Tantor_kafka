# Security Notes

## 1. Agent-to-backend authentication is separate from Linux privilege

There are two different identities:

- The Linux service account that runs the agent process.
- The optional credential used to authenticate HTTP/HTTPS requests to the management backend.

The agent does not require SSH credentials to communicate with the backend.

## 2. TLS verification

The previous package always set `InsecureSkipVerify: true` and ignored certificate-loading failures. This package changes that behavior:

- HTTPS verifies the server certificate by default.
- A private CA can be supplied with `ca_cert`.
- mTLS is supported when both client certificate and key are configured.
- Insecure verification must be enabled explicitly.

## 3. Credential handling

For bearer or Basic authentication, use protected credential files. The installer copies them under `/etc/tantor-agent/credentials` with group-readable permissions for the configured service account.

The agent does not log token/password values.

Management-server `Authorization` headers are not forwarded to a different artifact host.

## 4. Air-gapped behavior

The release binary is static and needs no Go runtime or module download.

The source tree has no third-party Go dependencies and is validated with:

```bash
GOPROXY=off GOSUMDB=off go test ./...
```

`http.use_environment_proxy` defaults to `false` so accidental `HTTP_PROXY`/`HTTPS_PROXY` environment settings do not redirect agent traffic outside the intended network path.

The agent still connects to the explicitly configured internal management server and artifact URLs because those connections are its purpose.

## 5. Limited Linux account and privileged deployment features

The long-running service can run under a dedicated non-login account. However, the existing product functionality includes operations that inherently require root-level host changes, such as:

- creating/changing systemd units;
- changing ownership and installation directories;
- starting/stopping services;
- applying operating-system prerequisites;
- parcel activation/removal;
- reboot scheduling.

The installer can create a sudo command policy for these existing features. Because some inherited deployment flows execute controlled shell scripts with privileged `bash -c`, this should be treated as a powerful administrative capability, not as a strong security sandbox.

Recommended production controls:

- Use a dedicated non-login service account, not a human user.
- Restrict who can create tasks in the management backend/UI.
- Use authenticated HTTPS/mTLS where the backend supports it.
- Use `--configure-sudoers no` when the client security team provides its own reviewed privilege policy.
- Protect the agent binary/configuration from modification by the service user.

A stronger privilege boundary would require replacing all privileged shell-style deployment operations with a separately audited operation-specific root helper. That is an architectural hardening project beyond merely changing runtime configuration.

## 6. Artifact integrity

The existing Kafka, Connect, Schema Registry, ksqlDB, and parcel flows retain SHA-256 verification when the task/checksum contract provides an expected checksum or checksum header. Monitoring deployment retains its pre-existing behavior and should be supplied only from trusted internal artifacts until checksum enforcement is added there.
