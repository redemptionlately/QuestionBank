-- auth-service 拥有的全部表。别的服务不得读写本库。
CREATE TABLE IF NOT EXISTS user_account (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
