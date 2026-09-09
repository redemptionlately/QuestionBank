-- Indexes match the read paths used by the M0 services.
CREATE INDEX idx_paper_version_status_published_at
    ON paper_version (status, published_at);

CREATE INDEX idx_practice_session_student_created_at
    ON practice_session (student_id, created_at);

CREATE INDEX idx_wrong_question_student_last_wrong_at
    ON wrong_question (student_id, last_wrong_at);
