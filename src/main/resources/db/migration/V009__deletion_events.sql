CREATE TABLE deletion_events (
  id uuid PRIMARY KEY,
  letter_id uuid NOT NULL UNIQUE REFERENCES letters(id),
  actor_user_id uuid NOT NULL REFERENCES users(id),
  reason text NOT NULL,
  reaction_count integer NOT NULL CHECK (reaction_count >= 0),
  save_count integer NOT NULL CHECK (save_count >= 0),
  deleted_at timestamptz NOT NULL DEFAULT now()
);
