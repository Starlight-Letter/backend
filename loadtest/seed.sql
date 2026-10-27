-- 부하 테스트용 데이터. psql -v scale=<배율> 로 실행한다.
-- scale=1 : 한 달 운영 분량, scale=3 : 한 달 x3 여유분, scale=0 : 지금 수준(편지 67통, 이벤트 약 4천 건)
\set ON_ERROR_STOP on
SELECT CASE WHEN :scale = 0 THEN 67    ELSE 5000   * :scale END AS n_letters,
       CASE WHEN :scale = 0 THEN 3931  ELSE 500000 * :scale END AS n_events,
       CASE WHEN :scale = 0 THEN 35    ELSE 2000   * :scale END AS n_users,
       CASE WHEN :scale = 0 THEN 319   ELSE 50000  * :scale END AS n_reactions,
       CASE WHEN :scale = 0 THEN 100   ELSE 15000  * :scale END AS n_saves,
       CASE WHEN :scale = 0 THEN 100   ELSE 30000  * :scale END AS n_sessions,
       CASE WHEN :scale = 0 THEN 100   ELSE 30000  * :scale END AS n_attempts,
       CASE WHEN :scale = 0 THEN 200   ELSE 60000  * :scale END AS n_passes
\gset

TRUNCATE users, sessions, auth_attempts, letters, request_keys, reactions, saves, reports, inquiries,
  admin_actions, deletion_events, metric_events, moderation_rules, survey_campaign, bridge_passes, questions CASCADE;

-- 운영자 1명 + 고정 토큰 세션(lt-admin). TOTP 로그인을 건너뛰기 위해 admin_verified_at을 바로 채운다.
INSERT INTO users (id, nickname, nickname_key, password_hash, recovery_code_hash, role, status, totp_secret_ciphertext, created_at)
VALUES ('00000000-0000-0000-0000-00000000a001', '운영자', '운영자', 'x', 'x', 'admin', 'active', 'x', now() - interval '31 days');
INSERT INTO sessions (id, user_id, token_hash, csrf_hash, csrf_token, expires_at, admin_verified_at)
VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000a001', encode(sha256('lt-admin'::bytea), 'hex'), repeat('0', 64),
        rpad('ltadmincsrf', 43, 'x'), now() + interval '1 day', now());

-- 회원
INSERT INTO users (id, nickname, nickname_key, password_hash, recovery_code_hash, role, status, created_at)
SELECT ('00000000-0000-0000-0001-' || lpad(to_hex(g), 12, '0'))::uuid, '별빛' || g, '별빛' || g, 'x', 'x', 'member', 'active',
       now() - random() * interval '30 days'
FROM generate_series(1, :n_users) g;

-- 부하 스크립트가 쓰는 회원 세션 200개(lt-member-1 … 200), csrf 토큰은 lt-csrf-N을 43자로 맞춘 값
INSERT INTO sessions (id, user_id, token_hash, csrf_hash, csrf_token, expires_at)
SELECT gen_random_uuid(), ('00000000-0000-0000-0001-' || lpad(to_hex(g), 12, '0'))::uuid,
       encode(sha256(convert_to('lt-member-' || g, 'UTF8')), 'hex'), repeat('0', 64), rpad('lt-csrf-' || g, 43, 'x'), now() + interval '1 day'
FROM generate_series(1, least(200, :n_users)) g;

-- 지워지지 않고 쌓인 과거 세션(대부분 만료)
INSERT INTO sessions (id, user_id, token_hash, csrf_hash, csrf_token, expires_at, revoked_at, created_at)
SELECT gen_random_uuid(), ('00000000-0000-0000-0001-' || lpad(to_hex(1 + (g % :n_users)), 12, '0'))::uuid,
       encode(sha256(convert_to('old-' || g, 'UTF8')), 'hex'), repeat('0', 64), repeat('x', 43),
       t + interval '30 days' - interval '31 days', CASE WHEN g % 3 = 0 THEN t END, t
FROM generate_series(1, :n_sessions) g, LATERAL (SELECT now() - random() * interval '30 days' AS t) x;

INSERT INTO auth_attempts (key_hash, attempts, window_started_at)
SELECT encode(sha256(convert_to('attempt-' || g, 'UTF8')), 'hex'), 1 + (g % 5), now() - random() * interval '30 days'
FROM generate_series(1, :n_attempts) g;

INSERT INTO bridge_passes (token_hash, bridge_id, expires_at, created_at)
SELECT encode(sha256(convert_to('pass-' || g, 'UTF8')), 'hex'), (ARRAY['bongnim','sillim','sillim2'])[1 + g % 3], t + interval '3 hours', t
FROM generate_series(1, :n_passes) g, LATERAL (SELECT now() - random() * interval '30 days' AS t) x;

-- 최악 가정: 한 달 동안 질문을 바꾸지 않아 모든 편지가 지금 질문 하나에 몰린다
INSERT INTO questions (id, bridge_id, body, effective_at, status)
VALUES ('00000000-0000-0000-0002-000000000001', 'bongnim', '오늘 하루 어땠나요?', now() - interval '31 days', 'published'),
       ('00000000-0000-0000-0002-000000000002', 'sillim', '요즘 마음을 쓰는 일이 있나요?', now() - interval '31 days', 'published'),
       ('00000000-0000-0000-0002-000000000003', 'sillim2', '누군가에게 전하고 싶은 말이 있나요?', now() - interval '31 days', 'published');

-- 시작 편지(다리마다 20통)
INSERT INTO letters (id, bridge_id, display_nickname, body, theme, is_seed, status, created_at)
SELECT gen_random_uuid(), (ARRAY['bongnim','sillim','sillim2'])[1 + g % 3], '별빛안부',
       '강물 위로 띄워 보내는 시작 편지 ' || g || '. 오늘도 수고 많았어요.', (ARRAY[1,2,5,6])[1 + g % 4], true, 'published', now() - interval '31 days'
FROM generate_series(1, 60) g;

-- 회원 편지: 3% 숨김, 1% 삭제
INSERT INTO letters (id, bridge_id, question_id, author_user_id, display_nickname, body, theme, is_seed, status, created_at)
SELECT gen_random_uuid(), b, ('00000000-0000-0000-0002-00000000000' || (1 + g % 3))::uuid,
       ('00000000-0000-0000-0001-' || lpad(to_hex(1 + (g * 7) % :n_users), 12, '0'))::uuid,
       '익명의 별' || g,
       '오늘은 도림천을 따라 걸었어요. 물소리가 생각보다 커서 마음이 조금 가라앉았어요. 당신의 하루도 무사했기를 바라요. #' || g,
       (ARRAY[1,2,5,6])[1 + g % 4], false, CASE WHEN g % 100 = 0 THEN 'deleted' WHEN g % 33 = 0 THEN 'hidden' ELSE 'published' END,
       now() - random() * interval '30 days'
FROM generate_series(1, :n_letters) g, LATERAL (SELECT (ARRAY['bongnim','sillim','sillim2'])[1 + g % 3] AS b) x;
UPDATE letters SET body = NULL WHERE status = 'deleted';

INSERT INTO request_keys (user_id, key, request_hash, letter_id, created_at)
SELECT author_user_id, 'key-' || id, repeat('0', 64), id, created_at FROM letters WHERE author_user_id IS NOT NULL;

CREATE TEMP TABLE pub AS SELECT row_number() OVER () AS n, id, created_at FROM letters WHERE status = 'published';
SELECT count(*) AS n_pub FROM pub \gset

-- 위로: 일부 편지에 몰리도록 제곱 분포
INSERT INTO reactions (id, letter_id, user_id, active, activated_at)
SELECT gen_random_uuid(), p.id, ('00000000-0000-0000-0001-' || lpad(to_hex(1 + floor(random() * :n_users)::int), 12, '0'))::uuid,
       random() > 0.1, p.created_at + random() * (now() - p.created_at)
FROM generate_series(1, :n_reactions) g
CROSS JOIN LATERAL (SELECT 1 + floor(power(random(), 2) * :n_pub)::int + g * 0 AS pick) k
JOIN pub p ON p.n = k.pick
ON CONFLICT DO NOTHING;

INSERT INTO saves (user_id, letter_id, created_at)
SELECT ('00000000-0000-0000-0001-' || lpad(to_hex(1 + floor(random() * :n_users)::int), 12, '0'))::uuid, p.id,
       p.created_at + random() * (now() - p.created_at)
FROM generate_series(1, :n_saves) g
CROSS JOIN LATERAL (SELECT 1 + floor(random() * :n_pub)::int + g * 0 AS pick) k
JOIN pub p ON p.n = k.pick
ON CONFLICT DO NOTHING;

INSERT INTO reports (id, letter_id, reporter_user_id, round, reason_code, status, created_at)
SELECT gen_random_uuid(), p.id, NULL, 1, 'other', CASE WHEN g % 4 = 0 THEN 'pending' ELSE 'dismissed' END, now() - random() * interval '30 days'
FROM generate_series(1, greatest(10, :n_letters / 10)) g JOIN pub p ON p.n = 1 + (g * 13) % :n_pub;

-- 방문 기록: 페이지를 열 때마다 쌓이는 이벤트
INSERT INTO metric_events (id, event_type, bridge_id, letter_kind, viewer_kind, created_at)
SELECT gen_random_uuid(), t,
       CASE WHEN t IN ('home_visit', 'survey_open') THEN NULL ELSE (ARRAY['bongnim','sillim','sillim2'])[1 + g % 3] END,
       CASE WHEN t = 'letter_open' THEN (CASE WHEN g % 5 = 0 THEN 'seed' ELSE 'member' END) END,
       CASE WHEN t IN ('home_visit', 'bridge_visit', 'letter_open') THEN (CASE WHEN g % 4 = 0 THEN 'member' ELSE 'guest' END) END,
       now() - random() * interval '30 days'
FROM generate_series(1, :n_events) g,
LATERAL (SELECT CASE WHEN r < 0.28 THEN 'home_visit' WHEN r < 0.58 THEN 'bridge_visit' WHEN r < 0.88 THEN 'letter_open'
                     WHEN r < 0.93 THEN 'qr_entry' WHEN r < 0.96 THEN 'five_reached' WHEN r < 0.98 THEN 'signup_start'
                     WHEN r < 0.99 THEN 'write_start' ELSE 'survey_open' END AS t
         FROM (SELECT random() + g * 0 AS r) z) x;

VACUUM ANALYZE;

SELECT relname, n_live_tup, pg_size_pretty(pg_total_relation_size(relid)) AS size
FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;
SELECT pg_size_pretty(pg_database_size('starlight')) AS db_size;
