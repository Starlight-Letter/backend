CREATE TABLE moderation_rules (
  id uuid PRIMARY KEY,
  category text NOT NULL CHECK (category IN ('profanity', 'advertising', 'personal_info')),
  phrase text NOT NULL,
  active boolean NOT NULL DEFAULT true,
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX moderation_rules_active_idx ON moderation_rules (active) WHERE active = true;
