-- Index for optimizing FIFO job-claiming queries filtering by status and ordering by created_at
CREATE INDEX IF NOT EXISTS idx_kf_jobs_status_created_id
    ON kf_jobs (status, created_at, id);
