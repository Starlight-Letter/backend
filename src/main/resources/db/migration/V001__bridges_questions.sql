CREATE TABLE bridges (
  id text PRIMARY KEY,
  name text NOT NULL,
  display_order smallint NOT NULL UNIQUE,
  active boolean NOT NULL DEFAULT true
);

INSERT INTO bridges (id, name, display_order) VALUES
  ('bongnim', '봉림교', 1),
  ('sillim', '신림교', 2),
  ('sillim2', '신림2교', 3);

CREATE TABLE questions (
  id uuid PRIMARY KEY,
  bridge_id text NOT NULL REFERENCES bridges(id),
  body text NOT NULL CHECK (length(trim(body)) > 0),
  effective_at timestamptz NOT NULL,
  status text NOT NULL CHECK (status IN ('draft', 'published')),
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX questions_current_idx
  ON questions (bridge_id, effective_at DESC, id DESC)
  WHERE status = 'published';
