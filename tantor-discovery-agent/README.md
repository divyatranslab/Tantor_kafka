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
