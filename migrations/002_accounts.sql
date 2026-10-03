CREATE TABLE users (
  id uuid PRIMARY KEY,
  nickname text NOT NULL,
  nickname_key text NOT NULL,
  password_hash text NOT NULL,
  recovery_code_hash text NOT NULL,
  role text NOT NULL CHECK (role IN ('member', 'admin')),
  status text NOT NULL CHECK (status IN ('active', 'withdrawn')),
  created_at timestamptz NOT NULL DEFAULT now(),
  withdrawn_at timestamptz
);

CREATE UNIQUE INDEX users_active_nickname_idx
  ON users (nickname_key) WHERE status = 'active';

CREATE TABLE sessions (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash char(64) NOT NULL UNIQUE,
  csrf_hash char(64) NOT NULL,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX sessions_user_live_idx
  ON sessions (user_id, expires_at) WHERE revoked_at IS NULL;

CREATE TABLE auth_attempts (
  key_hash char(64) PRIMARY KEY,
  attempts integer NOT NULL CHECK (attempts >= 0),
  window_started_at timestamptz NOT NULL,
  blocked_until timestamptz
);
