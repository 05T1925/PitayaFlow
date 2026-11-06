ALTER TABLE pf_object_intent
  ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'UPLOADING',
  ADD COLUMN claim_token CHAR(36) NULL,
  ADD COLUMN lease_until TIMESTAMP(3) NULL,
  ADD COLUMN updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD INDEX idx_object_intent_reap (state, lease_until);

ALTER TABLE pf_job
  ADD COLUMN claim_token CHAR(36) NULL,
  ADD INDEX idx_job_claim_token (id, claim_token);
