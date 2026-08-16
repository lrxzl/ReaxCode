#!/bin/bash
# ============================================
# Node.js 环境初始化（同步，幂等）
# ============================================

set -u
NOTIFY_LOG="${HOME}/reax/common.log"
mkdir -p "$(dirname "$NOTIFY_LOG")" 2>/dev/null
exec > >(tee -a "$NOTIFY_LOG") 2>&1

MARKER_FILE="${HOME}/.node-initialized"

# marker 存在 且 node 可用 → 直接跳过
if [ -f "$MARKER_FILE" ] && command -v node &> /dev/null; then
    echo "[node] 已初始化: $(node -v)，跳过"
    exit 0
fi

echo "[node] 检测 Node.js..."

if command -v node &> /dev/null; then
    echo "[node] Node.js 已存在: $(node -v)"
    echo "ok" > "$MARKER_FILE"
    exit 0
fi

echo "[node] 安装 Node.js..."
# 先确保镜像源正确（幂等）
if [ -f "${PREFIX:-/data/data/com.termux/files/usr}/etc/apt/sources.list" ]; then
    bash "$(dirname "$0")/init_mirrors.sh" 2>&1 | tail -3
fi
timeout 300 pkg update -y > /dev/null 2>&1 || echo "[node][警告] pkg update 失败或超时，继续尝试安装"
timeout 300 pkg install nodejs -y 2>&1 || echo "[node][错误] pkg install 超时或失败"

# 若安装失败，清理缓存后重试一次
if ! command -v node &> /dev/null; then
    echo "[node] 第一次安装失败，清理缓存重试..."
    pkg clean -y 2>/dev/null
    timeout 300 pkg install nodejs -y 2>&1
fi

if command -v node &> /dev/null; then
    echo "[node] 安装完成: $(node -v)"
    echo "ok" > "$MARKER_FILE"
    exit 0
else
    echo "[node][错误] Node.js 安装失败"
    exit 1
fi
