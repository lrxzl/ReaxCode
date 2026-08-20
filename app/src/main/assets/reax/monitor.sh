#!/bin/bash
# ============================================================
# ReaX 服务守护脚本
# 功能：持续监控 reaxcode(8089) 和 pro-manager(3456) 服务，
#       发现服务宕掉后自动拉起，每 10 秒检查一次
# 使用：bash monitor.sh          # 启动守护（已在运行则跳过）
# ============================================================

NOTIFY_LOG="${HOME}/reax/monitor.log"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOCK_FILE="${SCRIPT_DIR}/monitor.pid"

# 日志函数
log() {
  #echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "${NOTIFY_LOG}"
}

# 确保单实例运行
if [ -f "${LOCK_FILE}" ] && kill -0 "$(cat "${LOCK_FILE}")" 2>/dev/null; then
  log "monitor.sh 已在运行 (PID=$(cat "${LOCK_FILE}"))，退出..."
  exit 0
fi
echo $$ > "${LOCK_FILE}"
trap 'rm -f "${LOCK_FILE}"' EXIT

log "========== ReaX 服务守护启动 (PID=$$) =========="

# 检查端口是否监听
check_port() {
  node -e "process.exit(require('net').createConnection($1,'127.0.0.1').on('error',()=>process.exit(1)).on('connect',()=>process.exit(0)).setTimeout(1000).on('timeout',()=>process.exit(1)))" 2>/dev/null
}

# 主守护循环
while true; do
  # 检查 8089 端口（reaxcode）
  if ! check_port 8089; then
    log "[WARN] 8089 (reaxcode) 未监听，正在拉起..."
    nohup bash "${SCRIPT_DIR}/reaxcode/startup.sh" < /dev/null >> "${SCRIPT_DIR}/reaxcode-resume.log" 2>&1 &
  fi

  # 检查 3456 端口（pro-manager）
  if ! check_port 3456; then
    log "[WARN] 3456 (pro-manager) 未监听，正在拉起..."
    nohup bash "${SCRIPT_DIR}/pro-manager/startup.sh" < /dev/null >> "${SCRIPT_DIR}/pro-manager-resume.log" 2>&1 &
  fi

  # 每 10 秒检查一次
  sleep 10
done
