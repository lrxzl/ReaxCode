#!/bin/bash
# ============================================
# Pro-Manager 启动脚本 (快速版 + 守护进程)
# 首次慢，后续秒启；代码更新在后台进行，下次执行生效
# 所有输出静默写入 ./pro-manager.log
# ============================================
# [FIX] 全脚本适配非交互式环境，消除所有阻塞点
# [MOD] 若 server/html/ 存在，则跳过所有 /web 前端处理
# [MOD] 支持自定义目标分支变量

CURRENT_VERSION="0.5.8"
REPO_URL="https://gitee.com/lrxzlcn/pro-manager.git"
PROJECT_DIR="pro-manager"
PORT=3456
LOG_FILE="./pro-manager.log"

# [MOD] 指定拉取的分支，可改为 master 或其他分支名
TARGET_BRANCH="dev"

# 清空日志并重定向所有输出到日志文件（静默模式）
exec > "$LOG_FILE" 2>&1
# [FIX] 重定向 stdin 到 /dev/null，防止任何子进程等待用户输入导致挂起
exec < /dev/null

# [FIX] 禁用所有交互式提示
export DEBIAN_FRONTEND=noninteractive       # 禁止 dpkg/apt 配置提示
export APT_LISTCHANGES_FRONTEND=none         # 禁止 apt-listchanges 输出
export GIT_TERMINAL_PROMPT=0                 # 禁止 git 认证提示
export npm_config_yes=true                   # 自动确认 npm 提示

echo "=== Pro-Manager v${CURRENT_VERSION} 启动日志 $(date) ==="

# --------------------------------------------------
# 函数：检测是否存在静态前端目录 server/html/
# 存在则返回 0（true），server 会自动代理该目录，无需处理 /web
# --------------------------------------------------
has_static_html() {
    [ -d "$PROJECT_DIR/server/html" ]
}

# --------------------------------------------------
# 函数：安装 Node.js（如缺失）
# --------------------------------------------------
check_nodejs() {
    if command -v node &> /dev/null; then
        echo "[环境] Node.js 已存在: $(node -v)"
        return 0
    fi
    echo "[环境] 安装 Node.js..."
    pkg update -y > /dev/null 2>&1
    pkg install nodejs -y > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo "[环境] Node.js 安装完成"
        return 0
    else
        echo "[错误] Node.js 安装失败"
        exit 1
    fi
}

# --------------------------------------------------
# 函数：安装 Git（如缺失）
# --------------------------------------------------
check_git() {
    if command -v git &> /dev/null; then
        echo "[环境] Git 已存在: $(git --version | head -1)"
        return 0
    fi
    echo "[环境] 安装 Git..."
    pkg update -y > /dev/null 2>&1
    pkg install git -y > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo "[环境] Git 安装完成"
        return 0
    else
        echo "[错误] Git 安装失败"
        exit 1
    fi
}

# --------------------------------------------------
# 函数：配置镜像源（仅首次配置）
# --------------------------------------------------
setup_mirror() {
    MIRROR_FILE=".mirror-configured"
    if [ -f "$MIRROR_FILE" ]; then
        echo "[镜像] 已配置: $(cat $MIRROR_FILE)"
        return 0
    fi

    local SOURCES_LIST="${PREFIX:-/data/data/com.termux/files/usr}/etc/apt/sources.list"

    if [ ! -f "$SOURCES_LIST" ]; then
        echo "默认" > "$MIRROR_FILE"
        echo "[镜像] 未找到 sources.list，保持默认源"
        return 0
    fi

    echo "[镜像] 配置阿里云镜像源（非交互方式）..."

    cp "$SOURCES_LIST" "${SOURCES_LIST}.bak" 2>/dev/null

    sed -i '/termux-main/s|https\?://[^ ]*|https://mirrors.aliyun.com/termux/termux-main/|' "$SOURCES_LIST"

    if grep -q "mirrors.aliyun.com" "$SOURCES_LIST"; then
        echo "阿里" > "$MIRROR_FILE"
        echo "[镜像] 阿里云镜像配置完成"
        pkg update -y > /dev/null 2>&1
    else
        cp "${SOURCES_LIST}.bak" "$SOURCES_LIST" 2>/dev/null
        sed -i '/termux-main/s|https\?://[^ ]*|https://mirrors.tuna.tsinghua.edu.cn/termux/termux-main/|' "$SOURCES_LIST"
        if grep -q "tuna.tsinghua.edu.cn" "$SOURCES_LIST"; then
            echo "清华" > "$MIRROR_FILE"
            echo "[镜像] 清华镜像配置完成（回退）"
            pkg update -y > /dev/null 2>&1
        else
            echo "默认" > "$MIRROR_FILE"
            echo "[镜像] 保持默认源"
        fi
    fi
}

# --------------------------------------------------
# 函数：配置 npm 阿里镜像
# --------------------------------------------------
setup_npm_mirror() {
    npm config set registry https://registry.npmmirror.com 2>/dev/null
    npm config set fetch-timeout 60000 2>/dev/null
    npm config set fetch-retries 2 2>/dev/null
    echo "[镜像] npm 已切换为阿里镜像: $(npm config get registry)"
}

# --------------------------------------------------
# 函数：首次安装/克隆项目
# --------------------------------------------------
first_install() {
    echo "[安装] 开始首次部署..."
    echo "[安装] 克隆项目 (分支: $TARGET_BRANCH)..."
    # [MOD] 使用 -b 参数拉取指定分支
    timeout 300 git clone -b "$TARGET_BRANCH" "$REPO_URL" "$PROJECT_DIR" || {
        echo "[错误] 克隆失败，请检查网络或分支名"
        exit 1
    }
    install_deps
}

# --------------------------------------------------
# 函数：安装依赖（通用）
# --------------------------------------------------
install_deps() {
    cd "$PROJECT_DIR" || exit 1
    echo "[依赖] 正在安装根目录依赖..."
    npm install --no-audit --no-fund 2>&1
    if [ $? -ne 0 ]; then
        echo "[错误] 根目录依赖安装失败"
        cd ..
        exit 1
    fi

    echo "[依赖] 正在安装 server 依赖..."
    cd server && npm install --no-audit --no-fund 2>&1
    if [ $? -ne 0 ]; then
        echo "[错误] server 依赖安装失败"
        cd ../..
        exit 1
    fi
    cd ..

    # 检测静态前端目录，存在则跳过 /web
    if has_static_html; then
        echo "[依赖] 检测到 server/html/ 目录，跳过 /web 前端安装与构建"
        cd ..
        echo "[依赖] 所有依赖安装完成"
        return 0
    fi

    echo "[依赖] 正在安装 web 依赖..."
    cd web && npm install --no-audit --no-fund --ignore-scripts 2>&1
    if [ $? -ne 0 ]; then
        echo "[错误] web 依赖安装失败"
        cd ../..
        exit 1
    fi
    node node_modules/esbuild/install.js 2>&1
    if [ $? -ne 0 ]; then
        echo "[错误] esbuild 安装失败"
        cd ../..
        exit 1
    fi

    echo "[依赖] 正在构建前端..."
    NODE_OPTIONS=--no-experimental-detect-module node node_modules/vite/bin/vite.js build 2>&1
    if [ $? -ne 0 ]; then
        echo "[错误] 前端构建失败"
        cd ../..
        exit 1
    fi
    cd ..

    echo "[依赖] 所有依赖安装完成"
    cd ..
}

# --------------------------------------------------
# 函数：真正验证依赖是否可用
# --------------------------------------------------
verify_deps() {
    echo "[依赖] 验证依赖完整性..."
    cd "$PROJECT_DIR/server" || return 1

    if node -e "require('express')" 2>/dev/null; then
        echo "[依赖] 依赖验证通过"
        cd ../..
        return 0
    else
        echo "[依赖] express 模块不可用，需要重新安装依赖"
        cd ../..
        return 1
    fi
}

# --------------------------------------------------
# 函数：后台静默更新（强制更新版）
# --------------------------------------------------
background_update() {
    local LOG_PATH
    LOG_PATH="$(cd "$(dirname "$0")" && pwd)/pro-manager.log"

    local PM_SKIP_WEB=0
    if has_static_html; then
        PM_SKIP_WEB=1
    fi

    # [MOD] 将 TARGET_BRANCH 传入子进程
    PROJECT_DIR="$PROJECT_DIR" TARGET_BRANCH="$TARGET_BRANCH" PM_SKIP_WEB="$PM_SKIP_WEB" nohup bash -c '
        LOCK_FILE="/tmp/pro-manager-update.lock"

        if [ -f "$LOCK_FILE" ]; then
            OLD_PID=$(cat "$LOCK_FILE" 2>/dev/null)
            if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
                echo "[后台更新] 上次更新仍在进行中 (PID: $OLD_PID)，跳过"
                exit 0
            fi
        fi
        echo $$ > "$LOCK_FILE"
        trap "rm -f $LOCK_FILE" EXIT INT TERM

        cd "$PROJECT_DIR" || exit 1

        # [MOD] 直接使用外部传入的 TARGET_BRANCH
        CURRENT_BRANCH="$TARGET_BRANCH"
        echo "[后台更新] 目标分支: $CURRENT_BRANCH"

        echo "[后台更新] 检查远程更新..."
        if ! timeout 120 git fetch origin 2>&1; then
            echo "[后台更新] ❌ fetch 失败，可能无网络"
            exit 0
        fi

        LOCAL_HASH=$(git rev-parse HEAD 2>/dev/null)
        REMOTE_HASH=$(git rev-parse "origin/$CURRENT_BRANCH" 2>/dev/null)

        if [ -z "$REMOTE_HASH" ]; then
            echo "[后台更新] ❌ 无法获取远程分支 origin/$CURRENT_BRANCH 的哈希"
            echo "[后台更新] 可用的远程分支:"
            git branch -r 2>/dev/null
            exit 0
        fi

        if [ "$LOCAL_HASH" = "$REMOTE_HASH" ]; then
            echo "[后台更新] 当前已是最新代码 (${LOCAL_HASH:0:7})"
            exit 0
        fi

        echo "[后台更新] 🔔 发现新代码: ${LOCAL_HASH:0:7} → ${REMOTE_HASH:0:7}"
        echo "[后台更新] 正在强制拉取更新（丢弃所有本地改动）..."

        git checkout . 2>&1 || true
        # [MOD] 确保切回目标分支（防止本地因为某种原因切到了其他分支）
        git checkout "$CURRENT_BRANCH" 2>&1 || true
        if ! git reset --hard "origin/$CURRENT_BRANCH" 2>&1; then
            echo "[后台更新] ❌ git reset --hard 失败"
            exit 1
        fi
        git clean -fd 2>&1 || true

        echo "[后台更新] ✅ 代码已强制更新到 ${REMOTE_HASH:0:7}"

        # ------ 依赖安装 ------
        if [ -f "package.json" ]; then
            echo "[后台更新] 安装根目录依赖..."
            npm install --no-audit --no-fund 2>&1
        fi

        if [ -d "server" ] && [ -f "server/package.json" ]; then
            echo "[后台更新] 安装 server 依赖..."
            (cd server && npm install --no-audit --no-fund 2>&1)
        fi

        if [ "$PM_SKIP_WEB" = "1" ]; then
            echo "[后台更新] 检测到 server/html/ 目录，跳过 /web 前端处理"
        elif [ -d "web" ] && [ -f "web/package.json" ]; then
            echo "[后台更新] 安装 web 依赖并构建前端..."
            (
                cd web
                npm install --no-audit --no-fund --ignore-scripts 2>&1
                node node_modules/esbuild/install.js 2>&1
                NODE_OPTIONS=--no-experimental-detect-module node node_modules/vite/bin/vite.js build 2>&1
            ) && echo "[后台更新] 前端构建成功" || echo "[后台更新] ⚠️ 前端构建失败，下次启动时会重试"
        fi

        echo "[后台更新] ✅ 更新完成，新代码将在下次启动时生效"
    ' < /dev/null >> "$LOG_PATH" 2>&1 &

    local BG_PID=$!
    disown "$BG_PID" 2>/dev/null
    echo "[后台更新] 更新任务已提交 (PID: $BG_PID)"
}

# --------------------------------------------------
# 函数：启动服务
# --------------------------------------------------
start_service() {
    # 释放端口（如果被占用）
    local PID
    PID=$(lsof -ti:"$PORT" 2>/dev/null)
    if [ -n "$PID" ]; then
        if ps -p "$PID" -o comm= 2>/dev/null | grep -q "node"; then
            echo "[启动] 服务已在运行 (PID: $PID)，无需启动"
            return 0
        else
            echo "[启动] 端口 $PORT 被其他进程占用，强制释放..."
            kill -9 "$PID" 2>/dev/null
            sleep 1
        fi
    fi

    cd "$PROJECT_DIR" || return 1

    # 仅在不存在 server/html/ 时才需要检查并构建前端
    if ! has_static_html; then
        # 确保前端已构建
        if [ ! -f "web/dist/index.html" ]; then
            echo "[启动] 前端未构建，正在安装依赖并构建..."
            cd web && npm install --no-audit --no-fund --ignore-scripts 2>&1
            if [ $? -ne 0 ]; then
                echo "[错误] web 依赖安装失败"
                cd ..
                return 1
            fi
            node node_modules/esbuild/install.js 2>&1
            if [ $? -ne 0 ]; then
                echo "[错误] esbuild 安装失败"
                cd ..
                return 1
            fi
            NODE_OPTIONS=--no-experimental-detect-module node node_modules/vite/bin/vite.js build 2>&1
            if [ $? -ne 0 ]; then
                echo "[错误] 前端构建失败"
                cd ..
                return 1
            fi
            cd ..
            echo "[启动] 前端构建完成"
        fi
    else
        echo "[启动] 检测到 server/html/ 目录，跳过前端构建检查"
    fi

    echo "[启动] 正在后台启动服务..."
    nohup node server/index.js < /dev/null >> ../pro-manager.log 2>&1 &
    SERVICE_PID=$!

    # 验证启动（重试 15 次，每次 1 秒）
    local retry=0
    local max_retries=15
    echo "[启动] 等待服务响应..."

    while [ $retry -lt $max_retries ]; do
        sleep 1
        if curl -s --connect-timeout 2 --max-time 5 "http://localhost:$PORT" > /dev/null 2>&1; then
            echo "=========================================="
            echo "  Pro-Manager 启动成功！"
            echo "  访问地址: http://localhost:$PORT"
            echo "  PID: $SERVICE_PID"
            echo "  日志文件: $LOG_FILE"
            echo "=========================================="
            cd ..
            return 0
        fi

        if ! kill -0 "$SERVICE_PID" 2>/dev/null; then
            echo "[错误] node 进程意外退出，请查看日志"
            cd ..
            return 1
        fi
        retry=$((retry + 1))
    done

    echo "[警告] 服务启动超时(15秒)，但进程仍在后台运行"
    echo "[提示] 服务可能仍在初始化，稍后请自行访问 http://localhost:$PORT 确认"
    cd ..
    return 0
}

# --------------------------------------------------
# 函数：端口监听与自动重启 (守护进程 - 增强版)
# --------------------------------------------------
monitor_process() {
    local MONITOR_PID_FILE="./pro-manager-monitor.pid"
    local LOG_PATH="$(cd "$(dirname "$0")" && pwd)/pro-manager.log"
    local ABS_PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd)"

    if [ -f "$MONITOR_PID_FILE" ]; then
        local OLD_PID=$(cat "$MONITOR_PID_FILE")
        if kill -0 "$OLD_PID" 2>/dev/null; then
            echo "[监控] 守护进程已在运行 (PID: $OLD_PID)，跳过启动"
            return 0
        fi
    fi

    echo "[监控] 正在启动守护进程 (每10秒检查端口 $PORT)..."

    export PM_PORT=$PORT
    export PM_LOG=$LOG_PATH
    export PM_DIR=$ABS_PROJECT_DIR
    export PM_PID_FILE=$MONITOR_PID_FILE

    nohup bash -c '
        echo $$ > "$PM_PID_FILE"
        trap "rm -f $PM_PID_FILE" EXIT INT TERM

        while true; do
            sleep 10

            IS_ALIVE=0
            if command -v curl > /dev/null 2>&1; then
                if curl -s --connect-timeout 3 --max-time 5 http://localhost:$PM_PORT > /dev/null 2>&1; then
                    IS_ALIVE=1
                fi
            else
                if netstat -tlnp 2>/dev/null | grep -q ":$PM_PORT "; then
                    IS_ALIVE=1
                fi
            fi

            if [ $IS_ALIVE -eq 1 ]; then
                continue
            fi

            echo "[守护] $(date) ❌ 端口 $PM_PORT 无响应，准备重启服务..." >> "$PM_LOG"

            cd "$PM_DIR" || { echo "[守护] 进入目录 $PM_DIR 失败" >> "$PM_LOG"; continue; }

            if command -v lsof > /dev/null 2>&1; then
                lsof -ti:$PM_PORT | xargs kill -9 2>/dev/null
            elif command -v fuser > /dev/null 2>&1; then
                fuser -k $PM_PORT/tcp > /dev/null 2>&1
            else
                pkill -f "node server/index.js" > /dev/null 2>&1
            fi

            sleep 2

            nohup node server/index.js < /dev/null >> "$PM_LOG" 2>&1 &
            echo "[守护] $(date) ✅ 已发送重启命令 (新 PID: $!)" >> "$PM_LOG"

        done
    ' < /dev/null > /dev/null 2>&1 &

    local BG_PID=$!
    disown "$BG_PID" 2>/dev/null
    echo "[监控] 守护进程已启动 (PID: $BG_PID)"
}

# ================== 主流程 ==================
main() {
    # 1. 基础环境
    setup_mirror
    check_git
    check_nodejs
    setup_npm_mirror

    # 2. 项目不存在 → 首次安装
    if [ ! -d "$PROJECT_DIR" ]; then
        first_install
    else
        echo "[信息] 项目目录已存在"
        # 若存在静态前端目录，则不需要检查 web/dist/index.html
        if has_static_html; then
            echo "[信息] 检测到 server/html/ 目录，将跳过前端依赖检查"
            if ! verify_deps; then
                install_deps
            fi
        else
            if ! verify_deps || [ ! -f "$PROJECT_DIR/web/dist/index.html" ]; then
                install_deps
            fi
        fi
    fi

    # 3. 启动前再次确认依赖可用（双保险）
    if ! verify_deps; then
        echo "[错误] 依赖安装后仍不可用，请手动检查"
        exit 1
    fi

    # 4. 启动服务
    start_service

    # 5. 后台静默更新代码（下次生效）
    background_update

    # 6. 启动端口监听守护进程
    monitor_process

    echo "[完成] 脚本执行完毕 $(date)"
}

main
