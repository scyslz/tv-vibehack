#!/usr/bin/env bash
# 逐个试 MiTVTVPlayer 的硬件源 id，找出 HDMI 3 真正对应的那个。
#
# 原理：小米电视的输入源切换走 com.xiaomi.mitv.tvplayer/.ExternalSourceActivity，
# 参数是整型 extra "input"。已知：2 = HDMI 1，18 = HDMI 2，17 = AV。
# 这个脚本不需要重新编译 APK、不需要装新版本 —— 直接用 adb 把 Activity 拉起来，
# 你在电视上看着画面，对了按 y，脚本就把 id 记下来。
#
#   tools/probe_hdmi.sh                 # 按推荐顺序一个一个试（默认候选表）
#   tools/probe_hdmi.sh --ids 19 20 21  # 只试指定的 id
#   tools/probe_hdmi.sh --sweep         # 0..40 全扫一遍（每个都截图，事后自己看）
#   tools/probe_hdmi.sh --dump          # 只抓现场信息（TvInput 列表 / 包路径 / 已注册输入）
#   tools/probe_hdmi.sh --watch         # 直播 tvplayer 日志：用原遥控器自己切到 HDMI 3，抓真实 id
#
# 结果写在 .build/hdmi-probe/：每个 id 一张截图 + log.txt（tvplayer 自己打的日志）。
set -uo pipefail

DEV="${DEV:-192.168.100.165:5555}"
PKG="com.xiaomi.mitv.tvplayer"
ACT="$PKG/com.xiaomi.mitv.tvplayer.ExternalSourceActivity"
HOME_PKG="com.gathin.tvhome"
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/.build/hdmi-probe"

# 推荐顺序，理由见 --help / 文档 §14：
#   19 20 21  ...紧挨着 18（17=AV、18=HDMI2，19 最像 HDMI3）
#   16 15 14  ...AV(17) 左边
#   3 4 5 6   ...HDMI1(2) 右边的小号段
#   22 23 24  ...18 往后再留几个
#   0 1       ...TV/ATV，用来确认这套机制还活着
DEFAULT_IDS="19 20 21 16 3 4 5 6 22 23 24 15 1 0"

A=()
say() { printf '%s\n' "$*"; }

usage() { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

MODE=interactive
IDS="$DEFAULT_IDS"
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage ;;
        --dump)  MODE=dump ;;
        --watch) MODE=watch ;;
        --sweep) MODE=sweep; IDS=""; for i in $(seq 0 40); do IDS="$IDS $i"; done ;;
        --ids)   shift; IDS="$*"; break ;;
        *) say "未知参数：$1"; usage ;;
    esac
    shift
done

command -v adb >/dev/null || { say "找不到 adb（apt install android-tools-adb）"; exit 1; }

say "→ 连接 $DEV"
adb connect "$DEV" >/dev/null 2>&1
STATE=$(adb -s "$DEV" get-state 2>/dev/null | tr -d '\r')
if [ "$STATE" != "device" ]; then
    say "✗ 连不上电视。检查：电视开机、设置→账号与安全→ADB 已开、同一网段。"
    say "  当前 adb devices："; adb devices
    exit 1
fi
say "✓ 已连上：$(adb -s "$DEV" shell getprop ro.product.model | tr -d '\r')"

mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/log.txt"
: > "$LOG"

dump_info() {
    {
        say "===== dumpsys tv_input（系统注册的输入） ====="
        adb -s "$DEV" shell dumpsys tv_input 2>/dev/null | grep -iE "id=|packageName|type=|HDMI|label" | head -60
        say ""
        say "===== MiTVTVPlayer ====="
        adb -s "$DEV" shell pm path "$PKG" | tr -d '\r'
        say ""
        say "===== 当前前台 ====="
        adb -s "$DEV" shell dumpsys activity activities 2>/dev/null | grep -iE "mFocusedActivity|mResumedActivity" | head -5
    } | tee -a "$LOG"
}

if [ "$MODE" = watch ]; then
    say "现在用电视原配遥控器（或小米原版「输入源」面板）切到 HDMI 3，日志里冒出来的 id 就是答案。"
    say "Ctrl+C 结束。"
    adb -s "$DEV" logcat -c 2>/dev/null
    adb -s "$DEV" logcat -v time 2>/dev/null | grep -iE "hdmi|hdmiport|source|ExternalSource|InputSource|tvplayer"
    exit 0
fi

if [ "$MODE" = dump ]; then
    dump_info
    say ""
    say "完整输出已存：$LOG"
    exit 0
fi

# 把电视切回桌面，方便按 Home 键回到起点
to_home() {
    adb -s "$DEV" shell am start -n "$HOME_PKG/.MainActivity" >/dev/null 2>&1
    sleep 0.6
}

try_id() {
    local id="$1"
    adb -s "$DEV" logcat -c 2>/dev/null
    adb -s "$DEV" shell "am start -n $ACT --ei input $id --ez no_launch_panel true" 2>&1 | tr -d '\r' | tee -a "$LOG"
    sleep 2
    adb -s "$DEV" exec-out screencap -p > "$OUT_DIR/id_${id}.png" 2>/dev/null
    {
        say "--- logcat id=$id ---"
        adb -s "$DEV" logcat -d -t 400 2>/dev/null \
            | grep -iE "hdmi|hdmiport|source|ExternalSource|InputSource|tvplayer" | tail -15
    } | tee -a "$LOG"
}

say ""
say "候选 id：$IDS"
say "截图与日志目录：$OUT_DIR"
say "提示：先确认 HDMI 3 那根线插着、并且那个设备开着（没信号的黑色画面也说明 id 对了）。"
say ""

WINNER=""
for id in $IDS; do
    to_home
    say ""
    say "==================== 试 id = $id ===================="
    try_id "$id"

    if [ "$MODE" = sweep ]; then
        say "（sweep 模式：截图已存 id_${id}.png，继续）"
        continue
    fi

    while true; do
        printf '电视上现在是 HDMI 3 的画面吗？ [y=就是它 / n=不是 / r=再试一次 / s=看截图路径 / q=退出] '
        read -r ans </dev/tty || ans=q
        case "${ans,,}" in
            y|yes) WINNER="$id"; break 2 ;;
            r) try_id "$id"; continue ;;
            s) say "截图：$OUT_DIR/id_${id}.png" ;;
            q) say "退出，未记录"; exit 0 ;;
            *) break ;;
        esac
    done
done

say ""
if [ -n "$WINNER" ]; then
    say "✓ HDMI 3 = id $WINNER"
    say ""
    say "写进配置（把 19 换成 $WINNER）："
    say "  launcher/app/src/main/res/values/config.xml"
    say "  <integer-array name=\"source_hdmi\">  第 3 个 <item> 改成 $WINNER"
    say ""
    say "然后重新编译安装："
    say "  ./.build/build.sh && adb -s $DEV install -r launcher/app/build/outputs/apk/debug/app-debug.apk"
    printf '要我直接改 config.xml 吗？ [y/N] '
    read -r go </dev/tty || go=n
    if [ "${go,,}" = y ]; then
        CFG="$(cd "$(dirname "$0")/.." && pwd)/launcher/app/src/main/res/values/config.xml"
        python3 - "$CFG" "$WINNER" <<'PY'
import re, sys
cfg, new = sys.argv[1], sys.argv[2]
src = open(cfg, encoding="utf-8").read()
m = re.search(r'(<integer-array name="source_hdmi">)(.*?)(</integer-array>)', src, re.S)
body = m.group(2)
items = re.findall(r'<item>(.*?)</item>', body)
items = [i.strip() for i in items]
old = items[2] if len(items) > 2 else None
rebuilt = ["        <item>%s</item>%s" % (v, "" if n != 2 else "   <!-- HDMI 3（实测确认） -->")
           for n, v in enumerate(items[:2] + [new] + items[3:])]
src = src[:m.start(2)] + "\n" + "\n".join(rebuilt) + "\n    " + src[m.end(2):]
open(cfg, "w", encoding="utf-8").write(src)
print(f"config.xml: HDMI 3 {old} -> {new}")
PY
    else
        say "好的，配置没动。"
    fi
else
    say "整张候选表都没对上。下一步（按顺序）："
    say "  1) tools/probe_hdmi.sh --sweep         # 0..40 全扫，事后看 .build/hdmi-probe/*.png"
    say "  2) tools/probe_hdmi.sh --dump          # 看这台 ROM 到底注册了哪些输入"
    say "  3) 用原版遥控器打开「输入源」面板，切到 HDMI 3，然后："
    say "     adb -s $DEV shell dumpsys activity activities | grep -i source"
fi
