#!/bin/bash
# 检查并恢复服务：8089(reaxcode) 3456(pro-manager)
# 由 TermuxStartupHelper 定时调用（每10秒一次）

NOTIFY_LOG="${HOME}/reax/common.log"
mkdir -p "$(dirname "$NOTIFY_LOG")" 2>/dev/null
exec > >(tee -a "$NOTIFY_LOG") 2>&1

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 检查端口是否监听
check_port() {
  node -e "process.exit(require('net').createConnection($1,'127.0.0.1').on('error',()=>process.exit(1)).on('connect',()=>process.exit(0)).setTimeout(1000).on('timeout',()=>process.exit(1)))" 2>/dev/null
}

# 检查 8089 端口（reaxcode / node-tools）
if ! check_port 8089; then
  echo "[resume] 8089 端口未监听，正在启动 reaxcode..."
  nohup bash "${SCRIPT_DIR}/reaxcode/startup.sh" < /dev/null >> "${SCRIPT_DIR}/reaxcode-resume.log" 2>&1 &
else
  echo "[resume] reaxcode (8089) 运行正常"
fi

# 检查 3456 端口（pro-manager）
if ! check_port 3456; then
  echo "[resume] 3456 端口未监听，正在启动 pro-manager..."
  nohup bash "${SCRIPT_DIR}/pro-manager/startup.sh" < /dev/null >> "${SCRIPT_DIR}/pro-manager-resume.log" 2>&1 &
else
  echo "[resume] pro-manager (3456) 运行正常"
fi

