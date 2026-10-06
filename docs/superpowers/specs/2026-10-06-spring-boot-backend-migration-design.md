# 별빛안부 Spring Boot 백엔드 전환 설계

## 목적과 성공 기준

Node.js·TypeScript 백엔드를 Java 17과 Spring Boot 3 기반 서비스로 완전히 전환한다. 전환 뒤 저장소에는 Node 실행 코드와 의존성을 남기지 않는다. React 프론트엔드는 기존 `/api/v1` URL, JSON 구조, 쿠키 이름을 바꾸지 않고 새 서버와 동작해야 한다.

성공 기준은 다음과 같다.

- PostgreSQL의 기존 데이터와 14개 스키마 변경 이력을 그대로 사용한다.
- 회원, 편지, 반응·보관, 신고, 문의, 운영자, 설문·지표 기능의 HTTP 계약을 유지한다.
- 비회원·회원·운영자 권한, CORS, CSRF, 세션 쿠키, 요청 크기 제한과 오류 형식을 유지한다.
- Spring Boot 테스트와 실제 PostgreSQL 통합 테스트가 기존 기능 시나리오를 검증한다.
- `package.json`, TypeScript 소스, Node 테스트·실행 스크립트가 제거된다.

## 선택한 구조

Gradle Wrapper를 포함한 Spring Boot 단일 애플리케이션을 `backend` 저장소의 최상위에 둔다. Java 17, Spring Web, Spring JDBC, Spring Validation, Flyway, PostgreSQL JDBC, Spring Security Crypto, Argon2, JUnit 5와 Testcontainers를 사용한다.

애플리케이션은 계층을 다음처럼 나눈다.

```
web/          HTTP 컨트롤러, 요청·응답 DTO, 예외 처리, CORS·쿠키 필터
application/  회원·편지·운영·지표 유스케이스와 트랜잭션 경계
domain/       정책 검증, 역할, 오류 코드, KST 시간 규칙
infrastructure/ JDBC 저장소, Flyway, 암호·TOTP·난수 구현
```

컨트롤러는 `/api/v1` 계약만 담당한다. 서비스는 업무 규칙과 트랜잭션을 담당하고, JDBC 저장소는 PostgreSQL 질의만 담당한다. 이 경계로 HTTP 테스트와 DB 통합 테스트를 분리한다.

## HTTP 호환 규칙

모든 성공 응답은 `{ "data": ... }`, 실패 응답은 `{ "error": { "code", "message" } }`를 유지한다. `cache-control: no-store`, `x-request-id` 헤더와 16 KiB 본문 제한을 유지한다.

다음 공개 경로를 그대로 유지한다.

- 상태·다리: `GET /health`, `GET /bridges`, `GET /bridges/{bridgeId}`, `GET /bridges/{bridgeId}/river`
- 계정: 가입·로그인·복구·로그아웃, 현재 세션, 복구 코드 교체, 비밀번호 변경, 탈퇴
- 편지: 작성, 상세, 베스트, 보관함, 반응·보관 상태와 토글, 신고
- 문의·설문·익명 이벤트
- 운영자 로그인, 질문, 시작 편지, 신고·편지 검토, 문의, 금칙 규칙, 설문, 지표, 경고

개발 환경은 `star_session_dev`, HTTPS 운영 환경은 `__Host-star_session` 쿠키를 사용한다. 상태 변경 요청은 허용 Origin, 세션, `x-csrf-token`을 검증한다. 운영자 세션은 12시간, 회원 세션은 30일을 유지한다. 기존 프론트가 사용하는 쿠키 기반 인증과 CSRF 토큰 응답 필드는 변경하지 않는다.

## 데이터베이스와 이전 전략

기존 PostgreSQL 테이블과 컬럼은 변경하지 않는다. SQL 파일은 `src/main/resources/db/migration/V001__bridges_questions.sql`부터 `V014__stable_csrf_token.sql`까지 옮기고 Flyway가 새 DB를 구성하게 한다.

이미 Node 백엔드에서 생성한 DB는 기존 `schema_migrations` 테이블을 유지한다. Spring 설정에서 Flyway baseline을 버전 `14`로 설정해 기존 스키마를 재실행하지 않는다. 새 빈 DB에는 V001부터 V014까지 정상 적용한다. 이전 작업 전 백업을 만들고, 운영 DB에서 Flyway baseline 검증을 먼저 수행한다.

인증 테이블의 Argon2 해시 문자열, 세션 토큰 해시, CSRF 토큰, 복구 코드, TOTP 비밀값은 형식을 바꾸지 않는다. Java의 Argon2 검증기는 기존 PHC 형식 해시를 검증해야 한다. 비밀번호 재설정·변경 때 새 해시도 같은 호환 형식으로 저장한다.

## 주요 정책 보존

- 한국 시간 월요일 기준 최신 공개 질문을 사용하며 새 질문이 없으면 지난 질문을 유지한다.
- 회원만 편지 작성, 반응, 보관, 신고를 할 수 있다. 비회원은 다리별로 새로고침 전 5개 편지만 본문 열람할 수 있다. 이 제한은 현재 프론트의 세션 메모리 정책이므로 API 계약에는 추가하지 않는다.
- 반응과 보관은 각각 토글이며, 동일 회원의 중복 기록은 허용하지 않는다.
- 서로 다른 회원 5명의 신고가 누적되면 편지를 숨기고 운영자가 복원·삭제한다.
- 탈퇴해도 공개 편지와 표시 닉네임, 반응 집계는 남고 보관 목록은 제거된다.
- 운영자 로그인은 비밀번호와 TOTP를 함께 검증한다.

## 설정과 실행

`application.yml`에는 포트, 허용 Origin, 쿠키 보안 여부, 데이터 소스, 관리자 TOTP 키를 환경 변수로 받는다. 비밀값은 저장소에 넣지 않는다.

로컬 개발 서버는 3000번 포트에서 실행한다. 프론트 Vite 프록시는 기존처럼 `/api`를 3000번으로 전달한다. Docker Compose는 PostgreSQL 테스트 DB와 Spring 테스트를 지원한다. AWS 배포 구성은 이 전환 범위에 포함하지 않으며, 이후 Spring Boot JAR 또는 컨테이너를 기준으로 별도 구성한다.

## 검증

1. JUnit 단위 테스트로 질문 주차 계산, 본문 검증, TOTP, 권한·오류 코드를 검증한다.
2. Testcontainers PostgreSQL 통합 테스트로 Flyway, 회원, 편지, 참여, 신고, 문의, 운영자, 지표 흐름을 검증한다.
3. MockMvc 또는 실제 임시 포트 테스트로 쿠키, CORS, CSRF, 요청 본문 제한과 JSON 계약을 검증한다.
4. React 테스트와 빌드 후 Spring Boot 로컬 서버에 연결해 다리 조회, 로그인, 편지 작성, 반응·보관, 운영자 흐름을 수동 점검한다.

## 제외 범위

AWS 배포와 외부 알림은 이번 전환에 포함하지 않는다. 사용자 기능 추가나 API 재설계도 포함하지 않는다. 목적은 현재 구현의 동작을 Spring Boot로 안전하게 대체하는 것이다.
