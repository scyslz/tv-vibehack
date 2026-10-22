# NLRC Home（tv-vibehack 适配版）成果文档

> 设备：小米电视 4A（MiTV4-ANSM0，Amlogic，Android 6.0.1 / API 23，1920×1080 @ 320dpi）
> 局域网 ADB：`192.168.100.165:5555`
> 源码：`/home/hq/workspace/tv-vibehack`（上游 https://github.com/ToomRamcharan/tv-vibehack）
> 本文档记录本机实际做出的改动、原因、使用方法与回退方案。

---

## 1. 部署结果

| 项目 | 内容 |
|---|---|
| 应用包名 | `com.gathin.tvhome` |
| 桌面上显示名 | NLRC（NLRC Entertainment Hub） |
| 状态 | 已安装并运行，Home 键可回到该桌面 |
| 小米原生桌面 | `com.mitv.tvhome` **保留、未停用、未卸载**（安全兜底） |
| 无障碍服务 | `com.gathin.tvhome/com.gathin.tvhome.HomeKeyService` 已启用 |

---

## 2. 主要改动与原因

### 2.1 最低系统版本
上游要求 `minSdk 26`，本机是 API 23，改为 **23**（`launcher/app/build.gradle`）。

### 2.2 中文界面（原先为泰卢固语）
上游作者使用泰卢固语（Telugu，印度安得拉邦语言），问候语、节日横幅、日期均为泰卢固语，并内置了 `notosanstelugu.ttf` 字体规避固件缺字。

现改为中文并删除该字体：
- 问候语按时段：早上好 / 中午好 / 下午好 / 晚上好 / 夜深了·注意休息
- 节日横幅：元旦、春节、元宵、惊蛰、清明、劳动节、端午、建党、建军、中秋、国庆、重阳、冬至、圣诞，非节日显示「欢迎回家 · 愿你有个愉快的一天 · 星期X」
- 日期格式：`10月21日 星期三`（原 `EEEE d MMMM`），并提高对比度
- PenDrive 卡片角标 `3 భాగాలు` → `3 部`
- 删除 `assets/fonts/notosanstelugu.ttf`，源码已无任何泰卢固字符

### 2.3 桌面背景（原为写死的湿婆神像图）
换成**自动轮换的风景照**，每 **3 分钟**换一张，0.9 秒淡入：
- **有网时优先用网络图片**：拉取 Bing 每日壁纸（`HPImageArchive`，即 Windows 锁屏同一图源，按 `mkt` 市场给出应季、当地风光的编辑精选图），一次取最近 8 张，每 3 分钟切换一张，列表最多每 30 分钟刷新一次
  - **只在内存中保留**（最多 3 张、RGB_565），不写入任何本地文件/磁盘缓存（`setUseCaches(false)` + `Cache-Control: no-cache`），进程结束即消失
  - 市场按系统语言推导：`zh→zh-CN`、`ja→ja-JP`、`ko→ko-KR`、`de/fr/es` 同理，其余 `en-US`
- **离线 / 首次加载时**回退内置 10 张 1920×1080 风景图（`assets/wallpapers/`），同样每 3 分钟一张；内置图未做任何改动
- 新增渐变压暗层 `bg_wallpaper_scrim.xml` + 左侧暗角 `bg_wallpaper_vignette.xml`，保证亮图下文字可读
- 支持用户自定义：把自己的 jpg/png 放进
  `/sdcard/Android/data/com.gathin.tvhome/files/wallpapers/`
  有自定义图时优先轮播（优先级：自定义 > 网络 > 内置）；读不到/损坏时自动回退
- 核心逻辑在 `Wallpapers.java`：按时段选图、按屏幕分辨率降采样（RGB_565）解码，避免老机型内存压力
- 需要 `INTERNET` 权限（已加入 `AndroidManifest.xml`）
- 删除原图 `res/drawable-nodpi/bg_shiva_parvathi.jpg`

### 2.4 Wi-Fi / 蓝牙 / 设置按钮无效 → 已修复
本机小米设置**没有声明标准** `android.settings.WIFI_SETTINGS` / `android.settings.SETTINGS`，只支持自家 action，所以按标准 Intent 打开会失败。

新增 `TvSettings.java`，按优先级探测可用入口：
- Wi-Fi：`com.xiaomi.mitv.settings.WIFI_SETTINGS` → `com.xiaomi.mitv.settings/.wifi.MitvWifiActivity` → 标准 Intent 兜底
- 蓝牙：`com.xiaomi.mitv.settings.BLUETOOTH_SETTINGS` → 小米蓝牙页 → 设置主面板 → 标准 `ACTION_BLUETOOTH_SETTINGS` 兜底
- 设置：`com.xiaomi.mitv.settings.SETTINGS` → `com.xiaomi.mitv.settings/.entry.MainActivity` → 标准 Intent 兜底

顶栏胶囊从左到右：**Inputs · Wi-Fi · 蓝牙 · 设置**（蓝牙与 Wi-Fi 同一行，新增一列）。
Wi-Fi 胶囊显示当前 SSID / 以太网 / 离线；蓝牙胶囊关闭时显示「蓝牙已关闭」，开启但未连接设备时显示「无设备」，已连接时直接显示设备名（耳机、音箱、遥控器等），设备名过长会省略。

已验证可打开小米无线网络页与设置主面板。

### 2.5 HDMI / AV / 直播电视
本机不支持 `TvContract` 直通，改用小米自有 `com.xiaomi.mitv.tvplayer/.ExternalSourceActivity`，`input` 整型 extra：
- HDMI 1 = 2，AV = 17，HDMI 2 = 18；TV 模拟 = `.AtvActivity`
- 可在 `res/values/config.xml` 的 `source_hdmi_1/2`、`source_av` 调整
- 已验证 HDMI 1、HDMI 2 可切换

### 2.6 Home 键接管（无 root）
API 23 上没有 `pm set-home-activity` / `cmd package`，直接启动小米桌面又需要 `com.mitv.tvhome.permission.HOME_STATE` 权限。

方案：`HomeKeyService`（无障碍服务）监听窗口变化，检测到小米桌面被切到前台就立刻启动本桌面。
- 小米桌面始终保留，因此不会变砖
- 首次启动会弹一次提示，引导把 NLRC 设为默认桌面（点按钮直接进电视设置）；设成默认后 Home 为秒开
- 未设默认时，按 Home 会先看到小米桌面约 4–5 秒再切回本桌面

### 2.7 Dock（首行，最多 4 个，可自定义）+ 应用网格
原布局把「Dock 行」也放在同一个列表里，按方向键下移时 Dock 会跟着滚走，导致屏幕上只剩一行内容、上方大片空白，并且滑动后残留一块黑色圆角底板（`ItemDecoration` 不会被 padding 裁剪）。

现在的结构：
- `dock`：独立 RecyclerView，**位于内容区第一行**（顶栏下面），**一行最多 4 个磁贴**
- **Dock 为空时整行隐藏**（连背景色带一起消失），不留空槽
- `grid`：Dock 下方的应用网格（4 列），整行滚动，行间无空白、无黑块
- `DockDecoration` 只给 Dock 画色带并自身裁剪，不会残留黑板
- 焦点顺序与屏幕位置一致：**顶栏 → Dock（第一行）→ 应用网格**
  - Dock ↑ 到顶栏，↓ 进网格首行（同列）
  - 网格首行 ↑ 回 Dock（Dock 隐藏时直接回顶栏），其余行 ↑/↓ 逐行移动
- 移除了会与时钟重叠的 48sp 大标题，只保留随焦点变化的氛围光晕

#### 自定义 Dock（长按 OK）
- 网格里的应用：长按 OK 弹出菜单 →「固定到 Dock」，**追加到 Dock 末尾**（顺序＝添加顺序）
- Dock 里的磁贴：长按 OK →「从 Dock 移除」，该应用自动回到网格
- Inputs 弹窗里的 HDMI 1 / HDMI 2 / AV / Live TV：长按 OK 可直接固定 / 取消固定
- Dock 已满 4 个时会提示「Dock 只能放 4 个，请先移除一个」
- 固定列表保存在 `SharedPreferences("home").dock_keys`（每行一个 key，按添加顺序），
  由 `DockStore.java` 读写；卸载应用后对应 key 会自动跳过，不会留下空槽

#### 输入源不再出现在网格
HDMI / AV / Live TV 这类直通输入**只出现在两处**：顶栏 Inputs 弹窗、以及用户自己固定到 Dock 的磁贴。
应用网格（`grid`）只列真实应用，避免同一端口在网格和 Dock 里重复出现。

输入源列表现在是**完整列表**：HDMI 1、HDMI 2、AV Input、Live TV 始终全部列出（不再因为 Dock 里已有同款磁贴而隐藏），
从 Inputs 弹窗里选任意一项都能直接切过去。

### 2.8 磁贴尺寸与名称显示
- 磁贴由 `180×101dp` 改为 `180×78dp`（1080p 上 360×156px），一行高度约 188px
- 原先磁贴在卡片下方另有一行「应用名」，会占用行高、压到下一行，且图标磁贴会和名称重叠
- 现在**网格与 Dock 都不再显示独立名称行**：
  - 有 banner 的应用直接用 banner 自身的品牌字（YouTube / tv box / 云视听极光…）
  - 纯图标应用（无 banner）保留卡片内底部的名称小字
  - 卡片高度固定，图标居中，不再重叠

### 2.9 其它细节
- 顶部胶囊按钮、日期、问候的对比度与阴影调整
- 磁贴列表每次 `onResume` 重新读取（固定 / 移除后需立即生效，不再用 `loaded` 缓存）
- 启动加了 `FLAG_ACTIVITY_NO_ANIMATION`
- 调试日志清理

---

## 3. 构建与安装

宿主机没有 Java，构建在 Docker 镜像里完成（eclipse-temurin:17-jdk + Android SDK 34）。
持久化 debug keystore 位于 `.build/android-home`，保证签名稳定、`adb install -r` 可覆盖升级。

```bash
cd /home/hq/workspace/tv-vibehack
./.build/build.sh
adb -s 192.168.100.165:5555 install -r launcher/app/build/outputs/apk/debug/app-debug.apk
# 重装后需重新开启无障碍（见下）
adb -s 192.168.100.165:5555 shell "settings put secure enabled_accessibility_services \
  com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceAccessibilityService:com.gathin.tvhome/com.gathin.tvhome.HomeKeyService; \
  settings put secure accessibility_enabled 1"
```

截图（PNG 前有 `argc: 2` 前缀，需先定位 `\x89PNG` 再解码）：

```bash
adb -s 192.168.100.165:5555 exec-out screencap -p > shot.png
```

---

## 4. 使用方法

1. 按遥控器 Home 回到 NLRC Home
2. **第一行 Dock**：左右选择，OK 执行；长按 OK 可从 Dock 移除该项
3. **下方应用网格**：Dock ↓ 进入，左右选应用、上下整行滚动；长按 OK 可固定到 Dock
4. 顶部右上角：Inputs（完整输入源列表，长按 OK 可固定到 Dock）、Wi-Fi、蓝牙、设置
5. Dock 最多 4 个磁贴，为空时整行自动隐藏

---

## 5. 回退方案（安全兜底）

小米原生桌面 `com.mitv.tvhome` 始终保留，可随时恢复：

```bash
# 1) 关掉本桌面的无障碍接管
adb -s 192.168.100.165:5555 shell "settings put secure enabled_accessibility_services \
  com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceAccessibilityService"

# 2) 卸载本桌面（可选）
adb -s 192.168.100.165:5555 uninstall com.gathin.tvhome
```

若已在电视设置里把 NLRC 设为默认桌面，改回小米桌面即可（设置 → 应用/默认应用 → 桌面）。

---

## 6. 已知限制

- 未设默认桌面时，Home 键回桌面有约 4–5 秒延迟（系统先拉起小米桌面）
- 重装 APK 会清掉无障碍授权，需要重新开启（脚本见上）
- 遥控器 Netflix / Prime 快捷键映射到 HDMI 2，但小米语音服务抢占按键过滤时可能不生效
- 无 root、无 `su`，仅 `shell` uid，无法写入系统默认桌面设置
- 网络壁纸依赖 Bing 每日图源（`www.bing.com/HPImageArchive.aspx`）与 `INTERNET` 权限；断网或取图失败时自动回退内置 10 张，不会出现空背景
- Bing 每天最多提供 8 张编辑精选图，因此 3 分钟轮换约 24 分钟走完一轮（图源限制，非本地问题）

---

## 7. 关于 LTV Launcher

设备上存在另一个桌面 `com.leanbitlab.ltvL`（版本 `2026.10.03`，`installer=null`，首次安装时间 `2026-10-20 23:40:30`）。

**该应用不是本次工作安装的。** 本次工作只构建并安装了 `com.gathin.tvhome`（APK 由 `.build/build.sh` 产出，通过 `adb install` 推送）。`com.leanbitlab.ltvL` 属于同一时间段内通过其他方式（U 盘 / 应用市场 / 手机推送）侧载到电视上的第三方应用，与 NLRC Home 相互独立，可自行保留或卸载。

---

## 8. 本轮改动清单（2026-10-21，Dock 与顶栏）

改动已完成、**构建成功并已安装到电视**（`app-debug.apk`，2026-10-21 07:57 覆盖安装；版本 `2.0` / versionCode 2）。
安装后已重新开启无障碍服务并重启桌面，实测顶栏 4 个胶囊、Dock 4 项、Inputs 完整列表、网格无输入源均正常。

| # | 需求 | 实现 |
|---|---|---|
| 1 | Dock 只允许一行放 4 个 | `AppAdapter.MAX_DOCK = 4`，固定时超出弹提示 |
| 2 | Dock 一个都没有时不显示 Dock 栏 | `MainActivity.updateDockVisibility()`，`dock` 设为 `GONE`，色带随 View 一起消失 |
| 3 | 输入源不能在应用网格里出现 | `reload()` 把 `isInput` 的条目从网格剔除，输入源只出现在 Inputs 弹窗与（用户自行固定的）Dock |
| 4 | Inputs 列表要完全显示 | 去掉按 Dock 去重的逻辑，HDMI 1 / HDMI 2 / AV Input / Live TV 恒定全列 |
| 5 | 网格里长按 OK 固定到 Dock，Dock 可移除，顺序为添加顺序 | 长按弹菜单（`popup_tile_menu.xml`）：网格「固定到 Dock」追加到末尾、Dock「从 Dock 移除」；顺序存于 `SharedPreferences("home").dock_keys` |
| 6 | 不显示名称，图标不再重叠 | 移除卡片外的 `name` 文案；磁贴改为 180×78dp，图标 34dp 顶部对齐，纯图标磁贴保留卡片内小字 |
| 7 | 顶栏加蓝牙，与 Wi-Fi 同排一列 | 新增 `btnBluetooth` 胶囊 + `ic_bluetooth`，`TvSettings.bluetooth()` 按小米 action 探测；关闭时显示「蓝牙已关闭」，已连接时显示设备名 |
| 8 | 蓝牙胶囊要显示连接状态，而不只是「Bluetooth」 | `updateBluetoothLabel()` 汇总 ACL 广播、GATT、A2DP / HEADSET 代理与已配对设备连接状态，显示当前设备名；无设备时显示「No device」，名过长省略 |

涉及文件：
- 新增：`DockStore.java`、`popup_tile_menu.xml`、`ic_bluetooth.xml`、`ic_pin.xml`、`ic_unpin.xml`
- 修改：`MainActivity.java`、`AppAdapter.java`、`TvSettings.java`、`activity_main.xml`、`item_app.xml`、`strings.xml`、`config.xml`

### 验证步骤（ADB 恢复后）
```bash
# 1) 安装
adb -s 192.168.100.165:5555 install -r launcher/app/build/outputs/apk/debug/app-debug.apk
# 2) 重开无障碍
adb -s 192.168.100.165:5555 shell "settings put secure enabled_accessibility_services \
  com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceAccessibilityService:com.gathin.tvhome/com.gathin.tvhome.HomeKeyService; \
  settings put secure accessibility_enabled 1"
# 3) 重启桌面
adb -s 192.168.100.165:5555 shell am force-stop com.gathin.tvhome
adb -s 192.168.100.165:5555 shell am start -n com.gathin.tvhome/.MainActivity
```
检查点：
1. 顶栏出现 4 个胶囊：Inputs / Wi-Fi / 蓝牙 / 设置，蓝牙可打开对应设置页
2. 第一行 Dock 只剩已固定项，最多 4 个
3. 移除 Dock 全部项后该行（含深色底板）整体消失，网格上移
4. 网格中只有应用，没有 HDMI / AV / Live TV
5. 网格长按 OK → 可固定，且追加在 Dock 末尾；Dock 长按 → 可移除
6. Inputs 弹窗里 HDMI 1、HDMI 2、AV、Live TV 全部在列，长按可直接固定 / 取消固定
7. 磁贴不再有独立名称行，图标与卡片无重叠

> 注意：Dock 的固定列表保存在应用私有 SharedPreferences，`adb shell pm clear com.gathin.tvhome` 会重置为首次安装的默认两项（`nlrc_pendrive`、`org.smarttube.stable`）。

---

## 9. 本轮改动（Dock 与网格合并成一屏两行，versionCode 3 / 2.1）

### 9.1 需求
原来屏幕上是 **3 行**：Dock 一行 + 网格两行。要求：
1. 默认只显示 **第一行**，也就是 Dock 栏（带 Dock 浅色底板）
2. 按 ↓ 之后才「展开」出第二行，变成 **两行网格**
3. 展开后第一行的 Dock **就是网格的第一行**，**不再有 Dock 样式**（底板消失、行距与网格一致）
4. **整屏最多两行**（无论装了多少应用）

### 9.2 实现
不再用两个 RecyclerView，**Dock 与网格合并成同一个列表**（`AppAdapter.items`）：
- 前 `dockCount` 个位置 = 用户固定的 Dock 磁贴（顺序＝固定顺序，最多 `columns` 个）
- 中间用**不可见占位磁贴**把 Dock 行补齐到一整行，保证每个真实磁贴都从行首开始，
  于是「上/下」永远是精确的一行（`position ± columns`），左右不会跑出本行
- 之后是真实应用（输入源仍然不进网格）

一屏两行的做法（`MainActivity.setExpanded()`）：
- 列表本身固定在 **两行高度**，外面套一个 `gridWindow`（`activity_main.xml`），
  只改变这个窗口的高度：**收起＝一行高**，**展开＝两行高**，用 `ValueAnimator` 做 220ms 缓动
- 因为只是外层裁剪窗口变高变矮，RecyclerView 不做重新布局，动画过程中磁贴不跳动，
  焦点也不会丢
- **Dock 行靠近屏幕底部**：`activity_main.xml` 里在分隔线与 `gridWindow` 之间加了一个
  `layout_weight=1` 的弹性空隙，磁贴区因此被压到屏幕下方；父布局 `paddingBottom=28dp` 留出呼吸。
  窗口变高时弹性空隙同步变矮，所以**底边固定不动、第二行向上展开**（展开⇄收起不会有横线抖动）
- `DockDecoration` 新增 `alpha`：聚焦在 Dock 行时 = 1（浅色底板 + 描边），
  一进入应用行就 200ms 淡出到 0，Dock 行于是变成普通的网格第一行；
  回到 Dock 行再淡入
- **行高与窗口严格对齐（避免「两行半」）**：窗口高 = 行高 × 行数 = 94dp × 1 或 2，
  RecyclerView 不再留 padding，底板内缩 8dp（正好落在 `item_app.xml` 自带的 8dp 内边距里），
  所以收起时不会露出下一行、展开时正好两行
- **滚动按行吸附**：`snapRowsTo()` 用 `scrollToPositionWithOffset(行首, 0)` 把「展开时当前行、
  收起时 Dock 行」贴到窗口顶部，永远不会停在两行中间（原来会上下各露出半行）
- **高亮跟着行走**：窗口高度动画 / 行吸附都不触发 `onScrolled`，所以用 `glassDirty` 标记，
  在 `OnPreDrawListener` 里按新布局重新贴合高亮，避免出现一个「空的玻璃框」

焦点行为：
- 顶栏胶囊（Inputs / Wi-Fi / 蓝牙 / 设置）获得焦点 → 收起回一行（只剩 Dock）
- Dock 行按 ↓ → 展开两行，焦点落到同一列的应用；继续按 ↓ 两行一起上滚（Dock 行随之滚走）
- 应用行按 ↑ 到 Dock 行 → 收起回一行，并滚回顶部
- 顶栏按 ↓ / Dock 行按 ↑ → 在顶栏与 Dock 之间移动；行首行尾不会穿模到别的行

其它：
- 由于 Dock 行是同一个列表的第一行，`DockStore` 的固定 / 移除逻辑不变（追加到末尾、从 Dock 移除），
  固定 / 移除后焦点会跟到该磁贴的新位置（`AppAdapter.positionOfKey()`）
- `Dock 只能放 N 个` 的提示改为读 `AppAdapter.MAX_DOCK`（= `min(4, columns)`）
- 行高默认按 `item_app.xml` 的 78dp 磁贴 + 8dp 上下内边距 = 94dp 计算（`ROW_DP`）；
  首次布局后会用实测高度校正（`adoptMeasuredRow()`），改磁贴尺寸也不会切掉半行
- **已在真机验证**（2026-10-22 19:2x，`versionCode 3`）：收起=一行 Dock（有底板）、
  ↓ 展开成两行且 Dock 行失去底板、继续 ↓ 两行按行滚动、↑ 回到 Dock 行并收起、
  再 ↑ 回到顶栏胶囊；全程无半行、无空高亮框
- 版本号 `versionCode 3` / `versionName 2.1`，`assembleDebug` 已构建成功

### 9.3 改动文件
- `AppAdapter.java`：Dock 与网格合并成单列表、占位槽、`dockCount()/isDockPosition()/isDockRow()/positionOfKey()`
- `MainActivity.java`：`gridWindow` 两行裁剪窗口、`setExpanded()/resetWindow()/animateWindow()/animateBand()`、
  单列表键位处理 `onTileKey()`、`refreshTiles(focusKey)`
- `DockDecoration.java`：只负责 Dock 行底板，可淡入淡出，行距与网格一致（不再有额外偏移）
- `activity_main.xml`：删除独立 `dock` RecyclerView，改为 `gridWindow` + 单个 `grid`
- `app/build.gradle`：versionCode 3 / versionName 2.1

### 9.4 装机验证（待连上电视后执行）
```bash
adb connect 192.168.100.165:5555
adb -s 192.168.100.165:5555 install -r launcher/app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.100.165:5555 shell "settings put secure enabled_accessibility_services \
  com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceAccessibilityService:com.gathin.tvhome/com.gathin.tvhome.HomeKeyService; \
  settings put secure accessibility_enabled 1"
adb -s 192.168.100.165:5555 shell am force-stop com.gathin.tvhome
# 逐帧看效果：默认一行 Dock → 按 ↓ 展开两行 → 按 ↑ 收回
adb -s 192.168.100.165:5555 shell input keyevent 20   # DOWN
adb -s 192.168.100.165:5555 exec-out screencap -p > shot_expanded.png
adb -s 192.168.100.165:5555 shell input keyevent 19   # UP
adb -s 192.168.100.165:5555 exec-out screencap -p > shot_collapsed.png
```
> 若展开/收起的间距与预期不符，只需调整 `MainActivity.rowPx`（默认 94dp）与
> `bandPadPx`（默认 10dp）两处，或直接改 `item_app.xml` 的磁贴高度。

---

## 10. 本轮追加（2026-10-22 晚，翻页过渡与键位修正）

| # | 问题 | 处理 |
|---|---|---|
| 1 | 翻页是「瞬间吸附」，很生硬 | 行切换改为滑动：`MainActivity.slideRows()` 用 `RecyclerView.smoothScrollBy(0, dy)`，落点仍是精确的行边界（dy = 行号×94dp） |
| 2 | 从第 2 行到第 3 行时光标「迟疑 / 徘徊」 | 列表高度改为 **窗口 + 1 行**，下一行永远已布局，所以按下即移焦、行再在光标下滑动（原来要等滚动结束才交接高亮） |
| 3 | 行根本没动、焦点跑空（隐藏 bug） | 列表比窗口高一行后，若内容总高恰好等于视口，RecyclerView 会判定 `canScrollVertically(false)`，任何滚动都无效。**在列表尾部补两行空白占位**（`AppAdapter.realCount()` 之外的尾行），保证永远可滚 |
| 4 | 收起/展开时出现「空的高亮框」 | 高亮 `GlassView` 从 `root` 移进 `gridWindow`，与磁贴一起被窗口裁剪；另加 `glassDirty` + `OnPreDrawListener`，行吸附/窗口动画后按新布局重新贴合 |
| 5 | 光标偏慢、拖滞 | 弹簧 `stiffness 260→430`、`damping 0.58→0.82`；窗口 1↔2 行动画 `220ms→170ms` |
| 6 | 末行只有一个磁贴时，从第 2/3/4 列按 ↓ 无反应 | `onTileKey` DOWN：下方行比当前列短时落到**下一行最近的磁贴**（`count-1`），只有真的在最后一行才吞掉按键 |

其它：`AppAdapter.realCount()` 排除尾部空白行，上下左右与焦点恢复都以它为准，空白行永远不会被聚焦。
真机已验证：一行 Dock ⇄ 两行网格、跨行滑动、末行短行向下跳转、长按固定/移除后焦点跟随均正常。

---

## 11. 本轮追加（2026-10-22 夜，键位换行与「黑闪」崩溃修复）

| # | 问题 | 处理 |
|---|---|---|
| 1 | 网格里在行首/行尾按 ←/→ 出不去，只能靠 ↑/↓ 换行 | `onTileKey` 的应用行分支支持**左右换行**：行尾 → 下一行首；行首 → 上一行尾；最后一行首再往前、最后一行尾再往后则停住 |
| 2 | Dock 行要不要也支持左右换行 | **不支持**，保持「只能 ↑/↓ 进出」：Dock 行内 ←/→ 只在固定的几个磁贴之间移动，按 ↓ 才展开成网格。这样「一行 Dock ⇄ 两行网格」的进入/退出路径唯一，不会误触 |
| 3 | Dock 行不是整行时（固定数 < 4），行尾按 → 不能跳到应用 | 同上，Dock 行是一个独立区域，不参与网格换行 |
| 4 | 末行只有 1 个磁贴时，第 2/3/4 列按 ↓ 无反应 | 落点取「下一行最近的磁贴」（`count-1`），只有真在最后一行才吞掉按键 |
| 5 | **从网格固定到 Dock，整屏黑一下** | 实为崩溃：`animateBand` 的动画帧里调用了 `RecyclerView.invalidateItemDecorations()`，而滚动/布局过程中该调用会抛 `IllegalStateException: Cannot invalidate item decorations during a scroll or layout` → 进程被杀 → 桌面重启 → 黑屏一闪。改为 `grid.invalidate()`（只重绘、不触发 decorations 断言） |
| 6 | 代码清理 | 删除不再使用的 `tileView()` 辅助方法；去掉调试日志 |

键位最终规则（`MainActivity.onTileKey`）：

```
顶栏胶囊  ──↓──▶  Dock 行（收起，1 行）
Dock 行   ──↓──▶  应用行（展开，2 行）；──↑──▶ 顶栏
应用行     ──↑/↓──  逐行移动；──←/→── 行内移动，行首/行尾自动换到上一行尾/下一行首
```

真机已验证：Dock 行 → 网格 → 行内左右 → 行尾换行到下一行 → 行首换行回上一行；Dock 行内左右不进入网格；
高频 ↑/↓ 连按（压力测试）无崩溃、无黑闪。
