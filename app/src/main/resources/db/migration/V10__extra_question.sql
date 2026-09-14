-- V10: reunion table for non-choice questions stranded in book text.
-- A complete paper was split by ingest into choice-only papers; fill/short/essay
-- items (with answers) stayed inline in book extractedText. This table reunites
-- them as first-class records WITHOUT touching bank.* (extra-only extension).
-- Single CREATE with inline INDEX (H2 MODE=MySQL safe, V8 precedent).
-- Entity ExtraQuestion must byte-match this DDL (ddl-auto: validate).

CREATE TABLE extra_question (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  paper_version_id  BIGINT       NOT NULL,
  question_no       INT          NOT NULL,
  qtype             VARCHAR(16)  NOT NULL,
  prompt            VARCHAR(1000) NOT NULL,
  correct_answers   LONGTEXT     NOT NULL,
  explanation       LONGTEXT     NULL,
  knowledge_point   VARCHAR(500) NULL,
  source_ref        VARCHAR(200) NULL,
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_extraq_paper (paper_version_id)
);
