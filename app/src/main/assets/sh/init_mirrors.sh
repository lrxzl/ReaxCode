#!/bin/bash
# ============================================
# 镜像源配置（apt + npm），幂等
# ============================================

set -u

MARKER_FILE="${HOME}/.mirror-configured"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
SOURCES_LIST="${PREFIX}/etc/apt/sources.list"

if [ -f "$MARKER_FILE" ]; then
    echo "[mirror] 已配置过: $(cat "$MARKER_FILE")"
else
    echo "[mirror] 配置 apt 阿里云镜像..."
    if [ -f "$SOURCES_LIST" ]; then
        cp "$SOURCES_LIST" "${SOURCES_LIST}.bak" 2>/dev/null
        sed -i '/termux-main/s|https\?://[^ ]*|https://mirrors.aliyun.com/termux/termux-main/|' "$SOURCES_LIST"

        if grep -q "mirrors.aliyun.com" "$SOURCES_LIST"; then
            echo "阿里" > "$MARKER_FILE"
            echo "[mirror] 阿里云源配置完成"
            pkg update -y > /dev/null 2>&1
        else
            # 回退清华源
            sed -i '/termux-main/s|https\?://[^ ]*|https://mirrors.tuna.tsinghua.edu.cn/termux/termux-main/|' "$SOURCES_LIST"
            if grep -q "tuna.tsinghua.edu.cn" "$SOURCES_LIST"; then
                echo "清华" > "$MARKER_FILE"
                echo "[mirror] 清华源配置完成（回退）"
                pkg update -y > /dev/null 2>&1
            else
                echo "默认" > "$MARKER_FILE"
                echo "[mirror] 保持默认源"
            fi
        fi
    else
        echo "默认" > "$MARKER_FILE"
        echo "[mirror] 未找到 sources.list，保持默认"
    fi
fi

# npm 镜像每次都重设一次（成本极低，保证配置正确）
if command -v npm &> /dev/null; then
    npm config set registry https://registry.npmmirror.com 2>/dev/null
    npm config set fetch-timeout 60000 2>/dev/null
    npm config set fetch-retries 2 2>/dev/null
    echo "[mirror] npm registry: $(npm config get registry)"
fi
