# Kafka Prerequisite Check

## UI flow

The existing control-plane flow remains unchanged:

```text
UI: Check Prerequisites
        |
        v
Backend creates task: CHECK_PREREQUISITES
        |
        v
Agent on the selected VM polls the task
        |
        v
Agent runs all six checks locally
        |
        v
Agent reports SUCCESS or FAILED + full log_output
        |
        v
UI displays the host prerequisite result
```

The management server does not SSH into the host for this check. The already-connected agent executes the checks on its own VM.

## Mandatory checks

| Check | Pass condition |
|---|---|
| Open file limit | Soft and hard limits are both at least `1024000` |
| Swappiness | `/proc/sys/vm/swappiness` is exactly `0` |
| Transparent Huge Pages | `/sys/kernel/mm/transparent_hugepage/enabled` contains `[never]` |
| SELinux | `getenforce` reports `Disabled` or `Permissive` |
| Java | `java -version` resolves to `17.x` |
| NTP service | `ntpd` or `chronyd` is active under systemd |

All checks are mandatory. The checker does not stop at the first failure. This gives the UI one complete report per click.

## Example successful task output

```text
===== Kafka System Pre-check =====
Open file limit (soft/hard): 1024000/1024000 [Pass]
Swappiness: 0 [Pass]
Transparent Huge Pages: always madvise [never] [Pass]
SELinux: Permissive [Pass]
Java Version: 17.0.15 [Pass]
NTP Service: chronyd: Active [Pass]
===== Pre-check Completed =====
Summary: 6 passed, 0 failed, 6 total
Kafka prerequisite check passed.
```

## Example failed task output

```text
===== Kafka System Pre-check =====
Open file limit (soft/hard): 1024000/1024000 [Pass]
Swappiness: 10 [Fail, must be 0]
Transparent Huge Pages: always [madvise] never [Fail, enabled policy must be never]
SELinux: Enforcing [Fail, must be Disabled or Permissive]
Java Version: 21.0.7 [Fail, must be 17.x]
NTP Service: Not running [Fail, ntpd or chronyd must be active]
===== Pre-check Completed =====
Summary: 1 passed, 5 failed, 6 total
```

## Important runtime alignment

The generated Kafka and ZooKeeper systemd units set:

```ini
LimitNOFILE=1024000
```

This prevents a host from passing the prerequisite while Kafka itself starts with the older lower service limit.
