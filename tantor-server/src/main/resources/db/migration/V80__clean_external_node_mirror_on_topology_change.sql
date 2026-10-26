CREATE OR REPLACE FUNCTION sync_external_node_to_node()
RETURNS TRIGGER AS $$
DECLARE
    ec RECORD;
    node_role VARCHAR(100);
    old_role VARCHAR(100);
    old_host_id VARCHAR(255);
    new_host_id VARCHAR(255);
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM kf_nodes
        WHERE cluster_id = OLD.cluster_id
          AND host_id = COALESCE(NULLIF(OLD.host, ''), 'external-' || COALESCE(OLD.node_id::text, OLD.id::text));
        RETURN OLD;
    END IF;

    SELECT * INTO ec FROM kf_external_clusters WHERE id = NEW.cluster_id;
    node_role := CASE
        WHEN COALESCE(NEW.is_broker, FALSE) AND COALESCE(NEW.is_controller, FALSE) THEN 'broker_controller'
        WHEN COALESCE(NEW.is_broker, FALSE) THEN 'broker'
        WHEN COALESCE(NEW.is_controller, FALSE) THEN 'controller'
        ELSE 'external'
    END;
    new_host_id := COALESCE(NULLIF(NEW.host, ''), 'external-' || COALESCE(NEW.node_id::text, NEW.id::text));

    IF TG_OP = 'UPDATE' THEN
        old_role := CASE
            WHEN COALESCE(OLD.is_broker, FALSE) AND COALESCE(OLD.is_controller, FALSE) THEN 'broker_controller'
            WHEN COALESCE(OLD.is_broker, FALSE) THEN 'broker'
            WHEN COALESCE(OLD.is_controller, FALSE) THEN 'controller'
            ELSE 'external'
        END;
        old_host_id := COALESCE(NULLIF(OLD.host, ''), 'external-' || COALESCE(OLD.node_id::text, OLD.id::text));

        IF old_role IS DISTINCT FROM node_role OR old_host_id IS DISTINCT FROM new_host_id THEN
            DELETE FROM kf_nodes
            WHERE cluster_id = OLD.cluster_id
              AND host_id = old_host_id
              AND role = old_role
              AND NOT EXISTS (
                  SELECT 1
                  FROM kf_external_cluster_nodes AS sibling
                  WHERE sibling.cluster_id = OLD.cluster_id
                    AND COALESCE(
                        NULLIF(sibling.host, ''),
                        'external-' || COALESCE(sibling.node_id::text, sibling.id::text)
                    ) = old_host_id
                    AND CASE
                        WHEN COALESCE(sibling.is_broker, FALSE) AND COALESCE(sibling.is_controller, FALSE) THEN 'broker_controller'
                        WHEN COALESCE(sibling.is_broker, FALSE) THEN 'broker'
                        WHEN COALESCE(sibling.is_controller, FALSE) THEN 'controller'
                        ELSE 'external'
                    END = old_role
              );
        END IF;
    END IF;

    INSERT INTO kf_nodes (
        cluster_id, cluster_unique_id, node_id, host_id, host_name, host_ip, role, status,
        mode, env, kafka_version, installation_dir_path, config_path, data_path, log_path,
        service_config_json, created_at, updated_at
    )
    VALUES (
        NEW.cluster_id,
        ec.kafka_cluster_id,
        NEW.node_id,
        new_host_id,
        NEW.host,
        NEW.host,
        node_role,
        CASE WHEN NEW.last_seen IS NULL THEN 'BOOTSTRAP_CONNECTED' ELSE 'ONLINE' END,
        'EXTERNAL',
        ec.environment,
        COALESCE(NULLIF(NULLIF(ec.kafka_version, ''), 'null'), 'Unknown'),
        NEW.install_dir,
        NEW.config_file,
        NEW.data_dirs,
        COALESCE(NEW.log_dirs, ec.log_dirs),
        jsonb_build_object(
            'port', NEW.port,
            'isBroker', NEW.is_broker,
            'isController', NEW.is_controller,
            'memoryUsedMb', NEW.memory_used_mb,
            'memoryTotalMb', NEW.memory_total_mb,
            'diskUsedGb', NEW.disk_used_gb,
            'diskTotalGb', NEW.disk_total_gb,
            'lastSeen', NEW.last_seen
        )::text,
        now(),
        now()
    )
    ON CONFLICT (cluster_id, host_id, role) DO UPDATE SET
        cluster_unique_id = EXCLUDED.cluster_unique_id,
        node_id = EXCLUDED.node_id,
        host_name = EXCLUDED.host_name,
        host_ip = EXCLUDED.host_ip,
        status = EXCLUDED.status,
        mode = EXCLUDED.mode,
        env = EXCLUDED.env,
        kafka_version = EXCLUDED.kafka_version,
        installation_dir_path = EXCLUDED.installation_dir_path,
        config_path = EXCLUDED.config_path,
        data_path = EXCLUDED.data_path,
        log_path = EXCLUDED.log_path,
        service_config_json = EXCLUDED.service_config_json,
        updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Node IDs are Kafka identities and cannot be negative; older agents used
-- negative values as an "unknown" sentinel. NULL is the database representation
-- for an unknown ID and permits multiple unidentified nodes per cluster.
UPDATE kf_external_cluster_nodes
SET node_id = NULL
WHERE node_id < 0;

DELETE FROM kf_nodes AS stale
USING kf_external_cluster_nodes AS current_node
WHERE stale.cluster_id = current_node.cluster_id
  AND stale.host_id = COALESCE(
      NULLIF(current_node.host, ''),
      'external-' || COALESCE(current_node.node_id::text, current_node.id::text)
  )
  AND stale.role = 'external'
  AND (COALESCE(current_node.is_broker, FALSE) OR COALESCE(current_node.is_controller, FALSE))
  AND NOT EXISTS (
      SELECT 1
      FROM kf_external_cluster_nodes AS sibling
      WHERE sibling.cluster_id = current_node.cluster_id
        AND COALESCE(
            NULLIF(sibling.host, ''),
            'external-' || COALESCE(sibling.node_id::text, sibling.id::text)
        ) = stale.host_id
        AND NOT COALESCE(sibling.is_broker, FALSE)
        AND NOT COALESCE(sibling.is_controller, FALSE)
  );
