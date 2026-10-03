ALTER TABLE questions ADD CONSTRAINT questions_id_bridge_unique UNIQUE (id, bridge_id);

CREATE TABLE letters (
  id uuid PRIMARY KEY,
  bridge_id text NOT NULL REFERENCES bridges(id),
  question_id uuid,
  author_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
  display_nickname text,
  body text,
  theme smallint NOT NULL CHECK (theme BETWEEN 1 AND 4),
  is_seed boolean NOT NULL,
  status text NOT NULL CHECK (status IN ('published', 'hidden', 'deleted')),
  created_at timestamptz NOT NULL DEFAULT now(),
  hidden_at timestamptz,
  deleted_at timestamptz,
  FOREIGN KEY (question_id, bridge_id) REFERENCES questions (id, bridge_id),
  CHECK ((is_seed AND question_id IS NULL AND author_user_id IS NULL)
      OR (NOT is_seed AND question_id IS NOT NULL)),
  CHECK (status = 'deleted' OR (body IS NOT NULL AND display_nickname IS NOT NULL))
);

CREATE INDEX letters_member_river_idx
  ON letters (bridge_id, question_id, created_at DESC)
  WHERE status = 'published' AND is_seed = false;

CREATE INDEX letters_seed_river_idx
  ON letters (bridge_id, created_at DESC)
  WHERE status = 'published' AND is_seed = true;

CREATE INDEX letters_author_idx ON letters (author_user_id) WHERE author_user_id IS NOT NULL;

CREATE TABLE request_keys (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  key text NOT NULL,
  request_hash char(64) NOT NULL,
  letter_id uuid REFERENCES letters(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, key)
);
