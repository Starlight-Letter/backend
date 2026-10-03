CREATE UNIQUE INDEX questions_bridge_effective_unique
  ON questions (bridge_id, effective_at) WHERE status = 'published';

CREATE TABLE admin_actions (
  id uuid PRIMARY KEY,
  actor_user_id uuid NOT NULL REFERENCES users(id),
  action text NOT NULL,
  target_id text NOT NULL,
  reason text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX admin_actions_recent_idx ON admin_actions (created_at DESC);
