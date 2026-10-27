# 별빛안부 · Starlight Letter (Backend)

> **도림천 다리 아래, 유리병에 담아 띄우는 짧은 안부.**
> 별빛안부의 API 서버입니다. 2GB짜리 작은 서버 한 대에서도 멈추지 않도록, 부하 테스트로 문제를 찾아 고치고 운영 지표로 지켜보며 만들었습니다.

[![서비스](https://img.shields.io/badge/service-starletter.co.kr-0a1b2a)](https://www.starletter.co.kr/)
![Java](https://img.shields.io/badge/Java-17-007396)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-6db33f)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791)
![Tests](https://img.shields.io/badge/tests-59%20passed-2ea44f)
![Monitoring](https://img.shields.io/badge/monitoring-Grafana%20Cloud-f46800)

- **서비스**: https://www.starletter.co.kr/
- **프론트엔드 저장소**: [Starlight-Letter/frontend](https://github.com/Starlight-Letter/frontend): 화면, 디자인, 기획 문서
- **부하 테스트·트러블슈팅 기록**: [`docs/트러블슈팅_부하테스트.md`](./docs/트러블슈팅_부하테스트.md)

---

## 목차

1. [한눈에 보기](#한눈에-보기)
2. [시스템 아키텍처](#시스템-아키텍처)
3. [요청은 이렇게 흐릅니다](#요청은-이렇게-흐릅니다)
4. [기술 스택](#기술-스택)
5. [핵심 도메인과 정책](#핵심-도메인과-정책)
6. [데이터 모델](#데이터-모델)
7. [API](#api)
8. [보안 설계](#보안-설계)
9. [내용 검사: 우회는 막고 멀쩡한 편지는 통과시키기](#내용-검사-우회는-막고-멀쩡한-편지는-통과시키기)
10. [부하 테스트와 트러블슈팅](#부하-테스트와-트러블슈팅)
11. [모니터링](#모니터링)
12. [배포](#배포)
13. [로컬 실행과 테스트](#로컬-실행과-테스트)
14. [프로젝트 구조](#프로젝트-구조)
15. [한계와 다음 단계](#한계와-다음-단계)

---

## 한눈에 보기

별빛안부는 관악구 별빛내린천(도림천)의 세 다리에서 QR을 찍고, 매주 바뀌는 질문에 짧은 편지로 답해 강물에 띄우는 모바일 웹 서비스입니다. 서비스 소개와 화면은 [프론트엔드 README](https://github.com/Starlight-Letter/frontend)에 있습니다.

| 항목 | 내용 |
|---|---|
| 운영 서버 | AWS EC2 **t3.small** (2 vCPU, 메모리 2GB) 한 대, Docker Compose |
| 부하 테스트 | k6로 11가지 케이스 측정. **사이트를 멈추게 하던 문제 4가지**를 찾아 해결 |
| 출시 직전 측정 | 한 달 운영 분량 데이터에서 **181 req/s, 실패 0%**, 주요 API p95 **2.6~6.3ms** |
| 메모리 | 로그인 몰림 반복 시 **1.2GB(한계)·스왑 → 최고 239MiB·스왑 0** |
| 모니터링 | Grafana Alloy → **Grafana Cloud(Prometheus)**. 대시보드 4개, 알림 6개(메일·디스코드) |
| 분석 | 서버 실험 지표(운영자 콘솔) + **GA4**(GTM 맞춤 이벤트) |
| 테스트 | JUnit **59개**. 동시성 회귀 테스트, 실제 PostgreSQL 통합 테스트, 내용 검사 오탐 검증 포함 |
| DB 변경 이력 | Flyway 마이그레이션 **21개** |

---

## 시스템 아키텍처

![별빛안부 시스템 아키텍처](docs/architecture.png)

<details>
<summary>텍스트로 보기 (Mermaid)</summary>

그림 원본은 [`docs/architecture.svg`](docs/architecture.svg)입니다.

```mermaid
flowchart TB
    subgraph Client["📱 이용자"]
        Browser["모바일 브라우저<br/>(QR · 홈 화면)"]
        Admin["운영자<br/>+ OTP 인증 앱"]
    end

    subgraph AWS["☁️ AWS"]
        CF["CloudFront<br/>www.starletter.co.kr · HTTPS"]
        S3["S3<br/>React 빌드 결과"]
        subgraph EC2["EC2 t3.small · Ubuntu · Docker Compose"]
            BE["starlight-backend<br/>Spring Boot 3.4 · Java 17<br/>:3000 API · :9091 지표(내부)"]
            DB[("starlight-postgres<br/>PostgreSQL 16<br/>127.0.0.1:5432")]
            AL["starlight-alloy<br/>Grafana Alloy<br/>(메모리 200MB 상한)"]
        end
    end

    subgraph Observe["📈 관측"]
        GC["Grafana Cloud<br/>Prometheus (도쿄)"]
        DASH["대시보드 4종"]
        ALERT["알림 6종"]
        MAIL["📧 메일"]
        DISCORD["💬 디스코드"]
        GA["Google Analytics 4"]
    end

    Browser -->|"HTTPS"| CF
    Admin -->|"HTTPS /admin"| CF
    CF -->|"/ , /assets, /media"| S3
    CF -->|"/api/*"| BE
    BE -->|"JDBC · HikariCP"| DB
    AL -->|"60초마다 수집"| BE
    AL -->|"서버 지표(CPU·메모리·스왑·디스크)"| EC2
    AL -->|"DB 지표(pg_monitor 계정)"| DB
    AL -->|"remote_write"| GC
    GC --> DASH
    GC --> ALERT
    ALERT --> MAIL
    ALERT --> DISCORD
    Browser -.->|"GTM 맞춤 이벤트"| GA
```

</details>

| 구성 요소 | 역할과 설계 이유 |
|---|---|
| **CloudFront** | 화면(S3)과 API(EC2)를 **같은 도메인**으로 묶습니다. 같은 출처라 CORS 없이 쿠키 세션과 CSRF 토큰을 씁니다. HTTPS를 여기서 처리하고, 서울 엣지에서 정적 파일을 캐시합니다 |
| **S3** | React 정적 빌드. 해시가 붙은 `assets/`는 오래 캐시하고, 배포할 때 `index.html`과 사진은 무효화합니다 |
| **EC2 t3.small** | 비용을 최소로 하면서 실험 기간(약 3주)을 버티는 것이 목표였습니다. 메모리 2GB와 스왑 2GB, CPU 크레딧 소진 상황까지 흉내 내서 미리 측정했습니다 |
| **Spring Boot** | JVM 힙은 `-Xmx512m`로 묶고, Argon2 네이티브 메모리 누적을 막으려고 glibc 할당기를 조정했습니다(`MALLOC_ARENA_MAX=2` 등, [트러블슈팅 ③](#3-운영-사양에서만-드러난-비밀번호-해시-메모리-폭주)). 지표 포트(9091)는 외부로 열지 않습니다 |
| **PostgreSQL** | 서버 안(`127.0.0.1`)에만 엽니다. 백엔드는 Docker 내부 네트워크로 붙고, DB 도구는 SSH 터널로 접속합니다 |
| **Grafana Alloy** | 서버에 Prometheus·Grafana를 직접 띄우는 대신 **수집기 하나만** 두고 저장·화면은 Grafana Cloud에 맡겼습니다. 2GB 서버의 메모리를 아끼기 위해서예요. 실측 약 50~90MB |
| **Grafana Cloud** | 무료 플랜(보관 14일). 수집 간격 60초, 시계열 약 1,600개로 무료 한도(1만 개) 안에서 운영합니다 |
| **GA4** | 화면 이벤트(QR 유입, 편지 쓰기 버튼, 위로)는 GTM으로 보냅니다. 전환 지표는 서버에서도 따로 셉니다 |

---

## 요청은 이렇게 흐릅니다

QR을 찍고 편지를 띄우기까지의 흐름입니다. 이 흐름 하나에 작성 허가, 중복 제출 방지, 하루 한도, 동시성 제어, 내용 검사가 모두 들어 있습니다.

```mermaid
sequenceDiagram
    autonumber
    participant U as 📱 이용자
    participant CF as CloudFront
    participant API as Spring Boot
    participant DB as PostgreSQL

    U->>CF: QR 주소 열기 (/bridges/sillim?source=qr&k=열쇠)
    CF->>API: POST /api/v1/bridges/sillim/pass {key}
    API->>DB: 다리 QR 열쇠 확인 · 통행권 해시 저장 (3시간)
    API-->>U: Set-Cookie: star_bridge_pass (HttpOnly)
    Note over U: 화면은 주소창에서 열쇠(k)를 지운다

    U->>CF: POST /api/v1/bridges/sillim/letters<br/>x-csrf-token · idempotency-key
    CF->>API: 전달 (X-Forwarded-For 한 단계 신뢰)
    API->>API: 세션·CSRF 확인 → 통행권이 이 다리인지 확인 (아니면 403 BRIDGE_LOCKED)
    API->>API: 길이 · 기본 검사 · 운영자 규칙 (걸리면 422, 걸린 표현을 안내)
    API->>DB: 요청 키 기록 (같은 키 재전송이면 기존 편지 반환)
    API->>DB: 회원 행 잠금 (FOR NO KEY UPDATE)
    API->>DB: 오늘(KST) 이 다리 3통 · 전체 10통 확인 (넘으면 429)
    API->>DB: 편지 저장 (즉시 공개)
    API-->>U: 201 {id}
```

---

## 기술 스택

| 분류 | 사용 |
|---|---|
| 언어·프레임워크 | Java 17, Spring Boot 3.4 (Web, JDBC, Actuator) |
| DB | PostgreSQL 16, Flyway, HikariCP |
| 데이터 접근 | `JdbcTemplate`과 직접 쓴 SQL. 집계·잠금·인덱스를 SQL 수준에서 다루려고 ORM을 쓰지 않았습니다 |
| 인증·보안 | Argon2id(argon2-jvm), TOTP(HMAC-SHA1, 30초), AES-GCM, SHA-256 토큰 해시 |
| 관측 | Micrometer Prometheus, Grafana Alloy, Grafana Cloud |
| 테스트 | JUnit 5, Spring MockMvc, 실제 PostgreSQL 통합 테스트, k6 부하 테스트 |
| 인프라 | Docker, Docker Compose, AWS EC2 · S3 · CloudFront |

---

## 핵심 도메인과 정책

| 도메인 | 규칙 |
|---|---|
| **다리** | 봉림교 · 신림교 · 신림2교. 다리마다 추측할 수 없는 QR 열쇠가 있고, 운영자가 새로 발급하면 이전 QR과 통행권이 즉시 끊깁니다 |
| **주간 질문** | 다리별로 다른 질문을 미리 예약하면 **매주 월요일 00:00 KST**에 배포 없이 자동으로 바뀝니다. 새 질문이 없으면 이전 질문을 유지합니다 |
| **강물** | 그 다리의 현재 질문 편지 + 시작 편지만 흐릅니다. 회원 편지가 충분하면 시작 편지는 5통 중 최대 1통입니다. 무작위 후보 20통을 먼저 뽑고, 그 안에서 **안 본 편지에 가중치 20**을 줍니다. 편지가 많아져도 조회가 느려지지 않는 구조예요 |
| **편지 쓰기** | 1~150자, 즉시 공개. 유리병 1·2(기본), 5·6(업적으로 해금, 서버에서 `THEME_LOCKED`로 검사). 하루 **한 다리 3통 · 전체 10통**(KST 자정 초기화). 직접 지운 편지는 한도에서 빠집니다 |
| **비회원** | 다리마다 서로 다른 편지 5통까지 열람 |
| **위로·보관** | 회원·편지당 위로 하나(켜고 끄기), 보관은 별개 |
| **신고** | 서로 다른 회원 **5명**이 신고하면 임시 숨김. 운영자가 복원하면 처리된 신고로 다시 숨기지 않습니다. 숨겨진(검토 중) 편지는 작성자가 지울 수 없어, 지우고 다시 쓰는 식으로 검토를 피하지 못합니다 |
| **서랍장** | 이번 주·이번 달(KST) × 이 다리·전체 다리, **활성 위로 수** 기준 상위 10통. 순위는 10초 캐시, 숨김·삭제는 즉시 반영 |
| **업적** | 7개(첫 유리병, 이틀 밤의 안부, 세 다리의 안부, 처음 닿은 위로, 간직된 마음, 다정한 독자, 별빛 수집가). 한 번 켜진 별은 꺼지지 않습니다 |
| **계정** | 닉네임 + 비밀번호. 이메일 없이 **복구 코드**로 비밀번호를 재설정합니다. 탈퇴해도 쓴 편지와 서명은 남고 계정 연결만 끊깁니다 |
| **운영** | 질문·시작 편지·신고·문의·검사 규칙·QR·설문·지표·감사 기록. 모든 조치에 사유(2자 이상)를 남깁니다 |

---

## 데이터 모델

```mermaid
erDiagram
    bridges ||--o{ questions : "주간 질문"
    bridges ||--o{ letters : "강물"
    questions ||--o{ letters : "답한 편지"
    users ||--o{ letters : "작성 (탈퇴 시 연결 해제)"
    users ||--o{ sessions : "로그인"
    users ||--o{ reactions : "위로"
    users ||--o{ saves : "보관"
    users ||--o{ reports : "신고"
    users ||--o{ user_achievements : "업적"
    letters ||--o{ reactions : "받은 위로"
    letters ||--o{ saves : "보관됨"
    letters ||--o{ reports : "받은 신고"
    bridges ||--o{ bridge_passes : "QR 통행권 (해시)"

    bridges {
        text id PK
        text qr_key
        bool active
    }
    questions {
        uuid id PK
        text bridge_id FK
        timestamptz effective_at
        text status
    }
    letters {
        uuid id PK
        text bridge_id FK
        uuid question_id FK
        uuid author_user_id FK
        smallint theme
        bool is_seed
        text status
        int report_round
    }
    users {
        uuid id PK
        text nickname_key
        text password_hash
        text recovery_code_hash
        text role
        text status
    }
    reactions {
        uuid id PK
        uuid letter_id FK
        uuid user_id FK
        bool active
        timestamptz activated_at
    }
    reports {
        uuid id PK
        uuid letter_id FK
        int round
        text reason_code
        text status
    }
```

이 밖에 `inquiries`(비공개 문의), `moderation_rules`(운영자 검사 규칙), `metric_events`(실험 지표), `admin_actions`(감사 기록), `deletion_events`(삭제 기록), `request_keys`(중복 제출 방지), `auth_attempts`(속도 제한), `survey_campaign`(설문 설정)이 있습니다. 스키마 변경은 모두 `src/main/resources/db/migration`의 Flyway 마이그레이션(V001~V021)으로 남깁니다.

---

## API

모든 응답은 `{ "data": ... }` 또는 `{ "error": { "code", "message" } }` 형식입니다. 상태를 바꾸는 요청은 `x-csrf-token` 헤더가 필요합니다.

| 영역 | 주요 엔드포인트 |
|---|---|
| 공개 | `GET /bridges/{id}` · `GET /bridges/{id}/river?seen=` · `GET /letters/{id}` · `GET /best` · `GET /survey` · `POST /inquiries` · `POST /events` |
| QR 허가 | `POST /bridges/{id}/pass` · `GET /bridge-pass` |
| 계정 | `POST /auth/signup` · `POST /auth/login` · `POST /auth/logout` · `GET /auth/check-nickname` · `POST /auth/recover` · `POST /me/password` · `POST /me/recovery-code/rotate` · `POST /me/withdraw` |
| 회원 | `POST /bridges/{id}/letters` · `GET /bridges/{id}/letters/quota` · `PUT·DELETE /letters/{id}/reaction` · `PUT·DELETE /letters/{id}/save` · `POST /letters/{id}/reports` · `GET /me/saved` · `GET /me/letters` · `DELETE /me/letters/{id}` · `GET /me/letter-trace` · `GET /me/achievements` |
| 운영자 | `POST /admin/auth/login`(TOTP) · `/admin/questions` · `/admin/seeds` · `/admin/reports` · `/admin/letters` · `/admin/inquiries` · `/admin/moderation-rules` · `/admin/bridge-qr` · `/admin/survey` · `/admin/metrics` · `/admin/audit-actions` · `/admin/users/*` |

경로 앞에는 모두 `/api/v1`이 붙습니다.

---

## 보안 설계

| 위협 | 대응 |
|---|---|
| 비밀번호 유출 | **Argon2id** (메모리 19MiB, 반복 2, OWASP 권장값). 원문과 복구 코드 원문은 저장하지 않습니다. 동시 해시 수를 CPU 수로 제한해 메모리 폭주를 막습니다 |
| 세션 탈취 | 무작위 세션 토큰은 DB에 **SHA-256 해시로만** 저장합니다. 쿠키는 `HttpOnly`, `SameSite=Lax`, 30일 |
| CSRF | 세션마다 발급한 토큰을 `x-csrf-token` 헤더로 확인합니다. 상태를 바꾸는 요청은 `Origin`을 허용 목록과 대조합니다 |
| 운영자 계정 탈취 | 일반 가입으로 만들 수 없고 **비밀번호 + TOTP** 2단계. TOTP 비밀값은 **AES-GCM**으로 암호화해 저장합니다 |
| 무차별 대입·도배 | 로그인·복구·가입은 계정별로, 가입은 **같은 IP 1시간 5개**, 문의는 IP별로 제한합니다. CloudFront 뒤라 `X-Forwarded-For`를 정확히 **한 단계만** 믿고 실제 접속자 IP를 계산합니다 |
| 원격 편지 작성 | QR 열쇠로만 받는 3시간 통행권(쿠키, DB에는 해시)이 있어야 그 다리에 쓸 수 있습니다 |
| 중복 제출 | `idempotency-key` + 요청 본문 해시. 같은 키로 다시 보내면 같은 편지를 돌려줍니다 |
| 클라이언트 값 위조 | 문의 요청자 같은 계정 정보는 클라이언트가 보낸 값을 믿지 않습니다 |
| 노출 면적 | DB는 `127.0.0.1`, 지표 포트(9091)와 Alloy(12345)는 서버 안에서만 엽니다. 비밀 값은 서버의 `.env`(git 제외)에만 둡니다 |

---

## 내용 검사: 우회는 막고 멀쩡한 편지는 통과시키기

편지와 닉네임은 공개되므로 **기본 검사**(욕설, 연락처, 이메일, 링크·광고 문구)와 **운영자 검사 규칙**을 함께 거칩니다. 실제 운영에서 부딪힌 문제는 두 가지였습니다.

1. **우회 표기**: `씨 발`, `씨.발`, `씨1발`, `오픈 채팅`처럼 띄우거나 기호·숫자를 끼우면 그대로 통과했습니다.
2. **그렇다고 공백을 다 지우면 멀쩡한 편지가 막힙니다**: "다시 **발**견"은 `시발`, "솜씨 발휘"는 `씨발`, "또 라이브"는 `또라이`가 됩니다.

그래서 `ModerationText`에서 비교 방식을 나눴습니다.

| 우회 | 처리 |
|---|---|
| 기호·이모지 끼우기 (`씨.발`, `bit.ly`) | 기호를 지우고 비교 |
| 한글 사이 숫자 (`씨1발`) | 한글 사이에 낀 숫자만 지우고 비교 |
| 한 글자씩 띄우기 (`ㅅ ㅂ`, `개 새 끼`) | **한 글자짜리 조각끼리만** 붙여서 비교 (`다시 발견`은 붙이지 않음) |
| 자연스러운 띄어쓰기 (`오픈 채팅으로`, `미친 놈아`) | 3글자 이상 한글 규칙만, **낱말 처음에서 시작**하고 끝에는 **조사만** 붙은 경우 |
| 평범한 낱말 속 금지어 (`시발점`) | 예외 낱말로 먼저 제외 |

- **검증**: `ModerationFalsePositiveTest`가 운영 규칙 파일(`ops/moderation_rules_seed.sql`)을 직접 읽어, **평범한 편지 약 130문장**이 하나도 막히지 않고 **우회 표기 26개**는 모두 막히는지 확인합니다. 규칙을 추가할 때도 이 테스트로 오탐을 미리 확인합니다.
- **안내**: 걸리면 `'오픈채팅'처럼 광고·홍보로 보이는 표현이 들어 있어요. 그 부분을 고쳐 주세요. 다른 뜻으로 쓰셨다면 띄어쓰기나 표현을 조금 바꿔 주세요.`처럼 **무엇 때문인지와 해결 방법**을 함께 알려 줍니다.
- 운영자 규칙은 메모리에 1분 캐시하고, 운영자가 바꾸면 커밋 직후 즉시 다시 읽습니다.

---

## 부하 테스트와 트러블슈팅

출시 전 "동시 접속 50~100명"을 목표로 k6로 11가지 케이스를 측정했습니다. **회귀 테스트로 먼저 재현한 다음 고치는** 순서를 지켰습니다. 전체 과정과 수치는 [`docs/트러블슈팅_부하테스트.md`](./docs/트러블슈팅_부하테스트.md)에 있습니다.

| # | 케이스 | 결과 |
|---|---|---|
| 1 | 둘러보기 100명 | ✅ 실패 0%, p95 5.44ms |
| 2 | 동시 로그인 몰림 | 🐞 → ✅ 16명 동시 로그인에 **전원 30초 멈춤** → 48명까지 전원 성공 |
| 4 | 행사 시나리오(쓰기 포함 159명) | ✅ 25,221건, 402 req/s, 실패 0% |
| 5·6 | 편지 보내기·위로 버튼 연타 | ✅ 5번 동시 제출에 1통만 생성, 위로 중복 없음 |
| 7 | 한 회원이 같은 순간 여러 통 작성 | 🐞 → ✅ 하루 3통 제한이 **동시 6통에 뚫림** → 정확히 3통 |
| 10 | 운영 사양 흉내(2 vCPU · 메모리 제한, CPU 0.4개 최악) | 🐞 → ✅ 메모리 **1.2GB 한계·스왑** → 최고 239MiB |
| 11 | 데이터가 쌓인 뒤(한 달 · 한 달 ×3) | 🐞 → ✅ 한 달 ×3에서 **25 req/s · 30초 시간 초과** → 168 req/s · 실패 0% |
| — | 출시 직전 최종(운영과 같은 런타임 이미지) | ✅ **181 req/s**, 읽기+쓰기 220 req/s, **실패 0%** |

### 1. 동시 로그인이 사이트 전체를 30초 멈춤

요청 하나가 **커넥션 2개를 순서대로** 필요로 했습니다. 바깥 트랜잭션이 커넥션을 쥔 채 속도 제한용 별도 트랜잭션을 열었는데, 동시 요청이 풀(10개)을 다 차지하자 서로가 놓아 주기만 기다리며 30초 시간 초과까지 멈췄습니다. 별도 트랜잭션을 바깥보다 **먼저 끝내도록** 순서를 바꾸고, 해시 계산도 트랜잭션 밖으로 뺐습니다.

### 2. 하루 작성 제한이 동시 요청에 뚫림

"세고 → 저장"하는 사이에 다른 요청이 끼어드는 확인 후 실행(check-then-act) 경쟁이었습니다. 회원 행을 `FOR UPDATE`로 잠갔더니 이번에는 외래 키가 거는 `KEY SHARE` 잠금과 충돌해 교착이 났습니다. **`FOR NO KEY UPDATE`** 로 바꿔 같은 회원끼리만 줄을 세우고, 다른 회원끼리는 서로 막지 않게 했습니다.

### 3. 운영 사양에서만 드러난 비밀번호 해시 메모리 폭주

Argon2는 해시 하나에 19MB의 **JVM 힙 밖 네이티브 메모리**를 씁니다. 그리고 glibc 할당기가 해제된 큰 블록을 OS에 돌려주지 않아, 로그인 몰림이 반복될수록 메모리가 쌓였습니다. 할당기 설정과 동시 해시 수 제한을 **각각 켜고 끄며 4가지 조합을 비교**했고, 둘 다 적용했을 때만 2GB 서버에서 안정적이었습니다.

| | 기존 | 할당기만 | 동시 제한만 | **둘 다** |
|---|---|---|---|---|
| 몰림 5회 후 남은 메모리 | 1,089MiB | 172MiB | 1,087MiB | **159MiB** |
| 순간 최고 | 1,200 (한계) | 737MiB | 1,200 (한계) | **239MiB** |
| 스왑 | 345MiB | 0 | 258MiB | **0** |

### 4. 데이터가 쌓이면 서랍장 집계가 커넥션 풀을 막음

CPU는 낮은데 느렸습니다. `pg_stat_activity`를 보니 **디스크 임시 파일**(`BufFileRead/Write`)을 기다리고 있었습니다. 위로를 편지 본문과 먼저 조인한 뒤 묶다 보니 정렬 데이터가 `work_mem`을 넘어 디스크로 넘어갔고, 느린 집계가 풀을 차지해 나머지 요청이 30초 뒤 실패했습니다.
- **좁은 키(id, 수, 시각)로 먼저 집계하고 본문은 상위 후보에만** 붙였습니다.
- 순위만 10초 캐시하고, 숨김·삭제 여부는 매번 새로 읽습니다.
- 빠진 인덱스를 추가하고, 지표 화면의 테이블 읽기를 6번에서 1번으로 줄였습니다.

### 그 밖에 측정에서 배운 것

- 측정용 Docker 이미지가 운영과 다른 런타임(알파인/musl)이었던 것을 발견해 운영과 같은 이미지(glibc)로 맞췄습니다. musl에서는 위 3번의 할당기 설정이 적용되지 않습니다.
- 실패율을 믿기 전에 스크립트를 먼저 의심했습니다. 처음 58~60%로 나온 실패율은 모두 측정 스크립트 문제였고, 반복마다 쿠키가 초기화돼 세션이 사라지는 문제 등 **측정 함정 9건**을 정리했습니다. 이 과정에서 오히려 입력 검증, 중복 제출 방지, QR 통행권 정책이 부하 속에서도 정확히 동작한다는 걸 확인했습니다. 진짜 버그(2번)는 실패율 0%인 시나리오 안에 숨어 있다가, 엣지 케이스를 따로 설계해서야 드러났습니다.

---

## 모니터링

```mermaid
flowchart LR
    subgraph EC2["EC2 (서버 안)"]
        A1["Spring Actuator<br/>127.0.0.1:9091/actuator/prometheus<br/>JVM · HTTP · HikariCP"]
        A2["Alloy unix exporter<br/>CPU · 메모리 · 스왑 · 디스크 · 네트워크"]
        A3["Alloy postgres exporter<br/>연결 · 트랜잭션 · 캐시 적중률"]
        AL["Grafana Alloy"]
        A1 --> AL
        A2 --> AL
        A3 --> AL
    end
    AL -->|"remote_write (60초)"| P["Grafana Cloud Prometheus"]
    P --> D1["별빛안부 운영 한눈에<br/>(직접 만든 대시보드)"]
    P --> D2["Node Exporter Full"]
    P --> D3["JVM Micrometer"]
    P --> D4["PostgreSQL"]
    P --> R["알림 규칙 6개"] --> N["📧 메일 · 💬 디스코드"]
```

- **수집기 설정**: [`monitoring/config.alloy`](./monitoring/config.alloy). 접속 정보는 `.env`에서 받아요.
- **대시보드**: [`monitoring/starlight-overview.dashboard.json`](./monitoring/starlight-overview.dashboard.json)을 Grafana에서 가져오면 서비스 상태, 메모리, 스왑, 디스크, 5xx, p95와 요청·응답시간·JVM 힙·커넥션 풀·DB 그래프가 한 화면에 나와요. CPU 그래프에는 **t3.small 기준선(20%)** 을 표시해 CPU 크레딧이 줄어드는지 가늠할 수 있게 했습니다.
- **알림**: 서비스 다운(3분), 메모리 85% 초과, 스왑 300MB 초과, 디스크 80% 초과, 5xx 5% 초과, p95 1초 초과
- **비용 관리**: 응답시간은 전체 히스토그램 대신 구간 6개(50ms~2s)만 기록해 시계열을 아꼈습니다. 무료 플랜은 14일만 보관하므로 운영 중간과 종료 때 대시보드를 백업합니다.

---

## 배포

운영 서버에서는 `docker-compose.prod.yml` 하나로 세 컨테이너(backend, db, alloy)를 띄웁니다.

```bash
cd ~/backend && git pull
docker compose -f docker-compose.prod.yml up -d --build   # 시작할 때 Flyway가 마이그레이션 자동 적용
docker image prune -f && docker builder prune -f            # 빌드 찌꺼기 정리 (볼륨은 지우지 않음)
```

서버의 `.env`(git 제외)에 필요한 값:

| 값 | 용도 |
|---|---|
| `DB_PASSWORD` | PostgreSQL |
| `ADMIN_TOTP_KEY` · `ADMIN_NICKNAME` · `ADMIN_PASSWORD` | 운영자 계정과 TOTP 비밀값 암호화 키(Base64 32바이트) |
| `GRAFANA_CLOUD_PROM_URL` · `GRAFANA_CLOUD_PROM_USER` · `GRAFANA_CLOUD_PROM_TOKEN` | 지표 전송 (`metrics:write` 권한 토큰) |
| `POSTGRES_MONITOR_PASSWORD` | Alloy가 쓰는 `pg_monitor` 읽기 전용 DB 계정 |

### 운영 스크립트 (`ops/`)

| 파일 | 용도 |
|---|---|
| `launch_reset_test_data.sql` | 질문·시작 편지·검사 규칙·운영자는 남기고 테스트 데이터만 지웁니다. 기본은 미리보기(ROLLBACK)이고, `-v apply=1`일 때만 적용합니다 |
| `moderation_rules_seed.sql` | 오탐 검증을 통과한 검사 규칙 70개를 한 번에 넣습니다. 이미 있는 문구는 건너뜁니다 |

```bash
docker exec starlight-postgres pg_dump -U postgres starlight > ~/backup.sql                 # 항상 먼저 백업
docker exec -i starlight-postgres psql -U postgres -d starlight < ops/launch_reset_test_data.sql   # 미리보기
```

### 운영자 계정

운영자는 일반 가입으로 만들 수 없습니다.

```bash
ADMIN_NICKNAME=운영자 ADMIN_PASSWORD='안전한비밀번호' \
./gradlew bootRun --args='--starlight.provision-admin=true'
```

콘솔에 한 번만 출력되는 TOTP 비밀값을 인증 앱에 등록합니다. 인증 앱을 잃어버렸다면 같은 `ADMIN_TOTP_KEY`로 `--starlight.reset-admin-totp=true`를 실행합니다. 이때 기존 운영자 세션은 모두 폐기됩니다.

---

## 로컬 실행과 테스트

```bash
cp .env.example .env          # JDBC URL, 계정, ADMIN_TOTP_KEY
./gradlew bootRun             # http://127.0.0.1:3000, 시작할 때 Flyway 적용
```

프론트엔드는 `VITE_API_TARGET=http://127.0.0.1:3000`으로 실행하고, 그 주소를 `APP_ALLOWED_ORIGINS`에 넣습니다.

```bash
docker compose -f docker-compose.test.yml up -d --wait
TEST_DATABASE_URL=postgresql://starlight:starlight_test_password@127.0.0.1:54329/starlight_test ./gradlew test
```

- 통합 테스트는 테스트마다 새 스키마를 만들어 실제 PostgreSQL에서 돕니다.
- `AuthConcurrencyIntegrationTest`, `LetterLimitConcurrencyIntegrationTest`는 위 트러블슈팅 1·2번을 **수정 전 코드에서 실패하는 것을 먼저 확인한** 회귀 테스트입니다.
- 부하 테스트 재현: [`loadtest/README.md`](./loadtest/README.md). `./setup.sh 1 && ./run.sh 1`로 운영 사양을 흉내 낸 환경에서 다시 잴 수 있습니다.

---

## 프로젝트 구조

```
src/main/java/com/starlight/greetings/
├─ web/            # 컨트롤러, 쿠키·CSRF·Origin·클라이언트 IP, 오류 응답 형식
├─ application/    # 서비스와 SQL (강물, 편지 쓰기, 참여, 신고, 계정, 운영, 지표, QR 통행권, 업적)
├─ domain/
│  ├─ auth/        # 비밀번호 해시(Argon2id), 가입 검증
│  └─ moderation/  # 기본 검사, 우회 표기 비교(ModerationText), 안내 문구
└─ operations/     # 운영자 계정 생성, TOTP 재설정
src/main/resources/db/migration/   # Flyway V001~V021
monitoring/        # Alloy 설정, Grafana 대시보드
ops/               # 운영 SQL 스크립트
loadtest/          # k6 시나리오, 운영 사양 흉내 환경
docs/              # 부하 테스트·트러블슈팅 기록
```

---

## 한계와 다음 단계

정직하게 남겨 둔 것들입니다.

- **DB 자동 백업이 없습니다.** 지금은 손으로 `pg_dump`합니다. EBS 스냅샷 자동화나 `pg_dump` → S3 cron이 필요합니다.
- **오래된 데이터 정리 작업이 없습니다.** 세션, 로그인 시도, 통행권, 방문 기록이 쌓이기만 합니다. 한 달 분량(약 150MB)에서는 문제가 없음을 확인해 실험 기간에는 넣지 않았습니다.
- **서버 한 대 기준 설계입니다.** 서랍장 순위 캐시와 검사 규칙 캐시가 서버 메모리에 있어서, 여러 대로 늘리면 공유 캐시가 필요합니다.
- **서버에서 직접 이미지를 빌드합니다.** 빌드 중에 서비스와 메모리를 나눠 씁니다. CI 빌드와 이미지 레지스트리로 옮길 수 있습니다.
- 세션 쿠키 이름(`star_session_dev`)을 정리하고, 쿠키에 `Secure` 속성을 추가할 예정입니다.
- 삼성 인터넷의 강제 다크 모드는 사이트에서 끌 수 없어(표준 `color-scheme` 선언 무시), 문의 화면의 자주 묻는 질문으로 안내합니다.
