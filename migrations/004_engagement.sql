CREATE TABLE reactions (
  id uuid PRIMARY KEY,
  letter_id uuid NOT NULL REFERENCES letters(id),
  user_id uuid REFERENCES users(id) ON DELETE SET NULL,
  active boolean NOT NULL,
  activated_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX reactions_member_letter_unique
  ON reactions (user_id, letter_id) WHERE user_id IS NOT NULL;

CREATE INDEX reactions_best_idx
  ON reactions (activated_at, letter_id) WHERE active = true;

CREATE TABLE saves (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  letter_id uuid NOT NULL REFERENCES letters(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, letter_id)
);

CREATE INDEX saves_member_recent_idx ON saves (user_id, created_at DESC);
