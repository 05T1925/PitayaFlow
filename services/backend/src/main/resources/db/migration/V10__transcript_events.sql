CREATE TABLE pf_transcript_event (
  job_id CHAR(36) NOT NULL,
  seq BIGINT NOT NULL,
  kind VARCHAR(24) NOT NULL,
  start_ms BIGINT NULL,
  end_ms BIGINT NULL,
  payload_json TEXT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (job_id,seq),
  CONSTRAINT fk_transcript_event_job FOREIGN KEY (job_id) REFERENCES pf_job(id)
);
