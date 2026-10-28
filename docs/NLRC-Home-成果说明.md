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

---

## 12. 本轮追加（2026-10-22 夜，第三方应用清理）

### 12.1 现状
设备上用户安装（`pm list packages -3`）共 21 个，按用途/证据（装机时间、今日使用时长、组件与 SDK 特征）分类后清理了 7 个。

### 12.2 已卸载（7 个，均为 `pm uninstall --user 0`）
| 包名 | 是什么 | 备份 |
|---|---|---|
| `com.xtest` | 测试工具壳（`.main` + FileProvider） | 无（卸载前未备份，属纯垃圾） |
| `com.yunxiang.tilldream` | 版本号 `v`，塞腾讯登录/网易云信推送/华为 HMS 推送/X5，从未启动 | 无 |
| `com.tv.filemanager` | 当贝文件管理器（当贝 SDK + TalkingData + 推荐位），从未启动 | 无 |
| `com.mitv.shoplugin` 9.9.9 | 电视淘宝购物插件（阿里百川） | `.backup/apks/com.mitv.shoplugin/base.apk` |
| `com.mitv.kidai` 1.9.3 | AI 儿童/宝贝虎内购内容（ubestkid SDK + 支付） | `.backup/apks/com.mitv.kidai/base.apk` |
| `com.sohuott.tv.vod.xiaomi` 6.8.2 | 搜狐视频 TV 预装（2019 装机、从未启动） | `.backup/apks/com.sohuott.tv.vod.xiaomi/base.apk` |
| `com.lutongnet.nldmxXiaomiChildren` 4.01.06 | 儿童内容包（从未启动） | `.backup/apks/com.lutongnet.nldmxXiaomiChildren/base.apk` |

### 12.3 备份与回装
- 备份位置：仓库内 `.backup/`（**已加入 `.gitignore`**，合计约 95MB，不入库）
  - `apks/<包名>/base.apk`：`adb pull /data/app/<包名>-N/base.apk` 直接拉取（本机 shell 不能 `ls /data/app`，但**能读文件**，所以 pull 可行）
  - `meta/<包名>.txt`：`pm dump` 原文（版本/签名/权限/首次安装时间）
  - `restore.md`：清单 + md5 + 回装命令
- 回装：`adb -s 192.168.100.165:5555 install -r .backup/apks/<包名>/base.apk`
- **已实测回装可用**：`com.mitv.kidai` 从备份 `install -r` → Success → 出现在包列表 → 再卸载，闭环通过。

### 12.4 保留（14 个）
- 日常在用：`com.gathin.tvhome`(桌面) `org.smarttube.stable` `com.amazon.firetv.youtube` `com.ktcp.video`(云视听极光) `com.cibn.tv`(酷喵) `com.tencent.karaoketv`(全民K歌) `com.netease.cloudmusic.tv` `com.live.zd`(小薇直播) `com.quark.yun.tv`(夸克网盘) `com.github.tvbox.osc.tk`(TVBox) `mark.via`(Via 浏览器)
- 系统组件（别动）：`com.xm.webcontent`（小米 WebView 容器）、`com.mitv.codec.update`（解码库更新）
- 已是停用状态：`com.mitv.care`（浮层+推送广告）

### 12.5 这台 ROM 的限制（重要）
`pm disable-user` / `pm disable` / `pm disable-until-used` / `pm hide` **全部被拒**：

```
Error: java.lang.SecurityException: Permission Denial:
attempt to change component state from pid=…, uid=2000, package uid=…
```

即 shell 无权改**用户安装包**的启用状态；`adb root` 也不可用（production build）。
所以本机做精简只有两条路：① `pm uninstall --user 0`（本 ROM 无 `pm install-existing`，只能靠备份/商店回装）；
② 在电视「设置 → 应用」里手动停用（可逆）。
另：之前文档里记录的那批 debloat 停用，当前只剩 `com.mitv.care` 生效，其余已被恢复。

---

## 13. 本轮追加（2026-10-23 凌晨，Wi-Fi 状态即时刷新 + HDMI 3）

### 13.1 Bug：开机后 Wi-Fi 已连上但胶囊一直显示 Offline
原因：`updateWifiLabel()` 只在 `onResume` 和每分钟的 tick 里各读一次网络，**没有任何监听**。
开机时 `BootReceiver` 先把桌面拉起来，那时 Wi-Fi 往往还没关联 → 第一次读到“无网络”就一直是 Offline（最长 60 秒）。

改法（`MainActivity`）：
- 广播监听：`CONNECTIVITY_ACTION`、`WifiManager.NETWORK_STATE_CHANGED_ACTION`、`WIFI_STATE_CHANGED_ACTION`、`Intent.ACTION_SCREEN_ON`
- `ConnectivityManager.registerNetworkCallback`：`onAvailable / onLost / onCapabilitiesChanged`
- 开机补偿：只要还是 Offline 就每 **1.2 秒**重试（最多 12 次 ≈ 14 秒），Wi-Fi 一关联立刻显示 SSID
- `currentWifiLabel()` 三级兜底：`getActiveNetwork()+getNetworkCapabilities()` → `getActiveNetworkInfo()`（API 23 老接口）→ “Wi-Fi 已关联就直接用 SSID”
- `currentSsid()` 处理 `"<unknown ssid>"`、`0x`、引号；已联网但读不到名字时显示 `Wi-Fi` 而不是 `Offline`
- `onDestroy` 注销接收器与 callback

验证：重启电视后，开机首次读到的就是 `Xiaomi_A9C9_5G`（不再 Offline）；蓝牙同步显示 `XFW-BT`。

### 13.2 HDMI 3 进 Inputs 下拉
原因：Inputs 下拉走小米自家 `MiTVTVPlayer/.ExternalSourceActivity`，靠 `input=<硬件源 id>` 切换；`config.xml` 里只写了
`source_hdmi_1=2`、`source_hdmi_2=18`、`source_av=17`，**从来没有第三个口的 id**。
（标准 `TvInputManager` 在这台 ROM 上只注册了 ATV / DTV / HDMI1 三个 input，HDMI2/3 没暴露，所以只能走小米的 id。）

改法：把 HDMI 源改成**配置数组**，按口顺序排列，下拉与 Dock 固定都自动跟随：

```xml
<integer-array name="source_hdmi">
    <item>2</item>    <!-- HDMI 1 -->
    <item>18</item>   <!-- HDMI 2 -->
    <item>19</item>   <!-- HDMI 3（待真机确认） -->
</integer-array>
```

- `MainActivity.showInputs()` 用数组循环生成 `HDMI 1..N`（占位 key `hdmi_1..N`，可长按固定到 Dock）
- `AppAdapter.reload()` 同样循环生成可固定的磁贴（HDMI 2 保留蓝色强调色）
- `HdmiHelper.switchToHdmi2()`（Netflix/Prime 磁贴）改为读数组第 2 项
- 以后加/减口只改这一个数组

**待确认**：第三个口 `19` 是推测值（与 `18` 相邻）。→ 见 **§14.1**：用 `tools/probe_hdmi.sh` 逐个试候选 id，
不用每次重编译；确认后只改数组第 3 项。

---

## 14. 本轮追加（2026-10-26：HDMI 3 逐个实测 + 开机不重画）

### 14.1 HDMI 3 的 id：改成「一个一个试」，不用每次重编译

`19` 只是按「`17`=AV、`18`=HDMI 2」推出来的邻居值，没在真机上验证过。与其每改一次 id 就
`./.build/build.sh` + `adb install -r` 一轮，不如直接用 adb 把 `ExternalSourceActivity` 一个个拉起来：
你在电视上看着画面，对了按 `y`，脚本把 id 写进 `config.xml`。

```bash
tools/probe_hdmi.sh            # 交互式逐个试（默认候选表）
tools/probe_hdmi.sh --ids 20 21 16
tools/probe_hdmi.sh --sweep    # 0..40 全扫，每个 id 存一张截图到 .build/hdmi-probe/
tools/probe_hdmi.sh --dump     # 抓现场：dumpsys tv_input / tvplayer 包路径 / 当前前台
tools/probe_hdmi.sh --watch    # 直播 tvplayer 日志，用原遥控器自己切到 HDMI 3，抓真实 id
```

单条命令等价形式（脚本内部就是它）：

```bash
adb -s 192.168.100.165:5555 shell \
  "am start -n com.xiaomi.mitv.tvplayer/com.xiaomi.mitv.tvplayer.ExternalSourceActivity \
            --ei input 19 --ez no_launch_panel true"
```

候选表的顺序和理由：

| 顺序 | id | 理由 |
|---|---|---|
| 1 | 19 | 紧挨 `18`（HDMI 2），当前配置里的推测值 |
| 2 | 20 / 21 | `18` 往后再排两个，若是「口号连续」的枚举 |
| 3 | 16 / 15 / 14 | `17`（AV）左边，若枚举顺序是 AV、HDMI 2、HDMI 3… |
| 4 | 3 / 4 / 5 / 6 | `2`（HDMI 1）右边的小号段 |
| 5 | 22 / 23 / 24 | 大号段扩散 |
| 6 | 1 / 0 | ATV / TV，用来确认「这套 id 机制还活着」而不是整条路都不通 |

脚本会自动把 `tvplayer` 自己的 logcat（`hdmi|source|InputSource|ExternalSource`）存到
`.build/hdmi-probe/log.txt`，所以即使你看不出画面差别，日志里通常也能看到它切到了哪个口。

拿到 id 后只改一处（`res/values/config.xml`，脚本会问要不要自动改）：

```xml
<integer-array name="source_hdmi">
    <item>2</item>     <!-- HDMI 1，已验证 -->
    <item>18</item>    <!-- HDMI 2，已验证 -->
    <item>20</item>    <!-- HDMI 3，实测确认 -->
</integer-array>
```

> 注意：`tools/probe_hdmi.sh` 用的是 `am start`，不受 launcher 的配置影响，所以**不用装新 APK**就能试完整个候选表；
> 只有确定 id 之后才需要重编译一次，把数组第 3 项换掉。

### 14.2 Bug：开机后桌面「先后被重画一次」

现象：开机时先看到桌面（磁贴淡入 + 壁纸），接着整屏又重来一遍，像 home 被重新加载。

三个叠加的原因，逐个修掉：

1. **direct-boot 进程被销毁后重建**（主因）
   `MainActivity` 原来是 `android:directBootAware="true"`，`BootReceiver` 又监听 `LOCKED_BOOT_COMPLETED`。
   于是系统在 user 0 还锁着的时候就画了一次桌面；一旦解锁，系统会销毁 direct-boot 进程，再用普通进程
   重建一次 Activity —— 磁贴入场动画、壁纸、胶囊全部重来。
   - `AndroidManifest.xml`：`MainActivity` 改成 `directBootAware="false"`（`BootReceiver` / `HomeKeyService`
     仍保持 true，这样锁屏态的自愈逻辑还在）
   - `BootReceiver.onReceive()`：先 `UserManager.isUserUnlocked()`，锁着就**只自愈无障碍、不拉 Activity**，
     把启动留给解锁后的 `BOOT_COMPLETED`；拉 Activity 的逻辑抽成 `launchHome()`

2. **每次 onResume 都重放磁贴入场动画**
   `onResume()` 无条件 `adapter.reload()` + `grid.scheduleLayoutAnimation()`，所以从任何 App 回到桌面、
   甚至只是窗口焦点变化，整个网格都会再淡入一次。
   - `AppAdapter.reload()` 现在返回「磁贴是否真的变了」（按 key 比对，不看 label）
   - `MainActivity` 只在**首次 resume 或磁贴真的变了**时才 `playTileEntrance()`，并在 1.5 秒后清掉
     `LayoutAnimationController`，避免之后的 layout（拖 Dock、窗口伸缩、滚动）再触发一次

3. **开机时壁纸被网络图顶掉**
   开机瞬间 Wi-Fi 还没关联，先显示内置图；Bing 列表 1~3 秒后到达，立刻交叉淡入网络图 → 看起来就像整屏刷新。
   - `Wallpapers`：进程首次上墙时间 `firstPaintAt` + `STARTUP_SETTLE_MS = 25s`，这 25 秒内
     **内置图不会被网络图替换**；到下一个 3 分钟槽位再正常轮换淡入

验证要点（重启后观察）：
- 桌面只淡入**一次**，之后不再整屏重来
- `adb -s 192.168.100.165:5555 logcat -d | grep BootReceiver` 应只有一条
  `Received boot/power broadcast: android.intent.action.BOOT_COMPLETED`，且**没有**
  `LOCKED_BOOT_COMPLETED` 之后的启动记录
- 壁纸在开机 25 秒内保持内置图，之后（分钟 tick）才换成 Bing 图

---

## 15. 真机实测：HDMI 3 的 source id 是 **25**（2026-10-26，电视在线）

### 15.1 现场证据

| 观察 | 结果 |
|---|---|
| `dumpsys tv_input` 里注册的输入 | 只有 3 个：`ATVInputService/HW0`、`DTVInputService/HW10`、**`Hdmi1InputService/HW5`** |
| `Hdmi2InputService` / `Hdmi3InputService` | 服务被 bind，但**没有注册成 TvInput**（框架里不存在） |
| `am start ... --ei input 2 / 17 / 18 / 19 / 23 / 24 / 26` | 全部 `MiTv-SourceManagerImpl: can not get input source, setCurrentSource fail` → 什么都不会切 |
| `am start ... --ei input 25` | `createSessionInternalLocked(inputId=...Hdmi1InputService/HW5)` → **出画面** |
| 你自己在「系统设置」里切到 HDMI 3 时系统发的 intent | `{act=com.xiaomi.mitv.tvplayer.EXTSRC_PLAY cmp=.ExternalSourceActivity (has extras)} from uid 1000`，落地 `setCurrentSource source=25` |

结论：**这台机器（小米电视 4A / `xmen` / Amlogic，platformid 659）上，HDMI 3 那个口对应的就是 `input=25`**，
而 `2`（HDMI 1）、`18`（HDMI 2）、`19`（之前猜的 HDMI 3）在这台 ROM 上都不存在——所以 Inputs 里的 HDMI 3 一直切不动。

原因：`/system/etc/mitv.config.xml` 里这张板子的 `source_ports_list=source_list`，
`hdmi1_order=25 / hdmi2_order=24 / hdmi3_order=23` 是「mitv 内部源号」，
但框架只把其中一个（HW5 ↔ 25）注册成可直通输入；24 / 23 没有对应 TvInput，`setCurrentSource` 必然失败。
（也解释了为什么 `2 / 18` 这两条「已验证」的记录其实是误判：那时只看到小米播放器被拉起来，并没有真的切源。）

### 15.2 改法：id + 标签都进配置，三个口都在

`res/values/config.xml` 是两个平行数组，顺序即菜单顺序（Inputs 下拉、Dock 可固定磁贴、热键都跟着走）：

```xml
<integer-array name="source_hdmi">
    <item>23</item>   <!-- HDMI 1，未验证 -->
    <item>24</item>   <!-- HDMI 2，未验证 -->
    <item>25</item>   <!-- HDMI 3，已实测 -->
</integer-array>
<string-array name="hdmi_labels">
    <item>HDMI 1</item>
    <item>HDMI 2</item>
    <item>HDMI 3</item>
</string-array>
```

- `HdmiHelper.hdmiSources()/hdmiLabels()` 统一读这两个数组；缺标签时自动回退成 `HDMI 1`、`HDMI 2`…
- `HdmiHelper.switchToHdmi(ctx, index, toast)` 是新入口；`switchToHdmi2()`（Netflix/Prime 热键）
  = 有第 2 项用第 2 项，否则第 1 项
- 23 / 24 来自 `/system/etc/mitv.config.xml` 的 `hdmi3_order` / `hdmi2_order`（内部编号和背板丝印相反），
  但在框架里没有对应 TvInput，所以现在点下去只会把当前 HDMI 会话拆掉、退回「搜索信号中」

### 15.2.1 `tools/probe_hdmi.sh --check`（逐个验证 config.xml 里的 id）

```
$ tools/probe_hdmi.sh --check
config.xml 里的 HDMI id：23 24 25
id     结果   证据
23     fail    can not get input source (框架里没有这个源)
24     fail    同上
25     OK      com.droidlogic.tvinput/.services.Hdmi1InputService/HW5 (video events: 2)
```

判定标准：日志里出现 `createSessionInternalLocked` 才算真的切到了；
`can not get input source` 说明这个 id 在框架里根本不存在（拿一个不存在的 id 如 40 试，报错一模一样）。
注意：**没插设备的口框架可能压根不注册**，所以要验证 HDMI 1 / HDMI 2，必须先往那两个口插一个开机的设备再跑一次。

### 15.3 顺带发现

- **AV（`source_av=17`）在这台机器上同样切不动**（同一个 `can not get input source`），
  因为框架没注册 AV 直通输入；要用 AV 得走 `AtvActivity`（模拟电视）那条路，后续再改。
- 找不到 id 时的排查顺序（换个机型时照做）：
  1. `tools/probe_hdmi.sh --dump` 看这台机器注册了哪些 TvInput
  2. `tools/probe_hdmi.sh --watch`，然后用原遥控器在系统设置里切一次，日志里 `setCurrentSource source=N` 就是答案
  3. `tools/probe_hdmi.sh --sweep` 暴力扫 0..40，只有真的注册了的 id 才会出现 `createSessionInternalLocked`

---

## 16. 自动输入：开机 / 插播都自动切到 HDMI 3（2026-10-28，versionCode 4 / 2.2）

### 16.1 需求

电视设置里把 HDMI 3 设成默认输入源后，每次开机仍然停在 NLRC 桌面，必须再手动选一次。
希望：**只要检测到 HDMI 3 有信号就自动切过去**，不管当前在桌面还是某个应用里；开机时也一样。

### 16.2 实现（新增 `AutoInput.java`）

`AutoInput` 是一个进程内单例，由 `BootReceiver`、`HomeKeyService`、`MainActivity` 三处
`ensureStarted()` 拉起，跑在自己的 `HandlerThread` 上；**只在开机窗口内每 2 秒检查**，窗口结束
就停，不依赖 Activity 存活：

| 场景 | 行为 |
|---|---|
| 开机（`BOOT_COMPLETED`） | 先让 `BootReceiver` 拉起桌面，10 秒后第一次检查；有信号就进，没信号继续等到 60 秒窗口结束 |
| 桌面 / 任意应用运行中 | **不再监听，也不抢屏**（最终版只做开机切换） |
| 用户按 Home 回桌面 | 不会被弹回输入源 |
| 开机窗口内手动选了别的输入源 | `AutoInput.noteSelectedSource()` 取消这次开机进入 |
| 开机窗口内只是按了遥控器 / 看桌面 | 仍按计划进入上次的输入源 |
| 息屏 | 检查延后，亮屏后继续（仍受 60 秒窗口限制） |

检测有两条独立通路（`AutoInput.readSignal()`）：

1. **小米厂商 SDK（优先）**：反射调用
   `mitv.tv.TvContext.getInstance().getSourceManager().isSourceConnected(sourceId)`。
   这是从 `MiTVTVPlayer.apk` 里实测出来的接口（它自己用同一个 `SourceManager` 的
   `onSourceSignalStable` / `setCurrentSource`；系统里预装的 Kookong TV SDK 也用它判断源是否连接）。
   源 id 与 `source_hdmi` 数组一致，HDMI 3 = `25`。
   这些 `mitv.tv.*` 类在共享库 `com.xiaomi.mitv.api` 里（`MiTVTVPlayer` / `tvmanager` 的
   manifest 都声明了 `uses-library`），普通 App 的 classloader 默认看不到，所以
   `AndroidManifest.xml` 里补了
   `<uses-library android:name="com.xiaomi.mitv.api" android:required="false"/>`；
   声明成非必需，其他品牌电视没有这个库也能安装，只是自动退回第 2 条检测。
2. **公共 `TvInputManager`（兜底）**：遍历 `getTvInputList()`，找
   `TYPE_HDMI` + `isPassthroughInput()` 的输入。真机日志显示，HDMI 3 插着时框架会注册
   `com.droidlogic.tvinput/.services.Hdmi1InputService/HW5`，拔线时 `HW5` 从列表消失
   （`TvInputManagerService: notifyInputRemovedLocked(...HW5)`），所以它本身就是可用的信号指示。
   `config.xml` 的 `auto_hdmi_tvinput_hint`（默认 `HW5`）用来确认是同一个口。

切换动作仍然复用已验证的 `HdmiHelper.createMiSourceIntent()`（`ExternalSourceActivity` +
`input=25` + `no_launch_panel=true`），不新造切换通道。

### 16.3 开关与配置

- **Inputs 下拉菜单底部新增「开机进入」行**：状态只显示 `开 / 关`，OK 直接开关，
  保存在 `SharedPreferences("home").auto_input_enabled`（默认开）。
- `res/values/config.xml`：
  ```xml
  <integer name="auto_hdmi_index">2</integer>          <!-- 0-based，2 = HDMI 3 -->
  <integer name="auto_hdmi_boot_delay_ms">10000</integer> <!-- 开机后等桌面稳定 -->
  <integer name="auto_hdmi_boot_window_ms">60000</integer> <!-- 慢机顶盒的等待窗口 -->
  <string name="auto_hdmi_tvinput_hint" translatable="false">HW5</string>
  ```
  换端口只改 `auto_hdmi_index`；换机型时用 `tools/probe_hdmi.sh --dump` 找对应的 TvInput id 片段。

### 16.4 涉及文件

- 新增：`launcher/app/src/main/java/com/gathin/tvhome/AutoInput.java`
- 修改：`BootReceiver.java`（启动 watcher + 预约开机切换）、`HomeKeyService.java`（启动 watcher、
  上报前台包名、Home 键取消开机切换）、`MainActivity.java`（启动 watcher、Inputs 菜单开关、
  按键取消开机切换）、`res/values/config.xml`、`res/values/strings.xml`

### 16.5 验证方法

```bash
# 看 watcher 是否起来、识别到哪个源
adb -s 192.168.100.165:5555 logcat -s AutoInput

# 预期：
#   started: source=25 (HDMI 3), signal=true/false, vendor=true
#   signal HDMI 3 -> true
#   switched to HDMI 3 (source 25) - signal appeared

# 场景 1：机顶盒已开，重启电视 -> 桌面出现约 10 秒后自动进上次的输入源
# 场景 2：机顶盒启动慢 -> 60 秒窗口内一旦有信号就进
# 场景 3：在输入源里按 Home -> 留在桌面，不会被弹回
# 场景 4：关闭 Inputs 菜单里的「开机进入」-> 开机停在桌面
```

### 16.6 限制

- 厂商 `isSourceConnected` 走反射，只在小米/Amlogic 这类带 `mitv.tv` 框架的机器上存在；
  其他品牌会退回 `TvInputManager` 的 HDMI 输入检测，行为可能因 ROM 而异。
- `HW5` 是这台 Mi TV 4A 的实测值；换 HDMI 口或换机型要同步改 `auto_hdmi_tvinput_hint`，
  或把它留空让兜底逻辑接受任意 HDMI 输入。
- 机顶盒如果待机时仍保持 HDMI 热插拔/信号，可能会被判定为「有信号」；此时用 Inputs 菜单里的开关
  临时关闭即可（不影响手动选择输入源）。

### 16.7 安装（沿用原有签名，可直接覆盖）

```bash
cd /home/hq/workspace/tv-vibehack
adb connect 192.168.100.165:5555
adb -s 192.168.100.165:5555 install -r launcher/app/build/outputs/apk/debug/app-debug.apk
# 覆盖安装后重新开无障碍（脚本与 §3 相同）
adb -s 192.168.100.165:5555 shell "settings put secure enabled_accessibility_services \
  com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceAccessibilityService:com.gathin.tvhome/com.gathin.tvhome.HomeKeyService; \
  settings put secure accessibility_enabled 1"
adb -s 192.168.100.165:5555 shell am force-stop com.gathin.tvhome
adb -s 192.168.100.165:5555 shell am start -n com.gathin.tvhome/.MainActivity
adb -s 192.168.100.165:5555 logcat -s AutoInput
```

### 16.8 真机验证（2026-10-28 22:11，电视在线）

装 2.2 后实测：

| 检查 | 结果 |
|---|---|
| 版本 | `versionName=2.2`（versionCode 4） |
| watcher 启动 | `vendor SourceManager ready`、`HDMI TvInputs: [.../HW5]`、`started: active=HDMI 3, signals=[...], vendor=true` |
| 模拟 `BOOT_COMPLETED` | `boot switch scheduled in 10000 ms` → 10 秒后 `switched to HDMI 3 (source 25) - boot`，前台变成 `ExternalSourceActivity` |
| 在输入源里按 Home | 回到 `com.gathin.tvhome/.MainActivity`，后续无自动切回 |
| Inputs 下拉 | UI 里能看到「开机进入」和「开」 |

#### 16.8.1 顺带修掉的 API 23 崩溃（关键）

第一次模拟开机时发现 `BootReceiver` 直接崩了：

```
java.lang.NoSuchMethodError: No virtual method isUserUnlocked()Z
  in class Landroid/os/UserManager;
  at com.gathin.tvhome.BootReceiver.isUserUnlocked(BootReceiver.java:47)
```

`UserManager.isUserUnlocked()` 是 **API 24** 才有的方法，这台电视是 **API 23**；`NoSuchMethodError`
属于 `Error`，原来的 `catch (Exception)` 接不住，所以每次 `BOOT_COMPLETED` 进程都被杀，
`launchHome()` 和 `AutoInput.scheduleBootSwitch()` 根本执行不到。这也解释了为什么之前
「开机自动进桌面 / 自动切输入源」都不稳定——不是逻辑没写，是开机广播先崩了。

修法：`BootReceiver.isUserUnlocked()` 里先判断 `Build.VERSION.SDK_INT < Build.VERSION_CODES.N`
直接返回 `true`（API 23 没有 direct boot 用户锁的概念），再调用原方法。`AutoInput` 自己的
`isUserUnlocked()` 用的是反射 + try/catch，不受影响。

#### 16.8.2 第二个崩溃：Wi-Fi 回调在子线程改 UI（真机冷启动实测抓到）

修完上面的 API 23 崩溃后，真实重启（22:19）又抓到：

```
FATAL EXCEPTION: ConnectivityManager
android.view.ViewRootImpl$CalledFromWrongThreadException:
  Only the original thread that created a view hierarchy can touch its views.
  at MainActivity.updateWifiLabel(MainActivity.java:1033)
  at MainActivity$7.onAvailable(MainActivity.java:1109)
```

`ConnectivityManager.NetworkCallback` 跑在自己的 HandlerThread 上；开机后 Wi-Fi 一关联就回调，
直接 `btnWifi.setText()` 触发崩溃，进程在 **预约的 10 秒开机进入之前** 就被杀掉，所以桌面永远
等不到 HDMI 3。修法：`MainActivity.updateWifiLabel()` / `updateBluetoothLabel()` 入口判断
`Looper.myLooper() != Looper.getMainLooper()`，是子线程就 `handler.post(...)` 回主线程再改 UI。

#### 16.8.3 开机检查跨进程恢复

即使进程在 `BOOT_COMPLETED` 和 10 秒检查之间被杀，也不能把这次开机切换丢掉：

- `scheduleBootSwitch()` 把 `auto_boot_check_at`（`elapsedRealtime`）写进 `SharedPreferences`；
- `AutoInput.start()` 里的 `resumeBootCheck()` 在新进程中读回它；剩余时间在 `-15s ~ +30s`
  内就继续等，超过 30s 视为上一次开机的残留值丢弃；
- 如果进程在开机 2 分钟内、且 HDMI 有信号，再补一个 8 秒的兜底检查；
- 成功 / 无信号 / 用户按键取消后写 `auto_boot_done=true`，避免进程重启后重复抢屏。

实测强制杀进程再拉起：

```
AutoInput: boot switch scheduled in 10000 ms
AutoInput: started: source=25 (HDMI 3), signal=true, vendor=true
AutoInput: resumed boot switch, 6197 ms left
AutoInput: switched to HDMI 3 (source 25) - boot
```

#### 16.8.4 真实冷启动结果（2026-10-28 22:26，用户确认成功）

完整 `adb reboot` 后：

```
22:26:19  AutoInput: started: source=25 (HDMI 3), signal=true, vendor=true
22:26:21  BootReceiver: Received boot/power broadcast: android.intent.action.BOOT_COMPLETED
22:26:21  AutoInput: boot switch scheduled in 10000 ms
22:26:33  AutoInput: switched to HDMI 3 (source 25) - boot
```

过程中没有 `AndroidRuntime` FATAL，前台变成 `ExternalSourceActivity`；随后遥控器发了一个
`KEYCODE_BACK` 才回到桌面（这是用户主动操作，自动进入不会在信号一直存在时反复抢屏）。
无障碍服务在真实重启后也保留了（`enabled_accessibility_services` 里仍有 `HomeKeyService`）；
只有 `adb shell am force-stop com.gathin.tvhome` 会把它从列表里去掉，测试后需要重新设置。

> 结论：电视设置里的「默认信号源」不再是必要条件；开机、待机唤醒（`QUICKBOOT_POWERON`）
> 和运行中信号出现都会走 `AutoInput`。用户 2026-10-28 晚间确认「成功了」。

### 16.9 最终形态：只做「开机进入」+ 所有输入源 + 记住上次（2026-10-28 23:10）

用户明确：**只针对开机，不要运行中自动抢屏；UI 文案要短、能看懂。**

最终行为：

- Inputs 菜单底部只有一个开关行：**「开机进入 · 开/关」**（不是“自动切换”，也不再显示
  “HDMI/AV · 开”这种长文案）；
- 记住上次输入源：`last_input_source`；
  - HDMI/AV 通过 `AutoInput.noteSelectedSource()`，Live TV 通过
    `AutoInput.noteSelectedLiveTv()` 写入 `-2`；
  - Inputs 菜单、Dock 输入磁贴、Netflix/Prime 热键都会在真正点击时记录；
- 开机（`BOOT_COMPLETED` 后 10 秒）按顺序选：
  1. 上次是 **Live TV** → 直接 `createTvIntent()` 进 Live TV；
  2. 上次的 HDMI/AV 有信号 → 进那个口；
  3. `auto_hdmi_index`（默认 HDMI 3）有信号 → 进 HDMI 3；
  4. 其余 HDMI/AV 按配置顺序第一个有信号的；
  5. 都没有 → 在 60 秒窗口内继续等（机顶盒启动慢也能等到），窗口结束就停在桌面。
- **窗口结束后 `poll` 不再重排**，运行中没有任何 2 秒轮询、不会在桌面/应用上抢屏；
- 窗口内只是按遥控器不会取消；只有手动选了别的输入源才取消（`noteSelectedSource` /
  `noteSelectedLiveTv` 里的 `dropBootCheck()`）。

UI 真机 dump：

```
Inputs
HDMI 1
HDMI 2
HDMI 3
AV Input
Live TV
开机进入        开
```

开机真机日志（`auto_input_enabled=true`）：

```
AutoInput: started: active=HDMI 3, signals=[HDMI 1=off, HDMI 2=off, HDMI 3=on, AV Input=off], vendor=true
AutoInput: boot switch scheduled in 10000 ms (window 60000 ms)
AutoInput: boot check: active=HDMI 3, signals=[...], enabled=true
AutoInput: switched to HDMI 3 (source 25) - boot
```

`HdmiHelper.autoSources()` = HDMI 1/2/3 + AV，负责信号检测；Live TV 不参与信号检测，只参与
“上次是它就开机恢复”。`TvInputManager` 兜底只对 `auto_hdmi_index` 那个口有效（`HW5`），
厂商 SDK 可用时（本机 `vendor=true`）所有 HDMI/AV 都走 `isSourceConnected(sourceId)`；某个
id 不被支持时只跳过该口（`sourceFailed[]`）。

限制：这台 ROM 的 `dumpsys tv_input` 目前只有 `Hdmi1InputService/HW5`（HDMI 3）。HDMI 1/2 的
source id 23/24、AV 的 17，要真的插上设备后才能验证是否能切；通用逻辑会检测并尝试，但没设备
无法提前保证。

> 另：实测这台电视的「网络调试」**重启后会关闭**（`adb reboot` 后 5555 拒绝连接），
> 普通 App 无法可靠地自行打开 TCP ADB（需要写 `service.adb.tcp.port` / 厂商网络调试开关，
> 属于系统权限）。需要远程调试时仍要手动开一次；这与开机进入无关。
