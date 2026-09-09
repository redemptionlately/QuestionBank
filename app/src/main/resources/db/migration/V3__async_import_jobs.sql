CREATE TABLE import_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    source_name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    error VARCHAR(500),
    attempt INT NOT NULL DEFAULT 0,
    entity_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_import_job_owner FOREIGN KEY (owner_id) REFERENCES user_account(id),
    CONSTRAINT idx_import_job_owner_created UNIQUE (owner_id, id)
);
CREATE INDEX idx_import_job_owner_status ON import_job (owner_id, status, updated_at);
