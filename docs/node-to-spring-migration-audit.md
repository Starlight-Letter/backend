# Node.js → Spring Boot 이관 점검

## 이관 완료

- 공개 다리·질문·편지·설문 API
- 회원 가입·로그인·세션·복구·탈퇴
- 편지 작성·시작 편지·강물·상세·반응·보관·베스트
- 신고·문의·운영자 편지 조치
- 운영자 TOTP 로그인, 질문·시작 편지·문의·검사 규칙·설문·지표·알림
- PostgreSQL Flyway 마이그레이션과 관리자 초기 생성 도구
- 기본 욕설·광고·개인정보 콘텐츠 검사

## Node 제거 전 남은 동등성 작업

1. 가입·로그인·복구·문의·이벤트의 `auth_attempts` 기반 요청 제한 이관
2. 상태 변경 API의 Origin 검증과 16 KiB 요청 본문 제한 이관
3. 운영자 TOTP 재발급 명령 이관
4. AWS Lambda 진입점은 Spring Boot 배포 방식으로 대체하거나 삭제
5. 실제 PostgreSQL 통합 테스트에서 모든 UUID 입력 경로와 관리자 전체 흐름 점검
6. idempotency 재시도 시 `200` 반환과 재사용 여부 응답 이관

위 항목을 완료하고 Spring API와 프론트 전체 흐름을 검증한 뒤 Node.js 소스, `package.json`, TypeScript 설정과 Lambda 핸들러를 제거한다.
