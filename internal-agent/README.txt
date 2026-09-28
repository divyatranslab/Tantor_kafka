Tantor Internal Agent
Version: 1.0.0-prod.16-reporting.9

Linux x86_64 executable: bin/tantor-agent-linux-amd64
Complete Go source code: source/
Fix details: source/REPORTING_FIX.md

This patched version is deployed on 192.168.3.191, 192.168.3.229, and 192.168.3.213.
192.168.3.174 was unreachable during this update and remains on reporting.6 until it can be reached.
It is a Linux executable, not a Windows executable.
Runtime configuration on the server: /etc/tantor-agent/agent.yaml
Server endpoint: http://192.168.3.194:8443
Authentication: none

reporting.9: cleanup retains every role installed for one cluster on one host,
accepts the assigned service list in DELETE_CLUSTER tasks for older records,
and removes Kafka application log and custom storage directories recorded by deployments.
