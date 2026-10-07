# Node.js → Spring Boot 이관 완료 기록

## Spring Boot로 이관된 기능

- 공개 다리·질문·편지·설문 API
- 회원 가입·로그인·세션·복구·탈퇴
- 편지 작성·시작 편지·강물·상세·반응·보관·베스트
- 중복 제출 재시도 구분
- 기본 욕설·광고·개인정보 콘텐츠 검사와 운영자 검사 규칙
- 신고·문의·운영자 편지 조치
- 운영자 TOTP 로그인·초기 생성·재발급
- 질문·시작 편지·문의·검사 규칙·설문·지표·알림 운영 API
- PostgreSQL Flyway 마이그레이션, 요청 제한, Origin 및 요청 본문 크기 보호

## 제거된 Node.js 구성

- TypeScript 도메인·HTTP 서버·Lambda 핸들러
- Node 패키지와 TypeScript 설정
- 중복 SQL 마이그레이션과 Node 전용 스크립트·테스트

백엔드는 이제 Java 17, Spring Boot, Gradle, PostgreSQL만 사용한다.
