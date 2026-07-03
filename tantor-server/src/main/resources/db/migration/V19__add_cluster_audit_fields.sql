ALTER TABLE clusters ADD COLUMN created_by VARCHAR(255) DEFAULT 'system';
ALTER TABLE clusters ADD COLUMN updated_by VARCHAR(255) DEFAULT 'system';
