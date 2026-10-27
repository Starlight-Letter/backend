-- 편지 제거 요청 문의(removal)를 없앤다. 회원은 보관함에서 자기 편지를 직접 지운다.
-- 혹시 남아 있는 예전 제거 요청은 개인정보 문의로 옮겨 운영자가 계속 처리할 수 있게 한다.
UPDATE inquiries SET type = 'privacy' WHERE type = 'removal';

DO $$
DECLARE c record;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
           WHERE conrelid = 'inquiries'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%removal%'
  LOOP
    EXECUTE format('ALTER TABLE inquiries DROP CONSTRAINT %I', c.conname);
  END LOOP;
END $$;

ALTER TABLE inquiries ADD CONSTRAINT inquiries_type_check
  CHECK (type IN ('privacy', 'service', 'suggestion'));

-- 문의는 요청자 계정을 남기지 않는다(제거 요청이 사라져 쓸 곳이 없고, 클라이언트가 보낸 계정 값은 믿을 수 없다).
ALTER TABLE inquiries DROP COLUMN requester_user_id;
