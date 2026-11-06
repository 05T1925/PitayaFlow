ALTER TABLE pf_media ADD COLUMN duration_ms BIGINT NULL;

CREATE TABLE pf_transcript_source (
  id CHAR(36) NOT NULL PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  media_id CHAR(36) NOT NULL,
  media_version BIGINT NOT NULL,
  current_revision BIGINT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_transcript_media (media_id,media_version),
  CONSTRAINT fk_transcript_media FOREIGN KEY (media_id) REFERENCES pf_media(id),
  INDEX idx_transcript_owner (owner_id,media_id)
);

CREATE TABLE pf_transcript_revision (
  source_id CHAR(36) NOT NULL,
  revision BIGINT NOT NULL,
  origin VARCHAR(16) NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  speaker_revision BIGINT NOT NULL,
  provider VARCHAR(40) NOT NULL,
  model VARCHAR(80) NOT NULL,
  parameters_json VARCHAR(1000) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (source_id,revision),
  CONSTRAINT fk_transcript_revision_source FOREIGN KEY (source_id) REFERENCES pf_transcript_source(id)
);

CREATE TABLE pf_transcript_segment (
  source_id CHAR(36) NOT NULL,
  revision BIGINT NOT NULL,
  segment_id VARCHAR(64) NOT NULL,
  start_ms BIGINT NOT NULL,
  end_ms BIGINT NOT NULL,
  text_content TEXT NOT NULL,
  speaker_id VARCHAR(64) NOT NULL,
  role_name VARCHAR(16) NOT NULL,
  track_index INT NOT NULL,
  confidence DOUBLE NULL,
  PRIMARY KEY (source_id,revision,segment_id),
  CONSTRAINT fk_transcript_segment_revision FOREIGN KEY (source_id,revision) REFERENCES pf_transcript_revision(source_id,revision)
);

CREATE TABLE pf_transcript_machine (
  source_id CHAR(36) NOT NULL PRIMARY KEY,
  provider_response MEDIUMTEXT NOT NULL,
  CONSTRAINT fk_transcript_machine_source FOREIGN KEY (source_id) REFERENCES pf_transcript_source(id)
);

CREATE TABLE pf_transcribe_input (
  job_id CHAR(36) NOT NULL PRIMARY KEY,
  config_version CHAR(64) NOT NULL,
  CONSTRAINT fk_transcribe_job FOREIGN KEY (job_id) REFERENCES pf_job(id)
);
