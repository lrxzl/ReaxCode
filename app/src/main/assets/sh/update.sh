#!/bin/bash
# ============================================
# 手动更新脚本：从 git 拉取最新代码并安装依赖
# 不在自动启动流程中调用，仅手动执行：bash update.sh
# ============================================

set -u

PROJECT_DIR="${HOME}/node-servers/pro-manager"
TARGET_BRANCH="${TARGET_BRANCH:-dev}"
REPO_URL="${REPO_URL:-https://gitee.com/lrxzlcn/pro-manager.git}"
LOG_FILE="${HOME}/update.log"

exec > "$LOG_FILE" 2>&1
exec < /dev/null

export DEBIAN_FRONTEND=noninteractive
export GIT_TERMINAL_PROMPT=0
export npm_config_yes=true

echo "=== update.sh 开始 $(date) ==="

# 若不是 git 仓库，先 clone（保留原有非 git 文件会被覆盖）
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
    timeout 120 git fetch origin 2>&1

    LOCAL_HASH=$(git rev-parse HEAD 2>/dev/null)
    REMOTE_HASH=$(git rev-parse "origin/$TARGET_BRANCH" 2>/dev/null)

    if [ -z "$REMOTE_HASH" ]; then
        echo "[update][错误] 无法获取 origin/$TARGET_BRANCH"
        git branch -r
        exit 1
    fi

    if [ "$LOCAL_HASH" = "$REMOTE_HASH" ]; then
        echo "[update] 已是最新版本 (${LOCAL_HASH:0:7})"
        exit 0
    fi

    echo "[update] 发现新代码: ${LOCAL_HASH:0:7} → ${REMOTE_HASH:0:7}"
    git checkout . 2>&1 || true
    git checkout "$TARGET_BRANCH" 2>&1 || true
    git reset --hard "origin/$TARGET_BRANCH" 2>&1
    git clean -fd 2>&1 || true
fi

# 安装依赖
echo "[update] 安装根目录依赖..."
[ -f package.json ] && npm install --no-audit --no-fund 2>&1

if [ -d "server" ] && [ -f "server/package.json" ]; then
    echo "[update] 安装 server 依赖..."
    (cd server && npm install --no-audit --no-fund 2>&1)
fi

# 前端构建（仅当不存在 server/html/ 时）
if [ ! -d "server/html" ] && [ -d "web" ] && [ -f "web/package.json" ]; then
    echo "[update] 构建 web 前端..."
    (
        cd web
        npm install --no-audit --no-fund --ignore-scripts 2>&1
        node node_modules/esbuild/install.js 2>&1 || true
        NODE_OPTIONS=--no-experimental-detect-module node node_modules/vite/bin/vite.js build 2>&1
    ) || echo "[update] ⚠️ 前端构建失败"
fi

# 删除 deps marker，强制下次启动重装依赖
rm -f "$PROJECT_DIR/.deps-installed" 2>/dev/null

echo "[update] 完成 $(date)"
echo "[update] 提示：重启 App 或重新执行 pro-manager/startup.sh 让新代码生效"
