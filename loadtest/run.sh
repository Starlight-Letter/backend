#!/bin/sh
# 사용법: run.sh <scale>  — 백엔드 새로 띄우기 → 20초 예열 → 60초 측정 → 메모리 사용량
# 결과 요약은 화면에, 전체 지표는 scale<scale>.json에 남는다. 같은 조건을 두 번 재서 비교한다(첫 회차는 잡음이 섞이기 쉽다).
set -e
cd "$(dirname "$0")"
./run-backend.sh
k6 run -q -e DUR=20s -e OUT=warm month.js >/dev/null 2>&1 || true
k6 run -q -e OUT=scale$1 month.js 2>&1 | grep -v '^time='
docker stats --no-stream --format "{{.Name}} {{.MemUsage}}" sl-lt-be sl-lt-db
