#!/usr/bin/env bash
# 탁구 백엔드 빌드 & 실행 스크립트
# 사용법: bash run.sh
set -e
cd "$(dirname "$0")"

# 비밀값(관리자 암호 등)은 git에 올리지 않고 ~/pingpong.env 에 보관
#   예) echo "export ADMIN_SECRET='나만아는암호'" > ~/pingpong.env
[ -f "$HOME/pingpong.env" ] && source "$HOME/pingpong.env"

IMAGE=pingpong-api
NAME=pingpong-api
# H2 데이터는 소스 폴더 밖(홈)에 저장해서 재배포해도 안 지워지게 함
DATA_DIR="${DATA_DIR:-$HOME/pingpong-data}"

mkdir -p "$DATA_DIR"

echo "▶ 이미지 빌드 (몇 분 걸릴 수 있어요)..."
docker build -t "$IMAGE" .

echo "▶ 기존 컨테이너 정리..."
docker rm -f "$NAME" 2>/dev/null || true

# 비밀값만 환경변수로 전달. 나머지 설정은 application.yml(소스)에 있음.
echo "▶ 컨테이너 실행..."
docker run -d --name "$NAME" --restart unless-stopped \
  -p 8080:8080 \
  -v "$DATA_DIR:/app/data" \
  -e ADMIN_SECRET="${ADMIN_SECRET:-}" \
  -e MASKFALL_APP_KEY="${MASKFALL_APP_KEY:-}" \
  "$IMAGE"

echo ""
echo "✅ 실행 완료!"
echo "   데이터 위치 : $DATA_DIR"
echo "   로그 보기   : docker logs -f $NAME"
echo "   헬스 체크   : curl http://localhost:8080/api/health"
