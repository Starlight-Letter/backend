ALTER TABLE letters ADD COLUMN report_round integer NOT NULL DEFAULT 1 CHECK (report_round > 0);

CREATE TABLE reports (
  id uuid PRIMARY KEY,
  letter_id uuid NOT NULL REFERENCES letters(id),
  reporter_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
  round integer NOT NULL CHECK (round > 0),
  reason_code text NOT NULL CHECK (reason_code IN ('inappropriate', 'advertising', 'personal_info', 'other')),
  status text NOT NULL CHECK (status IN ('pending', 'dismissed', 'upheld')),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX reports_member_letter_unique
  ON reports (reporter_user_id, letter_id) WHERE reporter_user_id IS NOT NULL;

CREATE INDEX reports_current_round_idx ON reports (letter_id, round, status);
