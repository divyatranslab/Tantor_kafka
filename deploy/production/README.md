# Tantor production container deployment

This deployment uses immutable image references, non-root application
containers, read-only root filesystems, explicit resource/PID limits, health
checks, least-privilege capabilities, file-mounted secrets and separated
application/data networks.

## Prerequisites

- RHEL 9-compatible Linux on x86-64 with cgroup v2.
- Podman with a compatible `podman-compose` provider.
- Trivy and Syft in the controlled release-build environment.
- TCP 443 exposed to browsers and agents; TCP 80 is redirect-only.
- No external access to database, backend or artifact-repository ports.

## Installation

1. Verify every entry in `SHA256SUMS` before extracting or loading images.
2. Create the `secrets` directory with mode `0700`.
3. Create every file documented in `SECRETS.md` with mode `0444` inside the
   administrator-only `0700` secrets directory. This permits the explicitly
   granted non-root containers to read their individual read-only mounts.
4. Ensure the TLS certificate SAN contains the production Tantor hostname.
   Provision `agent-ca.crt` separately and issue each agent a unique client
   certificate and private key outside the release archive.
5. Package the release with the production OIDC issuer/audience and Keycloak
   origin/realm parameters. Packaging generates checksummed `ui-runtime-config.js`
   and `nginx.conf` files and rejects UI/backend identity-provider drift.
6. Run `bash ./start.sh` from the extracted bundle directory.
7. Confirm every service becomes healthy and only ports 80 and 443 are
published by the Tantor deployment.

## TLS trust boundary

Nginx is the production TLS endpoint and supports TLS 1.2 and 1.3. Port 80 is
redirect-only; the Spring management server and Artifact Repository are not
published and use HTTP only on the Compose `app` network, which is marked
internal. Nginx replaces forwarding headers before Spring consumes them, and
the backend port has no host mapping, so an external client cannot spoof the
original scheme or address. Agent API routes
and discovery report/task routes require a client certificate issued by the
mounted `agent-ca.crt`; normal browser routes retain application-level
authentication.

Agents must use the public HTTPS hostname and must be provisioned with the
control-plane CA plus their unique certificate/key. Missing, invalid, expired,
untrusted, or wrong-host certificates fail closed. Installer scripts do not
generate mock credentials or permit unverified downloads.

The dedicated Java monitoring client never changes JVM-wide HTTPS defaults,
does not follow redirects, and validates hostnames using TLS 1.2/1.3. Public-CA
Grafana endpoints use normal JVM trust. For a private CA, mount the CA file and
the required `monitoring-ca.crt` is mounted only into that client process. The
direct Prometheus default is intentionally HTTP only for the private monitoring service network;
an externally routed Prometheus endpoint must use HTTPS.

The database name and private service endpoint are fixed as `tantor` and
`database:5432`. Supply the database username and password only through the
`TANTOR_DB_USER` and `TANTOR_DB_PASSWORD` secret files. PostgreSQL readiness is
checked with `pg_isready`; `start.sh` explicitly starts and waits for database,
`tantor-server`, Artifact Repository, and UI in that order using `up --no-deps`.
It does not rely on the Compose provider enforcing `depends_on` health
conditions. The required provider contract is `podman-compose` support for
`config`, `up --no-deps`, and `ps --quiet`; the script prints the provider
version and fails immediately if those commands are unavailable.

`tantor-server` remains the only Flyway migration owner. Artifact readiness
uses its configured database connection to verify both `public.kf_artifact` and
the successful V67 Flyway history entry, the latest migration defining the
current Artifact Repository table contract. The check is read-only. Initial
connection wait is bounded to 60 seconds after a five-second connection attempt,
and the restart policy is capped at five failures, so invalid settings do not
create an endless silent retry loop.

Before packaging a release, run the repository's clean-environment check on a
Podman build host:

```bash
bash scripts/test-h01-deployment.sh
```

`manifest.lock.json` records the source revision and immutable image references
used by the release. Production updates must generate a new bundle; do not edit
the locked image references on the target host. Per-image Trivy reports and
SPDX SBOMs are included in `security-scans` and `sbom` for release review.

Example non-secret release configuration:

```powershell
.\package-deployment.ps1 `
  -Version 1.0.0 `
  -PublicOrigin https://tantor.corp.internal `
  -OidcIssuerUri https://identity.corp.internal/realms/tantor `
  -OidcAudience tantor-ui `
  -CorsAllowedOrigins https://tantor.corp.internal `
  -KeycloakUrl https://identity.corp.internal `
  -KeycloakRealm tantor `
  -KafkaSecurityMode SSL `
  -MonitoringMode direct `
  -PrometheusUrl http://monitoring:9090
```

The issuer must exactly equal `KeycloakUrl + /realms/ + KeycloakRealm`; a
mismatch fails packaging before any image is built. These values are public
configuration, not credentials. Client secrets remain outside the bundle.
