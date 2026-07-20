public class InjectCluster {
    public static void main(String[] args) throws Exception {
        java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:postgresql://localhost:5432/tantor", "postgres", "postgres");
        java.sql.Statement s = c.createStatement();
        
        // Disable the node_ids refresh trigger which is bugged on clusters table
        s.execute("ALTER TABLE kf_cluster_services DISABLE TRIGGER trg_refresh_cluster_node_ids");
        try {
            // 1. Delete existing mock data to start fresh
            s.execute("DELETE FROM kf_tasks WHERE cluster_id = 'a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d'");
            s.execute("DELETE FROM kf_cluster_services WHERE cluster_id = 'a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d'");
            s.execute("DELETE FROM kf_hosts WHERE id = '123e4567-e89b-12d3-a456-426614174008'");
            s.execute("DELETE FROM kf_clusters WHERE id = 'a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d'");

            // 2. Insert mock cluster (with kafka_cluster_id set to skip offline broker queries)
            s.execute("INSERT INTO kf_clusters (" +
                      "  id, cluster_name, origin_type, kafka_cluster_id, node_ids, created_by, updated_by, " +
                      "  kafka_version, status, environment, mode" +
                      ") VALUES (" +
                      "  'a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d', 'kabir_test', 'INTERNAL', 'khh-76877-jggjk', '[]'::jsonb, " +
                      "  'admin', 'admin', '4.0.1', 'SUCCESS', 'DEV', 'kraft'" +
                      ")");

            // 3. Insert mock host
            s.execute("INSERT INTO kf_hosts (" +
                      "  id, hostname, status, host_ip, disk_total_gb, disk_used_gb" +
                      ") VALUES (" +
                      "  '123e4567-e89b-12d3-a456-426614174008', " +
                      "  'test-host-1', " +
                      "  'ONLINE', " +
                      "  '192.168.3.191', " +
                      "  16, " +
                      "  11" +
                      ")");

            // 4. Insert mock service assignment linking cluster and host
            s.execute("INSERT INTO kf_cluster_services (" +
                      "  id, cluster_id, host_id, role, node_id" +
                      ") VALUES (" +
                      "  '777e4567-e89b-12d3-a456-426614174008', " +
                      "  'a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d', " +
                      "  '123e4567-e89b-12d3-a456-426614174008', " +
                      "  'broker', " +
                      "  1" +
                      ")");
        } finally {
            s.execute("ALTER TABLE kf_cluster_services ENABLE TRIGGER trg_refresh_cluster_node_ids");
        }

        // 5. Insert mock task for Deployment Logs page preview
        s.execute("INSERT INTO kf_tasks (" +
                  "  id, host_id, cluster_id, command, status, log_output, current_step" +
                  ") VALUES (" +
                  "  '123e4567-e89b-12d3-a456-426614174008', " + // Task ID
                  "  '123e4567-e89b-12d3-a456-426614174008', " + // Host ID
                  "  'a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d', " + // Cluster ID
                  "  'UPDATE_KAFKA_CONFIG', " +
                  "  'SUCCESS', " +
                  "  'Existing config backed up to /opt/data/kafka/config/.tantor-backups/server.properties/v3-20260711T063746.503325575Z.bak\n\nConfigs updated successfully\n\nKafka service kafka restarted', " +
                  "  'Validate cluster health'" +
                  ")");

        System.out.println("Mock cluster (with Kafka Cluster ID set), host, service link and deployment logs task injected successfully!");
        c.close();
    }
}
