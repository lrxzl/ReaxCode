#!/bin/bash
# ============================================
# Pro-Manager 启动脚本 (快速版)
# 首次慢，后续秒启；代码更新在后台进行，下次执行生效
# 所有输出静默写入 ./pro-manager.log
# ============================================

CURRENT_VERSION="0.5.6"
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
# 函数：后台静默更新（修复版）
# --------------------------------------------------
background_update() {
    local LOG_PATH
    LOG_PATH="$(cd "$(dirname "$0")" && pwd)/pro-manager.log"

    # 使用 nohup + bash -c 启动独立进程
    # ① 确保 $$ 返回的是本进程 PID（不再是父进程的）
    # ② 确保父脚本退出后子进程不会被 SIGHUP 杀死
    PROJECT_DIR="$PROJECT_DIR" nohup bash -c '
        LOCK_FILE="/tmp/pro-manager-update.lock"

        # ------ 锁检查 ------
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

        # ------ 分支检测（增强） ------
        CURRENT_BRANCH=$(git symbolic-ref --short HEAD 2>/dev/null)
        if [ -z "$CURRENT_BRANCH" ]; then
            # 尝试从远程获取默认分支名
            CURRENT_BRANCH=$(git remote show origin 2>/dev/null | grep "HEAD branch" | sed "s/.*: //")
            if [ -z "$CURRENT_BRANCH" ]; then
                CURRENT_BRANCH="master"
            fi
        fi
        echo "[后台更新] 当前分支: $CURRENT_BRANCH"

        # ------ fetch（不再吞掉错误） ------
        echo "[后台更新] 检查远程更新..."
        if ! git fetch origin 2>&1; then
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
        echo "[后台更新] 正在拉取更新..."

        if ! git reset --hard "origin/$CURRENT_BRANCH" 2>&1; then
            echo "[后台更新] ❌ git reset 失败"
            exit 1
        fi

        # ------ 依赖安装 ------
        if [ -f "package.json" ]; then
            echo "[后台更新] 安装根目录依赖..."
            npm install --no-audit --no-fund 2>&1
        fi

        if [ -d "server" ] && [ -f "server/package.json" ]; then
            echo "[后台更新] 安装 server 依赖..."
            (cd server && npm install --no-audit --no-fund 2>&1)
        fi

        if [ -d "web" ] && [ -f "web/package.json" ]; then
            echo "[后台更新] 安装 web 依赖并构建前端..."
            (
                cd web
                npm install --no-audit --no-fund --ignore-scripts 2>&1
                node node_modules/esbuild/install.js 2>&1
                NODE_OPTIONS=--no-experimental-detect-module node node_modules/vite/bin/vite.js build 2>&1
            ) && echo "[后台更新] 前端构建成功" || echo "[后台更新] ⚠️ 前端构建失败，下次启动时会重试"
        fi

        echo "[后台更新] ✅ 更新完成，新代码将在下次启动时生效"
    ' >> "$LOG_PATH" 2>&1 &

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

    echo "[启动] 正在后台启动服务..."
    nohup node server/index.js >> ../pro-manager.log 2>&1 &
    SERVICE_PID=$!

    # 验证启动（重试 15 次，每次 1 秒）
    local retry=0
    local max_retries=15
    echo "[启动] 等待服务响应..."

    while [ $retry -lt $max_retries ]; do
        sleep 1
        # 使用 curl 检测比 lsof 更准确，不受权限影响
        if curl -s "http://localhost:$PORT" > /dev/null 2>&1; then
            echo "=========================================="
            echo "  Pro-Manager 启动成功！"
            echo "  访问地址: http://localhost:$PORT"
            echo "  PID: $SERVICE_PID"
            echo "  日志文件: $LOG_FILE"
            echo "=========================================="
            cd ..
            return 0
        fi

        # 检查进程是否已经崩溃退出
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
    return 0  # 即使超时也返回0，不阻断后续的后台更新流程
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
