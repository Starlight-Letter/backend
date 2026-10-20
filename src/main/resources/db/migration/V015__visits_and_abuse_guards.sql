-- 구경만 하는 방문도 지표로 본다: 홈·다리 화면 조회수와 비회원/회원 구분
ALTER TABLE metric_events DROP CONSTRAINT metric_events_event_type_check;
ALTER TABLE metric_events ADD CONSTRAINT metric_events_event_type_check
  CHECK (event_type IN ('survey_open', 'qr_entry', 'letter_open', 'five_reached', 'signup_start', 'write_start', 'home_visit', 'bridge_visit'));
ALTER TABLE metric_events ADD COLUMN viewer_kind text CHECK (viewer_kind IN ('guest', 'member'));

-- 운영자 계정 정지: 정지된 계정은 로그인·작성·신고를 할 수 없고, 닉네임도 다른 사람이 가져갈 수 없다
ALTER TABLE users DROP CONSTRAINT users_status_check;
ALTER TABLE users ADD CONSTRAINT users_status_check CHECK (status IN ('active', 'withdrawn', 'suspended'));
ALTER TABLE users ADD COLUMN suspended_at timestamptz;
DROP INDEX users_active_nickname_idx;
CREATE UNIQUE INDEX users_active_nickname_idx
  ON users (nickname_key) WHERE status IN ('active', 'suspended');

-- 하루 편지 작성 수 제한을 빠르게 세기 위한 인덱스
CREATE INDEX letters_author_recent_idx
  ON letters (author_user_id, created_at DESC) WHERE author_user_id IS NOT NULL;
