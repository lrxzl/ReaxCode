#!/bin/bash
# ============================================
# Git 环境初始化（异步后台，幂等）
# ============================================

set -u
NOTIFY_LOG="${HOME}/reax/common.log"
mkdir -p "$(dirname "$NOTIFY_LOG")" 2>/dev/null
exec > >(tee -a "$NOTIFY_LOG") 2>&1

MARKER_FILE="${HOME}/.git-initialized"

if [ -f "$MARKER_FILE" ] && command -v git &> /dev/null; then
    echo "[git] 已初始化: $(git --version | head -1)，跳过"
    exit 0
fi

echo "[git] 检测 Git..."

if command -v git &> /dev/null; then
    echo "[git] Git 已存在: $(git --version | head -1)"
    echo "ok" > "$MARKER_FILE"
    exit 0
fi

echo "[git] 安装 Git..."
pkg update -y > /dev/null 2>&1
pkg install git -y 2>&1

if command -v git &> /dev/null; then
    echo "[git] 安装完成: $(git --version | head -1)"
    echo "ok" > "$MARKER_FILE"
    exit 0
else
    echo "[git][错误] Git 安装失败"
    exit 1
fi
