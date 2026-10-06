CREATE TABLE metric_events (
  id uuid PRIMARY KEY,
  event_type text NOT NULL CHECK (event_type IN ('survey_open')),
  bridge_id text REFERENCES bridges(id),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX metric_events_type_bridge_time_idx
  ON metric_events (event_type, bridge_id, created_at DESC);
