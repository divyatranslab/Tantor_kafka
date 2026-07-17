-- Drop userId from AuditLogs
ALTER TABLE kf_audit_logs DROP COLUMN IF EXISTS user_id;

-- kf_hosts.agent_status was folded into kf_hosts.status, but the host audit
-- trigger/function from older migrations still referenced agent_status. Remove
-- that dependency before dropping the column; do not use DROP COLUMN CASCADE,
-- because that would silently remove auditing behavior.
DROP TRIGGER IF EXISTS trg_host_to_global_audit ON kf_hosts;

CREATE OR REPLACE FUNCTION sync_host_to_global_audit()
RETURNS TRIGGER AS $$
DECLARE
    host_action VARCHAR(100);
    host_status VARCHAR(50);
    host_actor VARCHAR(255);
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.status IS NOT DISTINCT FROM NEW.status
       AND OLD.host_ip IS NOT DISTINCT FROM NEW.host_ip
       AND OLD.hostname IS NOT DISTINCT FROM NEW.hostname
       AND OLD.cluster_id IS NOT DISTINCT FROM NEW.cluster_id
       AND OLD.removed IS NOT DISTINCT FROM NEW.removed
       AND OLD.action IS NOT DISTINCT FROM NEW.action THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        host_action := COALESCE(NULLIF(NEW.action, ''), 'HOST_REGISTERED');
    ELSE
        host_action := COALESCE(NULLIF(NEW.action, ''), 'HOST_UPDATED');
    END IF;

    host_status := CASE
        WHEN COALESCE(NEW.removed, FALSE) THEN 'REMOVED'
        ELSE COALESCE(NULLIF(NEW.status, ''), 'UNKNOWN')
    END;
    host_actor := COALESCE(NULLIF(NEW.agent_name, ''), NULLIF(NEW.hostname, ''), 'system');
    IF NULLIF(NEW.host_ip, '') IS NOT NULL AND host_actor NOT LIKE '%(%' THEN
        host_actor := host_actor || ' (' || NEW.host_ip || ')';
    END IF;

    INSERT INTO kf_audit_logs (
        action, resource_type, resource_id, resource, user_name, event_category,
        status, origin, created_time, host_id, host_ip, host_name, cluster_id, created_by, details
    )
    VALUES (
        host_action,
        'HOST',
        NEW.id,
        COALESCE(NULLIF(NEW.hostname, ''), NEW.id),
        host_actor,
        'AGENT',
        host_status,
        'AGENT',
        now(),
        NEW.id,
        NULLIF(NEW.host_ip, ''),
        NULLIF(NEW.hostname, ''),
        NEW.cluster_id,
        host_actor,
        jsonb_build_object(
            'availability', CASE WHEN COALESCE(NEW.removed, FALSE) OR upper(host_status) IN ('OCCUPIED', 'PENDING', 'OFFLINE', 'REMOVED') THEN 'unavailable' ELSE 'available' END,
            'agentName', NEW.agent_name,
            'agentVersion', NEW.agent_version,
            'agentPath', NEW.agent_path,
            'javaVersion', NEW.java_version
        )
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Drop ipAddresses and agentStatus from Hosts
ALTER TABLE kf_hosts DROP COLUMN IF EXISTS ip_addresses;
ALTER TABLE kf_hosts DROP COLUMN IF EXISTS agent_status;

CREATE TRIGGER trg_host_to_global_audit
AFTER INSERT OR UPDATE OF status, host_ip, hostname, cluster_id, removed, action ON kf_hosts
FOR EACH ROW EXECUTE PROCEDURE sync_host_to_global_audit();

-- Drop failedReason from Tasks
ALTER TABLE kf_tasks DROP COLUMN IF EXISTS failed_reason;
