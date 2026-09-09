-- 清理 seed_papers.sql 灌入的压测数据。
SET @bank := (SELECT id FROM question_bank WHERE name = 'LOADTEST-BANK');

DELETE FROM wrong_question WHERE question_version_id IN
  (SELECT id FROM question_version WHERE paper_version_id IN (SELECT id FROM paper_version WHERE bank_id = @bank));
DELETE FROM submission_item WHERE session_id IN
  (SELECT id FROM practice_session WHERE paper_version_id IN (SELECT id FROM paper_version WHERE bank_id = @bank));
DELETE FROM practice_session WHERE paper_version_id IN (SELECT id FROM paper_version WHERE bank_id = @bank);
DELETE FROM question_version WHERE paper_version_id IN (SELECT id FROM paper_version WHERE bank_id = @bank);
DELETE FROM paper_version WHERE bank_id = @bank;
DELETE FROM question_bank WHERE id = @bank;

SELECT (SELECT COUNT(*) FROM paper_version) AS remaining_papers;
