-- 편지 하나에 달린 위로 수를 셀 때(편지 열기·위로 누르기·띄워 보낸 흔적) 위로 테이블 전체를 읽지 않도록 한다.
-- 기존 인덱스는 (회원, 편지)·(시각, 편지) 순서라 편지 id만으로는 쓸 수 없었다(부하 테스트로 확인).
CREATE INDEX reactions_letter_active_idx ON reactions (letter_id) WHERE active = true;

-- 보관 기록도 편지 id로 찾는 곳(띄워 보낸 흔적의 '간직됨', 편지 최종 삭제 시 보관 기록 제거)이 있어 같은 인덱스를 둔다.
CREATE INDEX saves_letter_idx ON saves (letter_id);
