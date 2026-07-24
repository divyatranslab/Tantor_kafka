Tantor External Kafka Discovery Agent v3.2
===========================================

Scope
-----
Install locally on an existing Kafka VM to discover active Kafka broker, KRaft controller, and ZooKeeper roles and report system-level information outbound to the Tantor backend. This bundle is not the internal managed-cluster deployment agent.

Production defaults
-------------------
- Air-gapped: no internet download or online package installation.
- Outbound HTTP only in the current project phase; no inbound agent listener.
- SELinux is preserved.
- Exact client scan roots, environment, and node identity are mandatory.
- Discovery policy defaults to running-only. Offline filesystem inventory is explicitly opt-in and is not connectable.
- Config write and service restart are disabled by default.
- Runtime user should be pre-provisioned; creation requires --create-runtime-user.

Bundle validation
-----------------
  sha256sum -c SHA256SUMS

Generic installation
--------------------
  sudo ./install-agent.sh \
    --server-ip <backend-host> \
    --server-port <backend-port> \
    --run-user <approved-agent-user> \
    --run-group <approved-agent-group> \
    --node-name <approved-node-identity> \
    --environment <prod|dr|uat|dev> \
    --scan-paths <kafka-install-root>,<kafka-data-root> \
    --cluster-name <logical-cluster-name> \
    --discovery-policy running-only \
    --require-server-reachable

Use --service-name <name> when the client requires a custom systemd unit name.

Prechecks
---------
The startup precheck and runtime discovery use the same configured scan roots. A running Kafka JVM is the production source of truth. Under running-only policy, no active cluster is reported when no Kafka server JVM is visible.

Verification
------------
  systemctl status <service-name> --no-pager -l
  journalctl -u <service-name> -n 100 --no-pager
  sudo -u <agent-user> test -r <active-kafka-config> && echo READABLE

Multi-node clusters
-------------------
Install one agent on every VM requiring system-level visibility. The agent reports the local broker/controller node. The backend must aggregate unique node IDs for cluster-wide counts. The payload contains localBrokerCount for explicit semantics while retaining brokerCount for backward compatibility.

Lifecycle
---------
When a previously running node disappears from discovery, the agent reports the same node with isRunning=false. Backend heartbeat TTL/decommission policy must still be configured and tested.

Security inference
------------------
When listener security cannot be proven from Kafka properties, the agent reports UNKNOWN rather than assuming PLAINTEXT.
