#!/bin/bash
# ============================================
# reax 统一启动脚本
# 负责依次启动 node-tools 和 pro-manager
# 由 TermuxStartupHelper.java 在 App 启动时调用
# ============================================

set -u
NOTIFY_LOG="${HOME}/reax/common.log"
mkdir -p "$(dirname "$NOTIFY_LOG")" 2>/dev/null
exec > >(tee -a "$NOTIFY_LOG") 2>&1

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "[reax] ===== reax 统一启动开始 ====="
echo "[reax] 1/2 启动 reaxcode..."
nohup bash "${SCRIPT_DIR}/reaxcode/startup.sh" < /dev/null >> "${SCRIPT_DIR}/reaxcode-startup.log" 2>&1 &

# 2. 启动 pro-manager（已禁用）
echo "[reax] 2/2 启动 pro-manager..."
nohup bash "${SCRIPT_DIR}/pro-manager/startup.sh" < /dev/null >> "${SCRIPT_DIR}/pro-manager-startup.log" 2>&1 &
PRO_MANAGER_PID=$!
echo "[reax] pro-manager 已派发 (PID: $PRO_MANAGER_PID)"

echo "[reax] ===== reax 统一启动完成 ====="
