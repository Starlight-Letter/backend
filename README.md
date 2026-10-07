# 별빛안부 백엔드

Spring Boot 3 · Java 17 · PostgreSQL 기반의 별빛안부 API입니다.

## 실행 준비

```bash
cp .env.example .env
```

`.env`에 PostgreSQL JDBC URL, 계정 정보, `ADMIN_TOTP_KEY`를 설정합니다. `ADMIN_TOTP_KEY`는 Base64로 인코딩한 32바이트 키입니다.

```bash
JDBC_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/starlight_dev
DATABASE_USERNAME=starlight
DATABASE_PASSWORD=change-me
ADMIN_TOTP_KEY=base64-encoded-32-byte-key
PORT=3000
```

애플리케이션은 시작할 때 Flyway로 스키마를 적용합니다.

```bash
./gradlew bootRun
```

프론트엔드는 `VITE_API_TARGET=http://127.0.0.1:3000`을 설정한 뒤 실행합니다.

## 운영자 계정 생성

운영자 계정은 일반 회원가입으로 만들 수 없습니다. 다음 환경 변수를 설정한 뒤 실행합니다.

```bash
ADMIN_NICKNAME=운영자 ADMIN_PASSWORD='안전한비밀번호' \
./gradlew bootRun --args='--starlight.provision-admin=true'
```

콘솔에 한 번만 출력되는 TOTP 비밀값을 인증 앱에 등록합니다. 앱 실행 때도 같은 `ADMIN_TOTP_KEY`를 유지해야 운영자 로그인이 가능합니다. 인증 앱을 잃어버린 경우 `ADMIN_NICKNAME`과 같은 키를 설정하고 `./gradlew bootRun --args='--starlight.reset-admin-totp=true'`를 실행합니다. 기존 운영자 세션은 폐기됩니다.

## 테스트

```bash
docker compose -f docker-compose.test.yml up -d --wait
TEST_DATABASE_URL=postgresql://starlight:starlight_test_password@127.0.0.1:54329/starlight_test ./gradlew test --no-daemon --offline
```

## 주요 API

- 공개: 다리, 질문, 강물 편지, 편지 상세, 설문 링크
- 회원: 가입·로그인·계정 관리, 편지 작성, 반응, 보관, 신고, 문의
- 운영자: TOTP 로그인, 질문·시작 편지·검사 규칙·신고·문의 관리, 설문 설정, 지표 및 알림
