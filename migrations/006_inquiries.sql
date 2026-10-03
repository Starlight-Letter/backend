CREATE TABLE inquiries (
  id uuid PRIMARY KEY,
  type text NOT NULL CHECK (type IN ('privacy', 'service', 'suggestion', 'removal')),
  body text NOT NULL CHECK (length(trim(body)) > 0),
  related_letter_id uuid REFERENCES letters(id),
  requester_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
  reply_email text,
  status text NOT NULL CHECK (status IN ('open', 'in_progress', 'resolved')),
  created_at timestamptz NOT NULL DEFAULT now(),
  resolved_at timestamptz
);

CREATE INDEX inquiries_open_priority_idx ON inquiries (type, created_at)
  WHERE status = 'open';
