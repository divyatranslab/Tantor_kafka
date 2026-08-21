# Required production secrets

Create these files on the target host. Do not add their values to source
control, `.env.production`, command-line arguments or the release archive.

| File | Content |
|---|---|
| `TANTOR_DB_USER` | Dedicated PostgreSQL application username |
| `TANTOR_DB_PASSWORD` | Strong PostgreSQL password |
| `TANTOR_ENCRYPTION_KEY` | Random application encryption key |
| `TANTOR_JWT_SECRET` | Random JWT signing secret |
| `tls.crt` | PEM certificate including the full required chain |
| `tls.key` | PEM private key matching `tls.crt` |
| `agent-ca.crt` | PEM CA certificate used only to verify agent and discovery-agent client certificates |
| `monitoring-ca.crt` | PEM CA bundle trusted only by the dedicated Grafana/Prometheus HTTP client |

The secrets directory should be owned by the deployment administrator with
mode `0700`. Each file should use mode `0444`: the parent directory prevents
other host users from traversing to the files, while the file mode lets the
explicitly granted non-root container identity read its individual read-only
mount. Never mount the entire secrets directory into a container. Rotate
secrets by replacing the files atomically and recreating the affected
containers.
