-- 정식 시작 전, 개발·데모 기간에 쌓인 테스트 데이터만 지운다(한 번만 쓰는 운영 스크립트).
--
-- 남기는 것: 다리(QR 키 포함, 인쇄한 QR이 그대로 동작), 질문, 시작 편지(is_seed), 검사 규칙, 설문 설정, 운영자 계정
-- 지우는 것: 회원 계정과 세션·업적, 회원 편지, 공감, 보관, 신고, 문의, 삭제 기록, 지표, 감사 기록, 로그인 시도 기록, QR 통행권
--
-- 실행 전 pg_dump로 백업을 받아 둔다. 기본은 미리보기(끝에서 ROLLBACK)이고, -v apply=1 을 붙여야 실제로 지워진다.
--   미리보기: docker exec -i starlight-postgres psql -U postgres -d starlight < launch_reset_test_data.sql
--   적용:     docker exec -i starlight-postgres psql -U postgres -d starlight -v apply=1 < launch_reset_test_data.sql

\set ON_ERROR_STOP on

BEGIN;

\echo '== 지울 데이터 건수'
SELECT 'users(member)' AS 대상, count(*) AS 건수 FROM users WHERE role = 'member'
UNION ALL SELECT 'letters(회원 편지)', count(*) FROM letters WHERE is_seed = false
UNION ALL SELECT 'reactions', count(*) FROM reactions
UNION ALL SELECT 'saves', count(*) FROM saves
UNION ALL SELECT 'reports', count(*) FROM reports
UNION ALL SELECT 'inquiries', count(*) FROM inquiries
UNION ALL SELECT 'deletion_events', count(*) FROM deletion_events
UNION ALL SELECT 'metric_events', count(*) FROM metric_events
UNION ALL SELECT 'admin_actions', count(*) FROM admin_actions
UNION ALL SELECT 'auth_attempts', count(*) FROM auth_attempts
UNION ALL SELECT 'bridge_passes', count(*) FROM bridge_passes;

-- 편지를 가리키는 기록부터 지운 뒤 편지, 마지막에 회원을 지운다(외래 키 순서).
DELETE FROM reports;
DELETE FROM reactions;
DELETE FROM saves;
DELETE FROM inquiries;
DELETE FROM request_keys;
DELETE FROM deletion_events;
DELETE FROM admin_actions;
DELETE FROM metric_events;
DELETE FROM auth_attempts;
DELETE FROM bridge_passes;

DELETE FROM letters WHERE is_seed = false;

-- 신고가 모두 사라졌으니 시작 편지의 신고 회차도 처음으로 돌린다.
UPDATE letters SET report_round = 1 WHERE is_seed = true;

-- 회원의 세션·업적은 ON DELETE CASCADE로 함께 지워진다.
DELETE FROM users WHERE role = 'member';

\echo '== 남은 데이터'
SELECT 'bridges' AS 대상, count(*) AS 건수 FROM bridges
UNION ALL SELECT 'questions', count(*) FROM questions
UNION ALL SELECT 'letters(시작 편지)', count(*) FROM letters
UNION ALL SELECT 'moderation_rules', count(*) FROM moderation_rules
UNION ALL SELECT 'survey_campaign', count(*) FROM survey_campaign
UNION ALL SELECT 'users(admin)', count(*) FROM users;

-- 테스트 중 신고 처리·운영자 삭제로 가려진 시작 편지가 있는지 확인용. 자동으로 되돌리지는 않는다(일부러 내린 것일 수 있음).
\echo '== 시작 편지 상태 (hidden/deleted가 있으면 관리자 화면에서 확인)'
SELECT bridge_id, status, count(*) FROM letters GROUP BY bridge_id, status ORDER BY bridge_id, status;

\if :{?apply}
COMMIT;
\echo '>> 적용 완료: 테스트 데이터를 지웠습니다.'
\else
ROLLBACK;
\echo '>> 미리보기만 했습니다(아무것도 지우지 않음). 확인 후 -v apply=1 을 붙여 다시 실행하세요.'
\endif
