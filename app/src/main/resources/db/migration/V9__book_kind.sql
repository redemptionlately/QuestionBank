-- V9: book kind for three import flows (FULL_TEXT / RECITE / SCAN).
-- book_section kind for chapter vs recite card (CHAPTER / RECITE).
-- Nullable, history rows unaffected. Single-statement ALTERs only:
-- H2 MODE=MySQL rejects multi ADD COLUMN in one statement (V5 lesson).

ALTER TABLE book ADD COLUMN kind VARCHAR(16) NULL;
ALTER TABLE book_section ADD COLUMN kind VARCHAR(16) NULL;
