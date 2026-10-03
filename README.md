# 별빛안부 백엔드

별빛안부의 API와 도메인 규칙을 구현하는 TypeScript 저장소입니다. 서비스 정책은 상위 작업 폴더의 `기능명세서_최종본.md`, 개발 순서는 `별빛안부_통합_구현계획서.md`를 따릅니다.

## 현재 구현

- PostgreSQL 마이그레이션과 세 다리 초기 데이터
- `GET /api/v1/health`, `GET /api/v1/bridges`, `GET /api/v1/bridges/:id`
- 다리별 공개 질문 중 현재 시각에 적용되는 최신 질문 조회, 질문 미등록 주에는 이전 질문 유지
- 로컬 HTTP 서버와 API Gateway HTTP API 이벤트용 Lambda 진입점
- 16 KiB 요청 본문 제한, 공통 성공·오류 응답 형식

회원가입·편지·반응·신고·문의·운영자 API와 AWS 인프라는 아직 구현되지 않았습니다. PostgreSQL 엔진은 확정했지만 Aurora 상품과 AWS 연결 방식은 비용·성능 검증이 남아 있습니다. 현재 Lambda 진입점은 직접 PostgreSQL 연결을 사용하며 AWS 배포용 네트워크·비밀값 구성은 없습니다.

## 개발 명령

```bash
npm ci
npm test
npm run build
```

로컬 PostgreSQL에 전용 개발 DB를 준비한 뒤:

```bash
DATABASE_URL=postgresql://localhost:5432/starlight_dev npm run db:migrate
DATABASE_URL=postgresql://localhost:5432/starlight_dev npm run dev
```

서버 주소는 기본 `http://127.0.0.1:3000`입니다. 질문을 아직 등록하지 않았다면 다리 응답의 `currentQuestion`은 `null`입니다. DB 통합 테스트는 별도의 이름이 `_test`로 끝나는 DB에서 실행합니다:

```bash
TEST_DATABASE_URL=postgresql://localhost:5432/starlight_test npm test
```
