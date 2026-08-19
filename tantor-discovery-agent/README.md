# Tantor Discovery Agent

The discovery agent is for Kafka clusters that were not provisioned by Tantor.
It scans the VM for Kafka `*.properties` files, detects the running broker,
reports metadata to Tantor Server, streams basic host/JMX metrics, and polls for
agent-managed tasks such as restart or config persistence.

## Build

From the repository root:

```powershell
cd tantor-discovery-agent
..\go\bin\go.exe build -o tantor-discovery-agent-linux .
```

## Configure

Copy `configs/discovery.yaml` and set:

```yaml
discovery:
  server_url: "http://<tantor-server-ip>:8443"
  scan_paths:
    - "/srv/apps"
    - "/data/apps"
    - "/opt"
  interval: "15s"
  command_timeout: "30s"
  http:
    connect_timeout: "3s"
    tls_handshake_timeout: "5s"
    response_header_timeout: "5s"
    request_timeout: "10s"
    retry_total_timeout: "25s"
    retry_max_attempts: 3
    retry_initial_backoff: "250ms"
    retry_max_backoff: "2s"
    circuit_failure_threshold: 5
    circuit_open_duration: "30s"
  node_name: ""
  restart_command: "systemctl restart kafka"
```

## Run On A Kafka VM

```bash
mkdir -p /srv/apps
scp tantor-discovery-agent-linux root@<vm-ip>:/srv/apps/tantor-discovery-agent
scp configs/discovery.yaml root@<vm-ip>:/srv/apps/discovery.yaml

ssh root@<vm-ip>
cd /srv/apps
chmod +x tantor-discovery-agent
nohup ./tantor-discovery-agent -config ./discovery.yaml > discovery-agent.log 2>&1 &
tail -f discovery-agent.log
```

The discovered cluster appears in Tantor UI under **External Clusters**.

## Persistent service logs

Use `install-discovery-agent.sh` or `deploy-discovery-agent.ps1` for a managed
installation. Both installers write stdout and stderr to:

```text
/var/log/tantor/discovery-agent/tantor-discovery-agent.log
```

This directory is persistent across reboot. The installers also create
`/etc/logrotate.d/tantor-discovery-agent` with daily rotation, a 50 MB size
limit, compression, and 14 retained rotations. The location can be changed
with `--log-dir` on Linux or `-LogDir` in PowerShell.

Follow the persistent log with:

```bash
sudo tail -F /var/log/tantor/discovery-agent/tantor-discovery-agent.log
```

## Network and shutdown behavior

All backend and local metrics requests use explicit connection, TLS, response,
and overall request deadlines. Transient heartbeat, registration, completion,
and metrics failures are retried with bounded exponential backoff and jitter.
The task-poll GET is deliberately not retried because the current server marks
the returned task `IN_PROGRESS`; retrying that request could conceal a task
whose response was lost.

The backend and local metrics endpoint have independent circuit breakers. On
`SIGINT` or `SIGTERM`, active HTTP requests, polling timers, scans, metrics
workers, and OS commands are cancelled before the process exits. The supplied
systemd units allow 40 seconds for this shutdown path.
