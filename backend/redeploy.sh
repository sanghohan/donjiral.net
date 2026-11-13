#!/usr/bin/env bash
# 최신 소스를 내려받아 백엔드를 다시 빌드·실행합니다.
# 이 파일은 홈(~)에 복사해두고 쓰세요:  bash ~/redeploy.sh
#   curl -fL https://raw.githubusercontent.com/sanghohan/donjiral.net/main/backend/redeploy.sh -o ~/redeploy.sh
# (소스 폴더는 매번 새로 받으므로, 데이터는 run.sh가 ~/pingpong-data 에 따로 보관합니다.)
#
# 2026-11 저장소 이름을 roulette → donjiral.net 으로 바꿨다. 예전 스크립트가 쓰던 ~/roulette-main 은 건드리지 않는다
# (Caddy 가 그 안의 Caddyfile 을 물고 있을 수 있음 — docker inspect caddy 로 확인한 뒤에 지울 것).
set -e
cd "$HOME"

REPO=sanghohan/donjiral.net
SRC=donjiral.net-main

echo "▶ 최신 소스 내려받기..."
curl -fL "https://github.com/$REPO/archive/refs/heads/main.tar.gz" -o donjiral.net.tar.gz
rm -rf "$SRC"
mkdir "$SRC"
# 압축 안 최상위 폴더 이름(저장소이름-브랜치)에 기대지 않도록 한 겹 벗겨서 푼다
tar xzf donjiral.net.tar.gz -C "$SRC" --strip-components=1

echo "▶ 빌드 & 실행..."
cd "$SRC/backend"
bash run.sh
