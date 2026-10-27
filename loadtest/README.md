# 부하 테스트 (데이터 규모별)

`docs/트러블슈팅_부하테스트.md` 케이스 11의 측정을 다시 돌리는 스크립트다. 운영 사양(EC2 t3.small)을 Docker로 흉내 낸다.

## 준비물
- Docker Desktop, [k6](https://k6.io) (`k6` 명령이 PATH에 있어야 함)
- Windows에서는 Git Bash에서 실행한다.

## 실행
```sh
cd backend/loadtest
./setup.sh 1      # 환경 만들기 + 한 달 분량 데이터 (0 = 출시 전, 3 = 한 달 x3)
./run.sh 1        # 읽기 위주 측정(한 번 더 돌려 두 결과를 비교)
./run.sh 1
./run-mixed.sh 1  # 읽기 + 쓰기(편지 띄우기·닉네임 확인)를 함께
```

| 파일 | 역할 |
|---|---|
| `setup.sh` | DB 컨테이너·네트워크 생성, 현재 코드로 백엔드 이미지 빌드, 마이그레이션 후 `seed.sql`로 데이터 넣기 |
| `run.sh` | 백엔드를 새로 띄우고 20초 예열 뒤 60초 측정 |
| `run-mixed.sh` | `month.js`와 `write.js`를 같은 60초 동안 함께. 앞 회차 편지를 하루 전으로 옮겨 이번에도 실제 저장(201)이 일어나게 한다 |
| `run-backend.sh` | 측정용 백엔드 컨테이너만 다시 띄우기 |
| `seed.sql` | 규모별 데이터(`-v scale=0/1/3`). 고정 토큰 세션 `lt-member-N`, 운영자 `lt-admin` 포함 |
| `month.js` | k6 시나리오: 비회원 100명 + 회원 50명 + 운영자 1명 |
| `write.js` | k6 시나리오: 회원 35명이 QR 통행권을 받아 편지를 띄우고(하루 한도 429는 정상), 10명이 닉네임 확인(쓸 수 있는 이름·사칭 이름). `noCookiesReset`으로 통행권 쿠키를 유지해야 한다 |
| `explain.sql` | 주요 쿼리 실행 계획(`docker exec -i sl-lt-db psql -U postgres -d starlight < explain.sql`) |
| `Dockerfile.loadtest` | 운영 Dockerfile과 같은 런타임(`17-jre`, glibc)이고, Windows 체크아웃의 `gradlew` 줄바꿈(CRLF)만 고친다 |

## 조건 바꾸기
- **CPU 크레딧 소진(vCPU 0.4개)**: `docker update --cpus=0.1 sl-lt-db` 후 `BECPU=0.3 ./run.sh 1`. 끝나면 `docker update --cpus=2 sl-lt-db`로 되돌린다(`--cpus=0`은 조용히 실패한다).
- **편지를 많이 쓴 회원**: `seed.sql` 뒤에 아래를 실행하면 `lt-member-1`이 편지 300통을 가진 회원이 된다.
  ```sql
  UPDATE letters SET author_user_id = '00000000-0000-0000-0001-000000000001'
  WHERE id IN (SELECT id FROM letters WHERE is_seed = false AND status <> 'deleted' ORDER BY random() LIMIT 300);
  ```

## 정리
```sh
docker rm -f sl-lt-be sl-lt-db && docker network rm sl-lt && docker rmi starlight-loadtest:latest
```
