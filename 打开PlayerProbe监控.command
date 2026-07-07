#!/usr/bin/env bash
set -euo pipefail

PORT="${PLAYERPROBE_PORT:-8765}"
RADIUS="${PLAYERPROBE_RADIUS:-2}"
INTERVAL_MS="${PLAYERPROBE_INTERVAL_MS:-1000}"
BASE_URL="http://127.0.0.1:${PORT}"

clear
echo "PlayerProbe Minecraft Mod GET Monitor"
echo
echo "1. 请先把 finalMod/playerprobe-1.0.0.jar 放进 Minecraft mods 目录。"
echo "2. 启动 26.1.2-Fabric，并进入一个世界。"
echo "3. 这个窗口会自动等待 mod 的 HTTP 服务，然后持续输出 /watch 数据。"
echo
echo "监听地址: ${BASE_URL}"
echo "数据接口: ${BASE_URL}/watch?radius=${RADIUS}&intervalMs=${INTERVAL_MS}"
echo

while true; do
  if curl -fsS "${BASE_URL}/health" >/tmp/playerprobe-health.json 2>/dev/null; then
    echo "已连接到 PlayerProbe:"
    cat /tmp/playerprobe-health.json
    echo
    echo "开始持续读取。按 Control-C 停止。"
    echo
    curl -N "${BASE_URL}/watch?radius=${RADIUS}&intervalMs=${INTERVAL_MS}"
    echo
    echo "连接断开，3 秒后重试..."
    sleep 3
  else
    echo "等待 Minecraft / PlayerProbe 启动中... $(date '+%H:%M:%S')"
    sleep 2
  fi
done
