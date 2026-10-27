#!/bin/sh
# 사용법: run-mixed.sh <scale>  — 읽기(month.js)와 쓰기(write.js)를 같은 60초 동안 함께 건다
# 앞 회차에서 쓴 편지는 하루 전으로 옮겨, 이번 회차에도 편지가 실제로 저장되는 경로(201)를 지나게 한다.
set -e
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1
docker exec sl-lt-db psql -U postgres -d starlight -q -c "UPDATE letters SET created_at = created_at - interval '1 day' WHERE body LIKE '부하 테스트 편지%' AND created_at > now() - interval '1 day'"
./run-backend.sh
k6 run -q -e DUR=20s -e OUT=warm month.js >/dev/null 2>&1 || true
k6 run -q -e OUT=write$1 write.js > write-result.txt 2>&1 &
writer=$!
k6 run -q -e OUT=scale$1 month.js 2>&1 | grep -v '^time='
wait $writer || true
grep -v '^time=' write-result.txt
docker stats --no-stream --format "{{.Name}} {{.MemUsage}}" sl-lt-be sl-lt-db
