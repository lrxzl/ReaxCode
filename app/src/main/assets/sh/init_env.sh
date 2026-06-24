#!/bin/bash
# ============================================
# Pro-Manager 启动脚本 (快速版)
# 首次慢，后续秒启；代码更新在后台进行，下次执行生效
# 所有输出静默写入 ./pro-manager.log
# ============================================

CURRENT_VERSION="0.5.5"
REPO_URL="https://gitee.com/lrxzlcn/pro-manager.git"
PROJECT_DIR="pro-manager"
PORT=3456
LOG_FILE="./pro-manager.log"

# 清空日志并重定向所有输出到日志文件（静默模式）
exec > "$LOG_FILE" 2>&1
echo "=== Pro-Manager v${CURRENT_VERSION} 启动日志 $(date) ==="

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

    if ! command -v termux-change-repo &> /dev/null; then
        pkg install termux-tools -y > /dev/null 2>&1
    fi

    echo "[镜像] 配置阿里云镜像源..."
    if termux-change-repo aliyun 2>/dev/null; then
        echo "阿里" > "$MIRROR_FILE"
        echo "[镜像] 阿里云镜像配置完成"
    else
        if termux-change-repo tsinghua 2>/dev/null; then
            echo "清华" > "$MIRROR_FILE"
            echo "[镜像] 清华镜像配置完成（回退）"
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
    echo "[镜像] npm 已切换为阿里镜像: $(npm config get registry)"
}

# --------------------------------------------------
# 函数：首次安装/克隆项目
# --------------------------------------------------
first_install() {
    echo "[安装] 开始首次部署..."
    echo "[安装] 克隆项目..."
    git clone "$REPO_URL" "$PROJECT_DIR" || {
        echo "[错误] 克隆失败，请检查网络"
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
# 函数：后台静默更新（核心修复版）
# --------------------------------------------------
background_update() {
    # 获取当前脚本的绝对路径，确保日志路径正确
    local LOG_PATH
    LOG_PATH="$(cd "$(dirname "$0")" && pwd)/pro-manager.log"

    (
        # 锁文件防止多个更新进程同时运行
        LOCK_FILE="/tmp/pro-manager-update.lock"
        if [ -f "$LOCK_FILE" ]; then
            OLD_PID=$(cat "$LOCK_FILE" 2>/dev/null)
            # 检查旧进程是否还在运行
            if kill -0 "$OLD_PID" 2>/dev/null; then
                echo "[后台更新] 上次更新仍在进行中，跳过"
                exit 0
            fi
        fi
        echo $$ > "$LOCK_FILE"

        cd "$PROJECT_DIR" || { rm -f "$LOCK_FILE"; exit 1; }

        # 自动检测当前分支
        CURRENT_BRANCH=$(git symbolic-ref --short HEAD 2>/dev/null || echo "master")
        echo "[后台更新] 当前分支: $CURRENT_BRANCH"

        echo "[后台更新] 检查远程更新..."
        git fetch origin "$CURRENT_BRANCH" 2>/dev/null || {
            echo "[后台更新] fetch 失败，可能无网络"
            rm -f "$LOCK_FILE"
            exit 0
        }

        LOCAL_HASH=$(git rev-parse HEAD)
        REMOTE_HASH=$(git rev-parse "origin/$CURRENT_BRANCH")

        if [ "$LOCAL_HASH" = "$REMOTE_HASH" ]; then
            echo "[后台更新] 当前已是最新代码 ($LOCAL_HASH:7)"
            rm -f "$LOCK_FILE"
            exit 0
        fi

        echo "[后台更新] 发现新代码: ${LOCAL_HASH:0:7} → ${REMOTE_HASH:0:7}"
        echo "[后台更新] 正在更新..."

        git reset --hard "origin/$CURRENT_BRANCH" 2>/dev/null || {
            echo "[后台更新] git reset 失败"
            rm -f "$LOCK_FILE"
            exit 1
        }

        # 检查是否需要更新依赖（package.json 或 lock 文件有变化）
        NEED_DEPS=false

        # 根目录依赖
        if [ -f "package.json" ]; then
            echo "[后台更新] 安装根目录依赖..."
            npm install --no-audit --no-fund --silent 2>&1
        fi

        # server 依赖（之前漏掉的！）
        if [ -d "server" ] && [ -f "server/package.json" ]; then
            echo "[后台更新] 安装 server 依赖..."
            (cd server && npm install --no-audit --no-fund --silent 2>&1)
        fi

        # web 依赖 + 构建
        if [ -d "web" ] && [ -f "web/package.json" ]; then
            echo "[后台更新] 安装 web 依赖并构建前端..."
            (
                cd web
                npm install --no-audit --no-fund --ignore-scripts --silent 2>&1
                node node_modules/esbuild/install.js 2>&1
                NODE_OPTIONS=--no-experimental-detect-module node node_modules/vite/bin/vite.js build 2>&1
            )
            if [ $? -eq 0 ]; then
                echo "[后台更新] 前端构建成功"
            else
                echo "[后台更新] 前端构建失败，下次启动时会重试"
            fi
        fi

        echo "[后台更新] ✅ 更新完成，新代码将在下次启动时生效"
        rm -f "$LOCK_FILE"
    ) >> "$LOG_PATH" 2>&1 &

    # 获取后台进程 PID
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
            return 0    # ✅ 修复：改 exit 为 return，让流程继续
        else
            echo "[启动] 端口 $PORT 被其他进程占用，强制释放..."
            kill -9 "$PID" 2>/dev/null
            sleep 1
        fi
    fi

    cd "$PROJECT_DIR" || exit 1

    # 确保前端已构建
    if [ ! -f "web/dist/index.html" ]; then
        echo "[启动] 前端未构建，正在安装依赖并构建..."
        cd web && npm install --no-audit --no-fund --ignore-scripts 2>&1
        if [ $? -ne 0 ]; then
            echo "[错误] web 依赖安装失败"
            cd ..
            exit 1
        fi
        node node_modules/esbuild/install.js 2>&1
        if [ $? -ne 0 ]; then
            echo "[错误] esbuild 安装失败"
            cd ..
            exit 1
        fi
        NODE_OPTIONS=--no-experimental-detect-module node node_modules/vite/bin/vite.js build 2>&1
        if [ $? -ne 0 ]; then
            echo "[错误] 前端构建失败"
            cd ..
            exit 1
        fi
        cd ..
        echo "[启动] 前端构建完成"
    fi

    echo "[启动] 正在后台启动服务..."
    nohup node server/index.js >> ../pro-manager.log 2>&1 &
    SERVICE_PID=$!
    sleep 2

    # 验证启动
    if lsof -ti:"$PORT" > /dev/null 2>&1; then
        echo "=========================================="
        echo "  Pro-Manager 启动成功！"
        echo "  访问地址: http://localhost:$PORT"
        echo "  PID: $SERVICE_PID"
        echo "  日志文件: $LOG_FILE"
        echo "=========================================="
    else
        echo "[错误] 启动失败，请查看上方日志"
        exit 1
    fi
    cd ..
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
        if ! verify_deps || [ ! -f "$PROJECT_DIR/web/dist/index.html" ]; then
            install_deps
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
    #    ✅ 现在 return 0 不会阻断这里了
    background_update

    echo "[完成] 脚本执行完毕 $(date)"
}

main
