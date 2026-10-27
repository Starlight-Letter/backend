-- 앱이 실제로 보내는 쿼리를 그대로 EXPLAIN ANALYZE 한다(실행 시간 줄만 뽑아 본다)
\set q '''00000000-0000-0000-0002-000000000001'''
\echo == 강물: 회원 편지 무작위 후보 20 → 안 본 편지 가중 5
EXPLAIN (ANALYZE, BUFFERS) SELECT id::text AS id, bridge_id, theme, is_seed, body, created_at FROM (SELECT id, bridge_id, theme, is_seed, body, created_at FROM letters
 WHERE bridge_id = 'bongnim' AND question_id = :q::uuid AND is_seed = false AND status = 'published' ORDER BY random() LIMIT 20) candidates
 ORDER BY power(random(), 1.0::float8 / CASE WHEN id = ANY('{}'::uuid[]) THEN 1 ELSE 20 END) DESC LIMIT 5;
\echo == 다리 화면: 지금 질문 편지 수
EXPLAIN (ANALYZE, BUFFERS) select b.id, (select count(*)::int from letters l where l.question_id = q.id and l.is_seed = false and l.status = 'published')
 from bridges b left join lateral (select id from questions where bridge_id = b.id and status = 'published' and effective_at <= now() order by effective_at desc, id desc limit 1) q on true
 where b.active = true and b.id = 'bongnim';
\echo == 서랍장: 이번 달 전체
EXPLAIN (ANALYZE, BUFFERS) SELECT l.id::text, l.bridge_id, l.body, l.display_nickname, l.theme, q.body, count(r.id)::int AS reaction_count
 FROM reactions r JOIN letters l ON l.id = r.letter_id LEFT JOIN questions q ON l.question_id = q.id
 WHERE r.active = true AND r.activated_at >= now() - interval '30 days' AND r.activated_at <= now()
   AND l.status = 'published' AND (NULL::text IS NULL OR l.bridge_id = NULL)
 GROUP BY l.id, q.body ORDER BY reaction_count DESC, max(r.activated_at) DESC, l.id ASC LIMIT 10;
\echo == 지표: 다리 방문(기간 없음)
EXPLAIN (ANALYZE, BUFFERS) select bridge_id,count(*)::int,count(*) filter (where viewer_kind='guest')::int,count(*) filter (where viewer_kind='member')::int
 from metric_events where event_type='bridge_visit' and (NULL::timestamptz is null or created_at>=NULL::timestamptz) and (NULL::timestamptz is null or created_at<NULL::timestamptz)
 group by bridge_id order by bridge_id;
\echo == 지표: 다리 방문(최근 7일)
EXPLAIN (ANALYZE, BUFFERS) select bridge_id,count(*)::int from metric_events where event_type='bridge_visit'
 and (now()-interval '7 days' is null or created_at>=now()-interval '7 days') and (now() is null or created_at<now()) group by bridge_id order by bridge_id;
\echo == 운영자 신고 알림
EXPLAIN (ANALYZE, BUFFERS) select count(*)::int total,count(*) filter(where status='hidden')::int hidden from letters l
 where status<>'deleted' and exists(select 1 from reports r where r.letter_id=l.id and r.round=l.report_round and r.status='pending');
\echo == 띄워 보낸 흔적(편지 많은 회원)
EXPLAIN (ANALYZE, BUFFERS) SELECT (SELECT count(*)::int FROM letters WHERE author_user_id = u AND status <> 'deleted'),
 (SELECT count(*)::int FROM reactions r JOIN letters l ON l.id = r.letter_id WHERE l.author_user_id = u AND l.status <> 'deleted' AND r.active = true AND r.user_id IS DISTINCT FROM u),
 (SELECT count(*)::int FROM saves s JOIN letters l ON l.id = s.letter_id WHERE l.author_user_id = u AND l.status = 'published' AND s.user_id <> u)
 FROM (SELECT '00000000-0000-0000-0001-000000000008'::uuid AS u) x;
