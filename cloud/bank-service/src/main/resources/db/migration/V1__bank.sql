-- bank-service 拥有的全部表（qb_bank）。practice-service 不得读写本库。

CREATE TABLE IF NOT EXISTS question_bank (
    id       BIGINT       NOT NULL AUTO_INCREMENT,
    name     VARCHAR(120) NOT NULL,
    owner_id BIGINT       NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS question_draft (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    bank_id      BIGINT       NOT NULL,
    prompt       VARCHAR(500) NOT NULL,
    type         VARCHAR(20)  NOT NULL,
    options_json VARCHAR(500) NOT NULL,
    answer       VARCHAR(200) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_question_draft_bank (bank_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS paper_version (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    bank_id      BIGINT      NOT NULL,
    version      INT         NOT NULL,
    published_at DATETIME(3) NOT NULL,
    item_count   INT         NOT NULL,
    PRIMARY KEY (id),
    -- 并发发布的兜底：两个请求算出的下一版号相同时，只有一个能落库，另一个拿 409
    UNIQUE KEY uk_paper_version_bank_version (bank_id, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS paper_item (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    paper_version_id BIGINT       NOT NULL,
    question_id      BIGINT       NOT NULL,
    prompt           VARCHAR(500) NOT NULL,
    type             VARCHAR(20)  NOT NULL,
    options_json     VARCHAR(500) NOT NULL,
    answer           VARCHAR(200) NOT NULL,
    ordinal          INT          NOT NULL,
    PRIMARY KEY (id),
    KEY idx_paper_item_version (paper_version_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS outbox_event (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    event_key  VARCHAR(120)  NOT NULL,
    event_type VARCHAR(60)   NOT NULL,
    payload    VARCHAR(2000) NOT NULL,
    created_at DATETIME(3)   NOT NULL,
    sent       TINYINT(1)    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 消费端幂等去重依赖这个键；同时也防止重复发布写出两条同 key 的消息
    UNIQUE KEY uk_outbox_event_key (event_key),
    KEY idx_outbox_pending (sent, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
