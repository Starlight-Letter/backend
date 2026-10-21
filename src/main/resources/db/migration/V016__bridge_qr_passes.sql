-- 다리별 QR 열쇠: 포스터 QR 주소에 담기는 추측할 수 없는 값. 저장소에 남지 않도록 DB에서 무작위로 만든다.
ALTER TABLE bridges ADD COLUMN qr_key text;
UPDATE bridges SET qr_key = replace(gen_random_uuid()::text, '-', '');
ALTER TABLE bridges ALTER COLUMN qr_key SET NOT NULL;
CREATE UNIQUE INDEX bridges_qr_key_idx ON bridges (qr_key);

-- QR로 들어온 브라우저에 주는 작성 허가. 한 브라우저에 하나(마지막으로 찍은 다리)만, 몇 시간 뒤 만료된다.
CREATE TABLE bridge_passes (
  token_hash char(64) PRIMARY KEY,
  bridge_id text NOT NULL REFERENCES bridges(id),
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX bridge_passes_expires_idx ON bridge_passes (expires_at);
