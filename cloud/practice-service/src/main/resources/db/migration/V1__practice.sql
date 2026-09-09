-- practice-service 拥有的全部表（qb_practice）。bank-service 的表一概不在这里出现，
-- 这就是"数据所有权"的物理证据：SHOW TABLES 两个库，表集合不相交。

CREATE TABLE IF NOT EXISTS practice_session (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    paper_id     BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    score        INT          NOT NULL DEFAULT 0,
    total_count  INT          NOT NULL,
    client_token VARCHAR(120) NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    -- 建会话幂等：同一个 client_token 只可能落一行，重试只会查回同一会话
    UNIQUE KEY uk_practice_session_token (client_token),
    KEY idx_practice_session_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS practice_item (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    session_id     BIGINT       NOT NULL,
    question_id    BIGINT       NOT NULL,
    ordinal        INT          NOT NULL,
    correct_answer VARCHAR(200) NOT NULL,
    student_answer VARCHAR(200) NULL,
    correct        TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_practice_item_session (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
