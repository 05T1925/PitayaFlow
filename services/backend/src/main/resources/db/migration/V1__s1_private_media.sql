CREATE TABLE pf_user (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(32) NOT NULL UNIQUE,
  password_hash VARCHAR(160) NOT NULL,
  role VARCHAR(16) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE pf_media (
  id CHAR(36) NOT NULL PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  object_key VARCHAR(128) NOT NULL UNIQUE,
  filename VARCHAR(255) NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  size_bytes BIGINT NOT NULL,
  content_version BIGINT NOT NULL DEFAULT 1,
  kind VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_media_owner FOREIGN KEY (owner_id) REFERENCES pf_user(id),
  INDEX idx_media_owner (owner_id, status)
);
CREATE TABLE pf_object_intent (
  object_key VARCHAR(128) NOT NULL PRIMARY KEY,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE pf_job (
  id CHAR(36) NOT NULL PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  media_id CHAR(36) NOT NULL,
  media_version BIGINT NOT NULL,
  operation VARCHAR(16) NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  request_digest CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  epoch BIGINT NOT NULL DEFAULT 0,
  lease_until TIMESTAMP(3) NULL,
  deadline_at TIMESTAMP(3) NOT NULL,
  result_json TEXT NULL,
  failure_code VARCHAR(40) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_job_owner FOREIGN KEY (owner_id) REFERENCES pf_user(id),
  UNIQUE KEY uk_job_idempotency (owner_id, operation, media_id, media_version, idempotency_key),
  INDEX idx_job_claim (status, lease_until)
);
CREATE TABLE pf_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  job_id CHAR(36) NOT NULL UNIQUE,
  delivered_at TIMESTAMP(3) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
