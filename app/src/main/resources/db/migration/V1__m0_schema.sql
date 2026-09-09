CREATE TABLE user_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_account_username UNIQUE (username)
);

CREATE TABLE question_bank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_question_bank_owner FOREIGN KEY (owner_id) REFERENCES user_account(id)
);

CREATE TABLE paper_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bank_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL,
    CONSTRAINT uk_paper_version_no UNIQUE (bank_id, version_no),
    CONSTRAINT fk_paper_version_bank FOREIGN KEY (bank_id) REFERENCES question_bank(id),
    CONSTRAINT fk_paper_version_creator FOREIGN KEY (created_by) REFERENCES user_account(id)
);

CREATE TABLE question_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_version_id BIGINT NOT NULL,
    question_no INT NOT NULL,
    prompt TEXT NOT NULL,
    question_type VARCHAR(20) NOT NULL,
    options_json TEXT NOT NULL,
    answer_json TEXT NOT NULL,
    score INT NOT NULL,
    explanation TEXT,
    CONSTRAINT uk_question_version_no UNIQUE (paper_version_id, question_no),
    CONSTRAINT fk_question_version_paper FOREIGN KEY (paper_version_id) REFERENCES paper_version(id)
);

CREATE TABLE practice_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    paper_version_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    total_score INT NULL,
    submission_key VARCHAR(100) NULL,
    submission_result_json TEXT NULL,
    entity_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMP NULL,
    CONSTRAINT fk_practice_student FOREIGN KEY (student_id) REFERENCES user_account(id),
    CONSTRAINT fk_practice_paper FOREIGN KEY (paper_version_id) REFERENCES paper_version(id)
);

CREATE TABLE submission_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    question_version_id BIGINT NOT NULL,
    answer_json TEXT NOT NULL,
    score INT NOT NULL DEFAULT 0,
    correct BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_submission_question UNIQUE (session_id, question_version_id),
    CONSTRAINT fk_submission_session FOREIGN KEY (session_id) REFERENCES practice_session(id),
    CONSTRAINT fk_submission_question FOREIGN KEY (question_version_id) REFERENCES question_version(id)
);

CREATE TABLE wrong_question (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    question_version_id BIGINT NOT NULL,
    wrong_count INT NOT NULL DEFAULT 1,
    last_wrong_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wrong_question UNIQUE (student_id, question_version_id),
    CONSTRAINT fk_wrong_student FOREIGN KEY (student_id) REFERENCES user_account(id),
    CONSTRAINT fk_wrong_question FOREIGN KEY (question_version_id) REFERENCES question_version(id)
);
