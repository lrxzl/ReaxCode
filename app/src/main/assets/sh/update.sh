#!/bin/bash
# ============================================
# 手动更新脚本：从 git 强制拉取最新代码
# 用法：bash update.sh
# ============================================

set -u

PROJECT_DIR="${HOME}/node-servers/pro-manager"
TARGET_BRANCH="${TARGET_BRANCH:-dev}"
REPO_URL="${REPO_URL:-https://gitee.com/lrxzlcn/pro-manager.git}"
LOG_FILE="${HOME}/update.log"

exec > "$LOG_FILE" 2>&1
exec < /dev/null

export GIT_TERMINAL_PROMPT=0

echo "=== update.sh 开始 $(date) ==="

# 若不是 git 仓库，先 clone
if [ ! -d "$PROJECT_DIR/.git" ]; then
    echo "[update] $PROJECT_DIR 不是 git 仓库，执行 clone..."
    rm -rf "$PROJECT_DIR"
    timeout 300 git clone -b "$TARGET_BRANCH" "$REPO_URL" "$PROJECT_DIR" 2>&1 || {
        echo "[update][错误] git clone 失败"
        exit 1
    }
    cd "$PROJECT_DIR" || exit 1
else
    cd "$PROJECT_DIR" || exit 1
    echo "[update] 当前分支: $(git branch --show-current 2>/dev/null)"

    echo "[update] 拉取远程更新..."
    timeout 120 git fetch origin 2>&1 || {
        echo "[update][错误] git fetch 失败"
        exit 1
    }

    REMOTE_HASH=$(git rev-parse "origin/$TARGET_BRANCH" 2>/dev/null)
    if [ -z "$REMOTE_HASH" ]; then
        echo "[update][错误] 无法获取 origin/$TARGET_BRANCH"
        git branch -r
        exit 1
    fi

    echo "[update] 强制切换到 $TARGET_BRANCH 并覆盖本地改动..."
    git checkout "$TARGET_BRANCH" 2>&1 || true
    git reset --hard "origin/$TARGET_BRANCH" 2>&1 || {
        echo "[update][错误] git reset --hard 失败"
        exit 1
    }
    git clean -fd 2>&1 || true
fi

LOCAL_HASH=$(git rev-parse HEAD 2>/dev/null)
echo "[update] 完成 $(date)，当前版本: ${LOCAL_HASH:0:7}"
echo "[update] 提示：重启 App 或重新执行 pro-manager/startup.sh 让新代码生效"
