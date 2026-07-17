-- Drop userId from AuditLogs
ALTER TABLE kf_audit_logs DROP COLUMN IF EXISTS user_id;

-- Drop ipAddresses and agentStatus from Hosts
ALTER TABLE kf_hosts DROP COLUMN IF EXISTS ip_addresses;
ALTER TABLE kf_hosts DROP COLUMN IF EXISTS agent_status;

-- Drop failedReason from Tasks
ALTER TABLE kf_tasks DROP COLUMN IF EXISTS failed_reason;
