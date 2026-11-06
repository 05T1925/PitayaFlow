CREATE TABLE pf_review_outline (
  source_id CHAR(36) NOT NULL PRIMARY KEY,
  current_revision BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_review_outline_source FOREIGN KEY (source_id) REFERENCES pf_transcript_source(id)
);
CREATE TABLE pf_review_outline_revision (
  source_id CHAR(36) NOT NULL,
  revision BIGINT NOT NULL,
  transcript_revision BIGINT NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  content_json MEDIUMTEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (source_id,revision),
  CONSTRAINT fk_review_outline_rev FOREIGN KEY (source_id) REFERENCES pf_review_outline(source_id),
  CONSTRAINT fk_review_outline_transcript FOREIGN KEY (source_id,transcript_revision) REFERENCES pf_transcript_revision(source_id,revision)
);
CREATE TABLE pf_review_input (
  job_id CHAR(36) NOT NULL PRIMARY KEY,
  source_id CHAR(36) NOT NULL,
  source_revision BIGINT NOT NULL,
  source_sha256 CHAR(64) NOT NULL,
  speaker_revision BIGINT NOT NULL,
  outline_revision BIGINT NOT NULL,
  outline_sha256 CHAR(64) NOT NULL,
  rule_version VARCHAR(64) NOT NULL,
  model_version VARCHAR(64) NOT NULL,
  tool_version VARCHAR(64) NOT NULL,
  materials_json VARCHAR(1000) NOT NULL,
  CONSTRAINT fk_review_input_job FOREIGN KEY (job_id) REFERENCES pf_job(id),
  CONSTRAINT fk_review_input_source FOREIGN KEY (source_id,source_revision) REFERENCES pf_transcript_revision(source_id,revision)
);
CREATE TABLE pf_review_result (
  job_id CHAR(36) NOT NULL PRIMARY KEY,
  attempt_id CHAR(36) NOT NULL UNIQUE,
  run_id CHAR(36) NOT NULL,
  epoch BIGINT NOT NULL,
  source_id CHAR(36) NOT NULL,
  source_revision BIGINT NOT NULL,
  source_sha256 CHAR(64) NOT NULL,
  outline_revision BIGINT NOT NULL,
  outline_sha256 CHAR(64) NOT NULL,
  report_json MEDIUMTEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_review_result_job FOREIGN KEY (job_id) REFERENCES pf_job(id),
  CONSTRAINT fk_review_result_source FOREIGN KEY (source_id,source_revision) REFERENCES pf_transcript_revision(source_id,revision)
);
