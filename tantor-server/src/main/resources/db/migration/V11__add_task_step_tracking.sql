ALTER TABLE tasks
ADD COLUMN current_step VARCHAR(255),
ADD COLUMN failed_reason TEXT,
ADD COLUMN step_logs JSONB;
