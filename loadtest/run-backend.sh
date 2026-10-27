#!/bin/sh
# 측정용 백엔드 컨테이너를 새로 띄우고 health가 응답할 때까지 기다린다.
# IMG=<태그>로 다른 이미지를, BECPU=0.3처럼 CPU 상한(크레딧 소진 흉내)을 줄 수 있다.
set -e
docker rm -f sl-lt-be >/dev/null 2>&1 || true
docker run -d --name sl-lt-be --network sl-lt --cpuset-cpus=0,1 ${BECPU:+--cpus=$BECPU} --memory=1200m --memory-swap=2g \
  -p 127.0.0.1:38080:3000 \
  -e JDBC_DATABASE_URL=jdbc:postgresql://sl-lt-db:5432/starlight -e DATABASE_USERNAME=postgres -e DATABASE_PASSWORD=lt \
  -e APP_ALLOWED_ORIGINS=http://localhost:5173 -e MALLOC_ARENA_MAX=2 -e MALLOC_MMAP_THRESHOLD_=131072 \
  starlight-loadtest:${IMG:-latest} >/dev/null
until curl -sf http://127.0.0.1:38080/api/v1/health >/dev/null; do sleep 1; done
