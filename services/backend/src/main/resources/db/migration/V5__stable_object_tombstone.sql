ALTER TABLE pf_object_intent
  ADD COLUMN removed_at TIMESTAMP(3) NULL,
  ADD INDEX idx_object_intent_removed (state, removed_at);

UPDATE pf_object_intent SET removed_at=updated_at WHERE state='REMOVED';
