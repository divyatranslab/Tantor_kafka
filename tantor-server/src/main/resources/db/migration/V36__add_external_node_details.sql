ALTER TABLE kf_external_cluster_nodes ADD COLUMN IF NOT EXISTS port INTEGER;
ALTER TABLE kf_external_cluster_nodes ADD COLUMN IF NOT EXISTS install_dir VARCHAR(512);
ALTER TABLE kf_external_cluster_nodes ADD COLUMN IF NOT EXISTS config_file VARCHAR(512);
ALTER TABLE kf_external_cluster_nodes ADD COLUMN IF NOT EXISTS data_dirs TEXT;
ALTER TABLE kf_external_cluster_nodes ADD COLUMN IF NOT EXISTS log_dirs TEXT;
