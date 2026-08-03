#!/bin/bash
# ============================================
# Node.js 环境初始化（同步，幂等）
# ============================================

set -u

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
pkg update -y > /dev/null 2>&1
pkg install nodejs -y 2>&1

if command -v node &> /dev/null; then
    echo "[node] 安装完成: $(node -v)"
    echo "ok" > "$MARKER_FILE"
    exit 0
else
    echo "[node][错误] Node.js 安装失败"
    exit 1
fi
