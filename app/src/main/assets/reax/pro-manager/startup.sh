#!/bin/bash
log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}
# ============================================
# pro-manager 服务启动脚本（自包含、幂等）
#   - 检查 pid，已运行则跳过
#   - 检查依赖（node_modules 存在 + express 可加载），缺失则安装
#   - 启动 node index.js（兼容 server/index.js）
# ============================================
log "ready"
set -u
NOTIFY_LOG="${HOME}/reax/common.log"
mkdir -p "$(dirname "$NOTIFY_LOG")" 2>/dev/null
exec > >(tee -a "$NOTIFY_LOG") 2>&1

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR" || exit 1

PID_FILE="${SCRIPT_DIR}/.pro-manager.pid"
DEPS_MARKER="${SCRIPT_DIR}/.deps-installed"
LOG_FILE="${SCRIPT_DIR}/service.log"

# 禁用交互
export DEBIAN_FRONTEND=noninteractive
export npm_config_yes=true

# ---------- 1. 已在运行则跳过 ----------
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        log "[pro-manager] 已在运行 (PID: $OLD_PID)，跳过"
        exit 0
    fi
fi
rm -f "$PID_FILE"

# ---------- 2. 等待 node 就绪 ----------
for i in $(seq 1 60); do
    command -v node &> /dev/null && break
    sleep 1
    [ $i -eq 60 ] && { log "[pro-manager][错误] node 未就绪"; exit 1; }
done

# ---------- 3. 安装依赖 ----------
install_deps() {
    log "[pro-manager] 开始安装依赖..."

    if [ -f "package.json" ]; then
        npm install --no-audit --no-fund 2>&1 || {
            log "[pro-manager][错误] 根目录依赖安装失败"
            return 1
        }
    fi

    if [ -d "server" ] && [ -f "server/package.json" ]; then
        (cd server && npm install --no-audit --no-fund 2>&1) || {
            log "[pro-manager][错误] server 依赖安装失败"
            return 1
        }
    fi

    # 静态前端目录存在则跳过 web 构建
    if [ -d "server/html" ]; then
        log "[pro-manager] 检测到 server/html/，跳过前端构建"
    elif [ -d "web" ] && [ -f "web/package.json" ]; then
        log "[pro-manager] 构建 web 前端..."
        (
            cd web
            npm install --no-audit --no-fund --ignore-scripts 2>&1
            node node_modules/esbuild/install.js 2>&1 || true
            NODE_OPTIONS=--no-experimental-detect-module node node_modules/vite/bin/vite.js build 2>&1
        ) && log "[pro-manager] 前端构建成功" \
          || log "[pro-manager] ⚠️ 前端构建失败，下次启动会重试"
    fi

    echo "ok" > "$DEPS_MARKER"
    return 0
}

# 校验依赖：marker 存在 且 express 可加载
verify_deps() {
    [ -f "$DEPS_MARKER" ] || return 1
    if [ -d "server" ]; then
        (cd server && node -e "require('express')" 2>/dev/null) || return 1
    else
        node -e "require('express')" 2>/dev/null || return 1
    fi
    return 0
}

if ! verify_deps; then
    install_deps || {
        log "[pro-manager][错误] 依赖安装失败，放弃启动"
        exit 1
    }
else
    log "[pro-manager] 依赖已就绪，跳过安装"
fi

# ---------- 4. 选择入口文件 ----------
ENTRY=""
if [ -f "index.js" ]; then
    ENTRY="index.js"
elif [ -f "server/index.js" ]; then
    ENTRY="server/index.js"
else
    log "[pro-manager][错误] 找不到入口文件"
    exit 1
fi

# ---------- 5. 启动服务 ----------
log "[pro-manager] 启动 $ENTRY..."
nohup node "${SCRIPT_DIR}/${ENTRY}" < /dev/null >> "$LOG_FILE" 2>&1 &
NEW_PID=$!
echo "$NEW_PID" > "$PID_FILE"

# ---------- 6. 健康检查 ----------
sleep 2
if kill -0 "$NEW_PID" 2>/dev/null; then
    log "[pro-manager] 启动成功 (PID: $NEW_PID)"
    exit 0
else
    log "[pro-manager][错误] 进程已退出，请查看 $LOG_FILE"
    rm -f "$PID_FILE"
    exit 1
fi
