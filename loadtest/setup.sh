#!/bin/sh
# 사용법: setup.sh <scale>  — 운영 사양(t3.small) 흉내 환경을 새로 만들고 데이터를 넣는다
#   scale=0 : 출시 전 수준(편지 67통, 방문 기록 약 4천 건)
#   scale=1 : 한 달 운영 분량, scale=3 : 한 달 x3
# 백엔드·DB가 CPU 2개(0,1번)를 나눠 쓰고, 백엔드 1.2GB(힙 512MB)·DB 512MB로 제한한다.
set -e
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1 # Git Bash가 컨테이너 안 경로(/seed.sql)를 윈도우 경로로 바꾸지 않게

docker rm -f sl-lt-be sl-lt-db >/dev/null 2>&1 || true
docker network create sl-lt >/dev/null 2>&1 || true
docker run -d --name sl-lt-db --network sl-lt --cpuset-cpus=0,1 --memory=512m --memory-swap=1g \
  -e POSTGRES_DB=starlight -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=lt postgres:16-alpine >/dev/null
until docker exec sl-lt-db pg_isready -U postgres -d starlight >/dev/null 2>&1; do sleep 1; done

# 지금 백엔드 코드로 이미지를 만든다(빌드 문맥은 backend 폴더)
docker build -q -f Dockerfile.loadtest -t starlight-loadtest:latest ..

# 백엔드를 한 번 띄워 마이그레이션을 적용한 뒤, 그 스키마에 데이터를 넣는다
./run-backend.sh
docker cp seed.sql sl-lt-db:/seed.sql
docker exec sl-lt-db psql -U postgres -d starlight -q -v scale="${1:-1}" -f /seed.sql
echo "준비 완료: ./run.sh ${1:-1} 로 측정"
