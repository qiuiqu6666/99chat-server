#!/usr/bin/env bash
# 宝塔/aaPanel 常见 Node 路径；本机开发时 source 或 exec 此脚本
export PATH="/www/server/nodejs/v20.19.0/bin:${PATH}"
exec "$@"
