INSERT INTO kf_clusters (id, cluster_name, origin_type, node_ids, created_by, updated_by, kafka_version, status, environment, mode, monitoring_enabled)
VALUES (
    '5056ab20-4a87-4d7a-8b3d-114df3c51375',
    'Fake Local Cluster',
    'INTERNAL',
    '[]'::jsonb,
    'admin',
    'admin',
    '3.7.0',
    'ACTIVE',
    'DEV',
    'kraft',
    true
);
