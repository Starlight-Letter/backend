# 별빛안부 백엔드

별빛안부의 API와 도메인 규칙을 구현하는 TypeScript 저장소입니다. 서비스 정책은 상위 작업 폴더의 `기능명세서_최종본.md`, 개발 순서는 `별빛안부_통합_구현계획서.md`를 따릅니다.

## 현재 구현

- PostgreSQL 마이그레이션과 세 다리 초기 데이터
- `GET /api/v1/health`, `GET /api/v1/bridges`, `GET /api/v1/bridges/:id`
- 다리별 공개 질문 중 현재 시각에 적용되는 최신 질문 조회, 질문 미등록 주에는 이전 질문 유지
- 로컬 HTTP 서버와 API Gateway HTTP API 이벤트용 Lambda 진입점
- 16 KiB 요청 본문 제한, 공통 성공·오류 응답 형식

닉네임·비밀번호 가입/로그인/복구 코드/탈퇴, 편지 작성·강물·상세, 반응·보관·현재 베스트, 회원 신고 5건 자동 숨김, 비공개 문의 API를 로컬 PostgreSQL에 구현했습니다. 운영자 전용 로그인(TOTP), 질문 예약·즉시 등록·정정, 시작 편지 등록, 신고 검토·숨김·복원·삭제, 문의 목록·상태 처리와 감사 기록도 구현했습니다. 삭제할 때 본문·표시 닉네임과 보관·반응 데이터는 제거하고 별도 DB 삭제 기록을 남깁니다.

시작 편지 수정, 운영자 지정 금칙 문구, 비회원 설문 클릭과 QR·편지 열람 익명 지표, 운영자 TOTP 오프라인 재설정도 구현했습니다. 아직 지표의 가입·작성 완료 측정과 실험 기간 필터, 신고·개인정보 문의 자동 알림, 삭제 이력의 S3 이중 보관, AWS 인프라는 구현되지 않았습니다. PostgreSQL 엔진은 확정했지만 Aurora 상품과 AWS 연결 방식은 비용·성능 검증이 남아 있습니다. 현재 Lambda 진입점은 직접 PostgreSQL 연결을 사용하며 AWS 배포용 네트워크·비밀값 구성은 없습니다.

## 개발 명령

```bash
npm ci
npm test
npm run build
```

로컬 PostgreSQL에 전용 개발 DB를 준비한 뒤:

```bash
DATABASE_URL=postgresql://localhost:5432/starlight_dev npm run db:migrate
DATABASE_URL=postgresql://localhost:5432/starlight_dev APP_ORIGIN=http://localhost:5173 npm run dev
```

운영자 계정은 공개 가입으로 만들 수 없습니다. 개발 DB에 마이그레이션을 적용한 뒤 32바이트 비밀키를 base64로 만들어 `ADMIN_TOTP_KEY`에 지정하고, `ADMIN_NICKNAME`·`ADMIN_PASSWORD`와 함께 `npm run admin:provision`을 실행합니다. 출력되는 TOTP 비밀값은 최초 한 번만 안전하게 보관하고 인증 앱에 등록합니다. 인증 앱을 잃어버린 경우 DB 관리 권한이 있는 로컬 셸에서 동일한 키와 닉네임으로 `npm run admin:reset-totp`를 실행합니다. 이 작업은 기존 운영자 세션을 모두 폐기하고 새 TOTP 비밀값을 한 번 출력합니다. 서버 실행 때도 같은 `ADMIN_TOTP_KEY`가 필요합니다. 키가 없거나 바뀌면 운영자 로그인을 사용할 수 없습니다. 운영자 로그인 경로는 `POST /api/v1/admin/auth/login`이며 12시간 세션을 발급합니다.

운영자 관리 API는 `/api/v1/admin/questions`, `/api/v1/admin/seeds`, `/api/v1/admin/reports`, `/api/v1/admin/letters/:id/review`, `/api/v1/admin/inquiries`, `/api/v1/admin/moderation-rules`, `/api/v1/admin/metrics` 아래에 있습니다. 변경 요청에는 허용된 `Origin`, 운영자 세션 쿠키와 `x-csrf-token`이 필요합니다. `POST /api/v1/events/survey-open`은 클릭만 집계하고 설문 완료를 뜻하지 않습니다. `POST /api/v1/events`는 QR 진입, 편지 열람 등 익명 퍼널 이벤트를 받습니다.

서버 주소는 기본 `http://127.0.0.1:3000`입니다. 질문을 아직 등록하지 않았다면 다리 응답의 `currentQuestion`은 `null`입니다. DB 통합 테스트는 별도의 이름이 `_test`로 끝나는 DB에서 실행합니다:

로컬 인증 쿠키 이름은 `star_session_dev`입니다. 실제 HTTPS 환경에서는 `__Host-star_session`을 사용하며, 상태를 변경하는 회원 요청은 허용된 `Origin`과 `x-csrf-token`을 요구합니다. 가입 응답의 복구 코드는 그 응답에만 포함되므로 사용자가 직접 보관해야 합니다. 현재 내용 검사는 기본 규칙이며 운영자 편집·검토 체계가 완성되기 전에는 공개 출시용 검사로 간주하지 않습니다.

```bash
TEST_DATABASE_URL=postgresql://localhost:5432/starlight_test npm test
```
