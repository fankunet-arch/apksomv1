#!/usr/bin/env bash
#
# 平板侧网络诊断采集脚本
#
# 用途：当 App 报 ERROR_HOST_LOOKUP(-2)「无法解析服务器地址」时，
#      一次性抓全判断所需的信息，避免来回问。
#
# 用法（平板已连 USB 并开启调试）：
#   ./tools/diagnose-network.sh
#   ./tools/diagnose-network.sh lms.sushisom.net 192.168.2.32
#
# 输出同时写入 network-diagnose-<时间>.txt，把该文件贴回来即可。
#
set -uo pipefail

HOST="${1:-lms.sushisom.net}"
IP="${2:-192.168.2.32}"
OUT="network-diagnose-$(date +%Y%m%d_%H%M%S).txt"

if ! command -v adb >/dev/null 2>&1; then
  echo "找不到 adb，请先安装 Android SDK Platform-Tools"; exit 1
fi
if [ -z "$(adb devices | sed -n '2p')" ]; then
  echo "没有检测到已连接的设备。请确认 USB 调试已开启并已授权。"; exit 1
fi

exec > >(tee "$OUT") 2>&1

sec() { echo; echo "======== $* ========"; }
sh_() { adb shell "$@" 2>&1; }

echo "诊断目标: $HOST -> $IP"
echo "采集时间: $(date)"

sec "1. 设备信息"
echo "型号   : $(sh_ getprop ro.product.model)"
echo "Android: $(sh_ getprop ro.build.version.release) (SDK $(sh_ getprop ro.build.version.sdk))"
echo "WebView: $(sh_ dumpsys package com.google.android.webview | grep -m1 versionName || echo '未知')"

sec "2. 私人 DNS 设置（关键）"
MODE=$(sh_ settings get global private_dns_mode | tr -d '\r')
SPEC=$(sh_ settings get global private_dns_specifier | tr -d '\r')
echo "private_dns_mode      : $MODE"
echo "private_dns_specifier : $SPEC"
case "$MODE" in
  hostname) echo ">> ⚠️ 严格模式，会完全绕过路由器 DNS。内网域名必然解析失败。" ;;
  off|opportunistic|null|"") echo ">> OK，不会绕过路由器 DNS。" ;;
  *) echo ">> 未知取值，请人工确认。" ;;
esac

sec "3. 默认网络与 DNS 服务器（关键）"
# 内网 WiFi 常因无法访问公网而被判定为「未验证」，若同时开着移动数据，
# 系统可能把默认网络切到蜂窝 —— 此时 App 走运营商 DNS，内网域名必然解析不了。
sh_ dumpsys connectivity | grep -iE 'active default network|Dns addresses|Current state|NetworkAgentInfo.*(WIFI|MOBILE)' | head -30

sec "4. 移动数据是否开启"
echo "mobile_data: $(sh_ settings get global mobile_data | tr -d '\r')"
echo ">> 若为 1 且第 3 节显示默认网络是 MOBILE，基本就是根因：关掉移动数据再试。"

sec "5. WiFi 与路由"
sh_ ip route | head -10
sh_ ip -4 addr show wlan0 | grep inet

sec "6. 域名解析测试（决定性）"
PING_OUT=$(sh_ "ping -c 2 -W 2 $HOST")
echo "$PING_OUT"
if echo "$PING_OUT" | grep -qE "unknown host|Name or service not known|bad address"; then
  echo ">> ❌ 系统 resolver 解析不了 $HOST。问题在平板网络配置，与 App 无关。"
  echo ">>    优先检查：第 2 节私人 DNS、第 3 节默认网络、WiFi 是否配了静态 IP + 公共 DNS。"
elif echo "$PING_OUT" | grep -q "$IP"; then
  echo ">> ✅ 系统 resolver 解析正常，且指向 $IP。"
  echo ">>    此时 WebView 仍报 -2 属于异常，请把本文件连同第 8 节日志一起贴回。"
else
  echo ">> ⚠️ 解析到了，但地址不是 $IP，请看上面 ping 的实际输出。"
fi

sec "7. IP 直连测试"
sh_ "ping -c 2 -W 2 $IP"

sec "8. 应用日志（请先启动 App 复现一次再跑本脚本）"
adb logcat -d -t 200 2>&1 | grep -iE 'sushivip|AppWebViewClient|MainActivity|chromium|cr_|DNS|ERR_NAME' | tail -40

sec "采集完成"
echo "文件已保存: $OUT"
