# Tantor Kafka Platform

Tantor Kafka is a Kafka operations platform with:

- `tantor-server`: Spring Boot management server on port `8443`
- `tantor-artifact-repository`: Spring Boot artifact repository on port `8081`
- `tantor-ui`: React/Vite frontend
- `tantor-agent`: Go agent installed on Kafka hosts
- `tantor-discovery-agent`: Go agent for external Kafka cluster discovery

The server uses Flyway migrations to create and update the PostgreSQL schema.

## Prerequisites

Install these before running the project:

- Java 21
- PostgreSQL 13 or newer
- Node.js 20 or newer
- npm
- PowerShell
- Go, only if you need to rebuild agents

On Linux/VM deployment hosts, the Kafka agent expects:

- Java 17 or Java 21
- systemd
- network access from agent host to Tantor server and artifact repository
- enough disk space under the selected Kafka install/data directories

## 1. Clone The Repository

```powershell
git clone https://github.com/divyatranslab/Tantor_kafka.git
cd Tantor_kafka
```

## 2. Create PostgreSQL Database

Create one database named `tantor`.

Using `psql`:

```sql
CREATE DATABASE tantor;
```

Or from terminal:

```powershell
psql -U postgres -c "CREATE DATABASE tantor;"
```

The tables are created automatically by Flyway when `tantor-server` starts.

## 3. Create `.env`

Copy the example file:

```powershell
Copy-Item .env.example .env
```

Then edit `.env` for your machine.

Minimum local development values:

```properties
TANTOR_DB_URL=jdbc:postgresql://localhost:5432/tantor
TANTOR_DB_USER=postgres
TANTOR_DB_PASSWORD=CHANGE_ME_MINIMUM_12_CHARACTERS
TANTOR_ENCRYPTION_KEY=CHANGE_ME_MINIMUM_32_CHARACTERS
TANTOR_ENCRYPTION_SALT=CHANGE_ME_UNIQUE_MINIMUM_16_CHARACTERS
TANTOR_PROXY_SECRET=CHANGE_ME_MINIMUM_32_CHARACTERS
TANTOR_SSL_KEYSTORE_PASSWORD=CHANGE_ME_MINIMUM_12_CHARACTERS
TANTOR_GRAFANA_PASSWORD=CHANGE_ME_MINIMUM_16_CHARACTERS
TANTOR_REPO_URL=http://localhost:8081
TANTOR_REPO_PATH=./.runtime/repository
TANTOR_MONITORING_MODE=direct
TANTOR_PROMETHEUS_URL=http://127.0.0.1:9090
TANTOR_MONITORING_EXPORTER_HOST=127.0.0.1
```

Existing installations that encrypted data with the previous key handling may
temporarily set `TANTOR_ENCRYPTION_LEGACY_KEY` to the old key while credentials
are rewritten. New ciphertext is prefixed with `v2:` and uses PBKDF2-derived
AES-256 keys. Remove the legacy key after all unversioned values are migrated.

For a VM/server deployment, set `TANTOR_REPO_URL` and `TANTOR_MONITORING_EXPORTER_HOST` to the Tantor server IP, for example:

```properties
TANTOR_REPO_URL=http://192.168.3.191:8081
TANTOR_MONITORING_EXPORTER_HOST=192.168.3.191
```

Do not commit real passwords or production secrets in `.env`.

## 4. Build Backend And Agents

From the repository root:

```powershell
.\build.ps1
```

This builds:

- `tantor-artifact-repository/target/tantor-artifact-repository-1.0.0.jar`
- `tantor-server/target/tantor-server-1.0.0.jar`
- `tantor-agent/tantor-agent-linux`, when Go is available
- `tantor-discovery-agent/tantor-discovery-agent-linux`, when Go is available

## 5. Start Backend Locally

For foreground logs:

```powershell
.\start-backend-dev.ps1
```

For background services:

```powershell
.\start-backend.ps1 -Restart
```

Stop background services:

```powershell
.\stop-backend.ps1
```

Expected backend URLs:

- Management server: `http://localhost:8443`
- Artifact repository: `http://localhost:8081`

Health checks:

```powershell
curl http://localhost:8443/api/v1/monitoring/health
curl http://localhost:8081/actuator/health
```

## 6. Start Frontend

```powershell
cd tantor-ui
npm install
npm run dev
```

Open the URL printed by Vite, usually:

```text
http://localhost:5173
```

Authentication is disabled by default. Enable Keycloak only when you have a valid Keycloak setup:

```properties
VITE_AUTH_ENABLED=true
VITE_KEYCLOAK_URL=https://your-keycloak-host
VITE_KEYCLOAK_REALM=Gatekeeper
VITE_KEYCLOAK_CLIENT_ID=apb-kafka
```

## 7. First Data Setup In UI

After backend and UI are running:

1. Open the UI.
2. Upload a Kafka `.tgz` binary in Artifacts.
3. Upload the JMX exporter `.jar` in Artifacts using service type `JMX Exporter`.
4. Register or start a Tantor agent on the target Kafka host.
5. Deploy a cluster from the UI.

The JMX exporter artifact is auto-selected from `kf_artifact` where:

```text
service_type = JMX_EXPORTER
status = AVAILABLE
```

You do not need to manually put the JMX artifact ID in `.env` unless you want to force a specific artifact.

## 8. Monitoring Setup

Current monitoring flow:

1. Kafka is deployed by Tantor agent.
2. The agent attaches JMX exporter to Kafka on port `7071`.
3. Tantor server starts one `kafka_exporter` systemd service per internal cluster on the Tantor server host.
4. Tantor server exposes scrape targets at:

```text
/internal/prometheus/targets
```

5. Prometheus scrapes those targets.
6. UI calls Tantor monitoring APIs.
7. Tantor server queries Prometheus and returns metrics to the UI.

Required monitoring components:

- `kafka_exporter` installed on the Tantor server host at `/usr/local/bin/kafka_exporter`
- Prometheus running and configured to scrape Tantor service discovery endpoint
- Target Kafka hosts must allow access to JMX exporter port `7071`

Example Prometheus scrape config:

```yaml
scrape_configs:
  - job_name: tantor-sd
    http_sd_configs:
      - url: http://127.0.0.1:8443/internal/prometheus/targets
        refresh_interval: 15s
```

For a local demo Prometheus on the Tantor server host:

```properties
TANTOR_MONITORING_MODE=direct
TANTOR_PROMETHEUS_URL=http://127.0.0.1:9090
```

## 9. VM Deployment Notes

Typical backend deploy on a Linux VM:

```bash
cd /opt/Tantor_kafka
git pull

cd /opt/Tantor_kafka/tantor-server
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$PATH
mvn clean package -DskipTests
sudo systemctl restart tantor-server
sudo systemctl status tantor-server --no-pager
```

Typical UI deploy:

```bash
cd /opt/Tantor_kafka/tantor-ui
npm install
npm run build
sudo rm -rf /usr/share/nginx/html/*
sudo cp -r /opt/Tantor_kafka/tantor-ui/dist/* /usr/share/nginx/html/
sudo systemctl reload nginx
```

Typical artifact repository deploy:

```bash
cd /opt/Tantor_kafka/tantor-artifact-repository
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$PATH
mvn clean package -DskipTests
sudo systemctl restart tantor-artifact
sudo systemctl status tantor-artifact --no-pager
```

## Troubleshooting

### `JAR not found`

Run:

```powershell
.\build.ps1
```

Then start backend again.

### UI returns `502 Bad Gateway`

The backend is not running or Nginx cannot reach it. Check:

```bash
sudo systemctl status tantor-server --no-pager
sudo journalctl -u tantor-server -n 100 --no-pager
```

### Artifact upload fails

Check repository path permissions:

```bash
sudo mkdir -p /var/lib/tantor/repository/artifacts
sudo chown -R root:root /var/lib/tantor/repository
sudo chmod -R 775 /var/lib/tantor/repository
sudo systemctl restart tantor-artifact
```

Also verify:

```bash
curl -i http://127.0.0.1:8081/api/v1/artifacts
```

### Monitoring shows `kafka_exporter required`

Check kafka_exporter and Prometheus:

```bash
systemctl status tantor-kafka-exporter-<cluster-id> --no-pager
curl http://127.0.0.1:<exporter-port>/metrics | head
curl http://127.0.0.1:9090/api/v1/targets
```

### Monitoring shows `JMX required`

On the Kafka node:

```bash
ps -ef | grep jmx_prometheus_javaagent | grep -v grep
curl http://127.0.0.1:7071/metrics | head
```

From the Tantor server:

```bash
curl http://<kafka-node-ip>:7071/metrics | head
```

### Database starts empty

That is normal for a fresh clone. Start `tantor-server`; Flyway will create the schema. Then upload artifacts and register hosts from the UI.
