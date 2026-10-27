-- 예전 유리병(3 물결, 4 새벽)을 없앤다. 새 편지는 1 달빛, 2 별빛, 5 별무리, 6 은하수만 쓴다.
-- 혹시 남아 있는 예전 병 편지는 색이 가까운 기본 병으로 옮긴다(물결 → 달빛, 새벽 → 별빛).
UPDATE letters SET theme = 1 WHERE theme = 3;
UPDATE letters SET theme = 2 WHERE theme = 4;

ALTER TABLE letters DROP CONSTRAINT letters_theme_check;
ALTER TABLE letters ADD CONSTRAINT letters_theme_check CHECK (theme IN (1, 2, 5, 6));
