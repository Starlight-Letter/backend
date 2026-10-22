-- 신고 사유에 '부적절한 닉네임' 추가: 익명이 아닌 편지의 서명(닉네임)이 문제일 때
-- 제약 이름이 환경마다 다를 수 있어(V018 배포 때 확인한 부분), 이름 대신 reason_code를 검사하는 CHECK 제약을 찾아 지운다.
DO $$
DECLARE c record;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
           WHERE conrelid = 'reports'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%reason_code%'
  LOOP
    EXECUTE format('ALTER TABLE reports DROP CONSTRAINT %I', c.conname);
  END LOOP;
END $$;

ALTER TABLE reports ADD CONSTRAINT reports_reason_code_check
  CHECK (reason_code IN ('inappropriate', 'advertising', 'personal_info', 'nickname', 'other'));
