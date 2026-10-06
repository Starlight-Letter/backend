ALTER TABLE metric_events DROP CONSTRAINT metric_events_event_type_check;
ALTER TABLE metric_events ADD CONSTRAINT metric_events_event_type_check
  CHECK (event_type IN ('survey_open', 'qr_entry', 'letter_open', 'five_reached', 'signup_start', 'write_start'));
ALTER TABLE metric_events ADD COLUMN letter_kind text
  CHECK (letter_kind IN ('seed', 'member'));
