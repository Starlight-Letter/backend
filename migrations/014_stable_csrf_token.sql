ALTER TABLE sessions ADD COLUMN csrf_token char(43);

UPDATE sessions
SET csrf_token = ''
WHERE csrf_token IS NULL;

ALTER TABLE sessions ALTER COLUMN csrf_token SET NOT NULL;
