-- Drop constraints and columns related to role_id
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_id_fkey;
ALTER TABLE users DROP COLUMN IF EXISTS role_id;

-- Add string role column
ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(20) DEFAULT 'monitor';

-- Drop role related tables
DROP TABLE IF EXISTS role_permissions CASCADE;
DROP TABLE IF EXISTS permissions CASCADE;
DROP TABLE IF EXISTS roles CASCADE;

-- Add new auth_source and ldap_dn columns
ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_source VARCHAR(20) DEFAULT 'local';
ALTER TABLE users ADD COLUMN IF NOT EXISTS ldap_dn VARCHAR(500);

-- Make sure there is a default admin user
INSERT INTO users (id, username, password_hash, role, auth_source, is_active) 
VALUES ('00000000-0000-0000-0000-000000000000', 'admin', '$2a$10$7Z2P.M8h.rZ2/t4H4y.4K.nS6q.rZ2/t4H4y.4K.nS6q.rZ2/t4H', 'admin', 'local', true)
ON CONFLICT (username) DO NOTHING;
