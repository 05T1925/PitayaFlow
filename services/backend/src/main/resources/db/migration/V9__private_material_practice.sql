CREATE TABLE pf_material (
  id CHAR(36) NOT NULL PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  name VARCHAR(180) NOT NULL,
  kind VARCHAR(12) NOT NULL,
  status VARCHAR(12) NOT NULL,
  current_revision BIGINT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_material_owner (owner_id,status)
);
CREATE TABLE pf_material_revision (
  material_id CHAR(36) NOT NULL,
  revision BIGINT NOT NULL,
  source_name VARCHAR(180) NOT NULL,
  original_sha256 CHAR(64) NOT NULL,
  extracted_sha256 CHAR(64) NOT NULL,
  original_bytes MEDIUMBLOB NULL,
  extracted_text MEDIUMTEXT NULL,
  extraction_error VARCHAR(80) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (material_id,revision),
  CONSTRAINT fk_material_revision FOREIGN KEY (material_id) REFERENCES pf_material(id)
);
CREATE TABLE pf_review_selection (
  media_id CHAR(36) NOT NULL PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  revision BIGINT NOT NULL,
  materials_json MEDIUMTEXT NOT NULL,
  CONSTRAINT fk_selection_media FOREIGN KEY (media_id) REFERENCES pf_media(id)
);
CREATE TABLE pf_practice_answer (
  media_id CHAR(36) NOT NULL,
  question_id VARCHAR(64) NOT NULL,
  owner_id BIGINT NOT NULL,
  current_revision BIGINT NOT NULL,
  source_revision BIGINT NOT NULL,
  outline_revision BIGINT NOT NULL,
  PRIMARY KEY (media_id,question_id),
  CONSTRAINT fk_practice_media FOREIGN KEY (media_id) REFERENCES pf_media(id)
);
CREATE TABLE pf_practice_answer_revision (
  media_id CHAR(36) NOT NULL,
  question_id VARCHAR(64) NOT NULL,
  revision BIGINT NOT NULL,
  text_sha256 CHAR(64) NOT NULL,
  text_content TEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (media_id,question_id,revision),
  CONSTRAINT fk_practice_revision FOREIGN KEY (media_id,question_id) REFERENCES pf_practice_answer(media_id,question_id)
);
ALTER TABLE pf_review_input ADD COLUMN selection_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE pf_review_input ADD COLUMN practice_json MEDIUMTEXT NULL;
UPDATE pf_review_input SET practice_json='[]' WHERE practice_json IS NULL;
ALTER TABLE pf_review_input MODIFY COLUMN practice_json MEDIUMTEXT NOT NULL;
