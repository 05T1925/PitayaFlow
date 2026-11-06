CREATE TABLE pf_synthetic_source (
  id CHAR(36) NOT NULL PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  media_id CHAR(36) NOT NULL,
  current_revision BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_source_owner FOREIGN KEY (owner_id) REFERENCES pf_user(id),
  CONSTRAINT fk_source_media FOREIGN KEY (media_id) REFERENCES pf_media(id),
  INDEX idx_source_media (owner_id, media_id)
);
CREATE TABLE pf_synthetic_revision (
  source_id CHAR(36) NOT NULL,
  revision BIGINT NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  content_json TEXT NOT NULL,
  speaker_revision BIGINT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (source_id, revision),
  CONSTRAINT fk_revision_source FOREIGN KEY (source_id) REFERENCES pf_synthetic_source(id)
);
CREATE TABLE pf_agent_input (
  job_id CHAR(36) NOT NULL PRIMARY KEY,
  source_id CHAR(36) NOT NULL,
  source_revision BIGINT NOT NULL,
  source_sha256 CHAR(64) NOT NULL,
  speaker_revision BIGINT NOT NULL,
  rule_version VARCHAR(32) NOT NULL,
  tool_version VARCHAR(32) NOT NULL,
  CONSTRAINT fk_agent_input_job FOREIGN KEY (job_id) REFERENCES pf_job(id),
  CONSTRAINT fk_agent_input_revision FOREIGN KEY (source_id, source_revision) REFERENCES pf_synthetic_revision(source_id, revision)
);
CREATE TABLE pf_agent_grant (
  attempt_id CHAR(36) NOT NULL PRIMARY KEY,
  job_id CHAR(36) NOT NULL,
  epoch BIGINT NOT NULL,
  claim_token CHAR(36) NOT NULL,
  token_sha256 CHAR(64) NOT NULL UNIQUE,
  run_id CHAR(36) NOT NULL,
  expires_at TIMESTAMP(3) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_grant_job FOREIGN KEY (job_id) REFERENCES pf_job(id),
  INDEX idx_grant_job (job_id, state)
);
CREATE TABLE pf_agent_result (
  job_id CHAR(36) NOT NULL PRIMARY KEY,
  attempt_id CHAR(36) NOT NULL UNIQUE,
  run_id CHAR(36) NOT NULL,
  epoch BIGINT NOT NULL,
  source_id CHAR(36) NOT NULL,
  source_revision BIGINT NOT NULL,
  source_sha256 CHAR(64) NOT NULL,
  speaker_revision BIGINT NOT NULL,
  result_json TEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_agent_result_job FOREIGN KEY (job_id) REFERENCES pf_job(id),
  CONSTRAINT fk_agent_result_revision FOREIGN KEY (source_id, source_revision) REFERENCES pf_synthetic_revision(source_id, revision)
);
