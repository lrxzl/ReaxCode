#!/bin/bash

npx reaxcode@latest -y

##!/bin/bash
## ============================================
## node-tools 服务启动脚本（自包含、幂等）
## 仅做：检查 pid → 启动 node server.js
## ============================================
#
#set -u
#
#SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
#cd "$SCRIPT_DIR" || exit 1
#
#PID_FILE="${SCRIPT_DIR}/.node-tools.pid"
#LOG_FILE="${SCRIPT_DIR}/server.log"
#
## 1. 已在运行则跳过
#if [ -f "$PID_FILE" ]; then
#    OLD_PID=$(cat "$PID_FILE" 2>/dev/null)
#    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
#        echo "[node-tools] 已在运行 (PID: $OLD_PID)，跳过"
#        exit 0
#    fi
#fi
#rm -f "$PID_FILE"
#
## 2. 等待 node 就绪（init_env.sh 通常已让 node 就绪，最多等 30 秒兜底）
#for i in $(seq 1 30); do
#    command -v node &> /dev/null && break
#    sleep 1
#    [ $i -eq 30 ] && { echo "[node-tools][错误] node 未就绪"; exit 1; }
#done
#
## 3. 启动 server.js
#echo "[node-tools] 启动 server.js..."
#nohup node "${SCRIPT_DIR}/server.js" < /dev/null >> "$LOG_FILE" 2>&1 &
#NEW_PID=$!
#echo "$NEW_PID" > "$PID_FILE"
#
## 4. 健康检查：1 秒后进程还活着
#sleep 1
#if kill -0 "$NEW_PID" 2>/dev/null; then
#    echo "[node-tools] 启动成功 (PID: $NEW_PID)"
#    exit 0
#else
#    echo "[node-tools][错误] 进程已退出，请查看 $LOG_FILE"
#    rm -f "$PID_FILE"
#    exit 1
#fi
