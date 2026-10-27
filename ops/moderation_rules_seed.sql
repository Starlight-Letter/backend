-- 운영자 검사 규칙 기본 목록을 한 번에 넣는다(이미 있는 문구는 건너뛴다). 넣은 뒤 1분 안에 서버에 반영된다.
-- 실행: docker exec -i starlight-postgres psql -U postgres -d starlight < ops/moderation_rules_seed.sql
--
-- 비교 방식(ModerationText): 기호·한글 사이 숫자는 지우고(씨.발, 씨1발), 한 글자 조각끼리는 붙여서(씨 발) 본다.
-- 3글자 이상 한글 문구는 띄어 쓴 낱말에 걸쳐서도 본다(오픈 채팅으로, 미친 놈아). 단 낱말 처음에서 시작하고 끝에는 조사만 붙어야 한다.
-- 평범한 말에 들어가는 짧은 말(보지·자지·새끼·꺼져·걸레·시바·대출·토토·야동·투자·코인·인스타·닥쳐(닥쳐오다)·급전(급전환)·먹튀)과
-- 편지에 평범하게 쓰일 수 있는 말(재택 알바, 주식 추천, 드라마 카지노, 계좌번호)은 넣지 않았다. 새 문구를 넣기 전에 src/test/.../ModerationFalsePositiveTest의 평범한 문장 목록에 걸리지 않는지 확인한다.
-- 기본 검사에 이미 있는 씨발·시발·좆·개새끼·병신·ㅅㅂ, 이메일, 010 번호, 링크 주소는 넣지 않는다.
\set ON_ERROR_STOP on
BEGIN;
WITH admin AS (SELECT id FROM users WHERE role = 'admin' AND status = 'active' ORDER BY created_at LIMIT 1),
rules(category, phrase) AS (VALUES
  -- 욕설·비속어
  ('profanity', '씨바'), ('profanity', '씨팔'), ('profanity', '시팔'), ('profanity', 'ㅆㅂ'), ('profanity', 'ㅂㅅ'),
  ('profanity', '븅신'), ('profanity', '빙신'), ('profanity', '좆같'), ('profanity', '존나'), ('profanity', 'ㅈㄴ'),
  ('profanity', '개색기'), ('profanity', '개섀끼'), ('profanity', '개세끼'), ('profanity', '개쌔끼'), ('profanity', '느금'),
  ('profanity', '니애미'), ('profanity', '엠창'), ('profanity', '지랄'), ('profanity', 'ㅈㄹ'),
  ('profanity', '또라이'), ('profanity', '미친놈'), ('profanity', '미친년'), ('profanity', '쌍년'), ('profanity', '썅'),
  ('profanity', '창녀'), ('profanity', '섹스'), ('profanity', '한남충'), ('profanity', '김치녀'), ('profanity', '틀딱'),
  ('profanity', '맘충'), ('profanity', '급식충'), ('profanity', 'fuck'), ('profanity', 'shit'), ('profanity', 'bitch'),
  -- 상업 광고·스팸
  ('advertising', '오픈채팅'), ('advertising', '오픈채팅방'), ('advertising', '오픈카톡'), ('advertising', '오픈카톡방'),
  ('advertising', '오카방'), ('advertising', '텔레그램'),
  ('advertising', '카톡아이디'), ('advertising', '라인아이디'), ('advertising', '디엠주세요'), ('advertising', 'dm주세요'),
  ('advertising', '맞팔'), ('advertising', '선팔해'), ('advertising', '팔로우해'), ('advertising', '부업모집'),
  ('advertising', '고수익'), ('advertising', '수익인증'), ('advertising', '리딩방'), ('advertising', '대출상담'),
  ('advertising', '대출문의'), ('advertising', '온라인카지노'), ('advertising', '카지노사이트'), ('advertising', '토토사이트'),
  ('advertising', '바카라'), ('advertising', '슬롯사이트'), ('advertising', '먹튀검증'), ('advertising', '조건만남'),
  ('advertising', '애인대행'), ('advertising', '추천인코드'), ('advertising', 'bit.ly'),
  -- 개인정보 노출
  ('personal_info', '공일공'), ('personal_info', '주민번호'), ('personal_info', '입금계좌'),
  ('personal_info', '선입금'), ('personal_info', '카톡id'), ('personal_info', '카카오톡아이디'), ('personal_info', '인스타아이디')
)
INSERT INTO moderation_rules (id, category, phrase, active, created_by)
SELECT gen_random_uuid(), r.category, r.phrase, true, admin.id
FROM rules r CROSS JOIN admin
WHERE NOT EXISTS (SELECT 1 FROM moderation_rules m WHERE lower(m.phrase) = lower(r.phrase));
COMMIT;
SELECT category AS 범주, count(*) AS 규칙수 FROM moderation_rules WHERE active GROUP BY category ORDER BY category;
