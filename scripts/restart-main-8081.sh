#!/usr/bin/env bash
# 仅重启现网 :8081，不杀 wallet-node.jar / :8093
# 现网由 systemd 单元 99chat-main.service 托管（Restart=on-failure），本脚本只是 systemctl 的封装。
# 请勿再用 nohup 手工拉起 8081：systemd 实例会因端口占用无限重启，空耗 CPU / 内存。
set -euo pipefail
cd "$(dirname "$0")/.."
JAR="${JAR:-target/server-0.0.1-SNAPSHOT.jar}"
if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR" >&2
  exit 1
fi

echo "restarting 99chat-main.service ..."
sudo systemctl restart 99chat-main.service

for _ in $(seq 1 90); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/auth/login || true)
  if [[ -n "$code" && "$code" != "000" ]]; then
    echo ":8081 ready (http $code)"
    break
  fi
  sleep 1
done

sudo systemctl --no-pager --lines=5 status 99chat-main.service
