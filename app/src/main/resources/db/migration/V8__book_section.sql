-- V8: 背诵型电子书的知识点节（标题 + 答案正文，按 sort_no 排序展开背诵）。
-- 说明：该 DDL 曾被误追加进已应用的 V7，导致校验和漂移 + 与并行工作流的 V7 撞版本号；
-- 现独立成 V8，避免再次踩 Flyway 版本冲突。

CREATE TABLE book_section (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  book_id     BIGINT       NOT NULL,
  sort_no     INT          NOT NULL DEFAULT 0,
  title       VARCHAR(500) NOT NULL,
  body        LONGTEXT     NULL,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_section_book (book_id)
);
