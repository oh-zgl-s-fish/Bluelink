# Bluelink · 全 App 只读 UI 审计与调整方案（md3-audit-2）

- **审计对象**：Bluelink **v0.5.11 终态**实际 UI 代码（可编译），重点覆盖 md3-audit（v0.5.1a）之后 v0.5.7–0.5.11 新增页与交互（个性化页 / 壁纸 / 设置页五区 / 关于页收集日志两段式 / 容器透明度滑杆）；旧审计结论以代码现状为准复检。
- **页面清单（审计范围）**：`ui/MainScreen.kt`（MainTopBar / 主页两态 MainPage / SelfDevicePane / ScanListPanel / DeviceRow / NetworkBadge / LanStatusLine / PeerDevicePane / BottomActionRow / TimeFlowPanel / EventRow / LogPage / AboutPage / AboutLinkRow / AppDrawer / PermissionBanner / BluetoothOffBanner / EmptyState / DeviceDetailSheet / 六类 AlertDialog / NetPairingDialog / PinStageColumn / BroadcastBreathButton）、`ui/SettingsPage.kt`（五区 SettingsGroup / SegmentedButton×2 / Switch / 破坏性确认弹窗 / 权限清单行）、`ui/personalize/PersonalizePage.kt`（ColorSectionRow / FamilyEntryStrip / FamilyDotsRow / ConcreteColorRow / AccentSwatch / WallpaperPickEntry / SceneSwitchRow / SceneDotButton / PreviewSection / MaskRow / ContainerTransparencyRow / WallpaperSourceSheet）、`WallpaperBackdrop.kt`、`WallpaperStore.kt`、`ui/theme/{BluelinkColor,Dimens,BluelinkTheme}.kt`、`ui/BluelinkUiState.kt`、`ui/BluelinkRoot.kt`、`MainActivity.kt`。
- **审计基准**：`/srv/android/material-design-3-ui-skill-main`（SKILL.md v1.1.0 + `references/` 13 篇：color-system / component-selection / spacing-and-layout / typography / shape-and-elevation / motion / navigation / adaptive-design / feedback-and-overlays / m3-expressive / anti-patterns / accessibility / forms-and-input）——逐篇通读。
- **性质**：只读审计 + 方案文档；**未修改任何应用代码、未构建、未下载依赖、未联网**；唯一写入物为本文件。
- **产出物定位**：供后续实现阶段直接执行的决策交接文档；实现顺序见 §6。

> **结论一句话**：md3-audit 的 P0 地基（语义色 token 双 scheme / 扩展 success-warning 对 / Spacing·Shape·Motion token / 减动效）已**全部落地且执行得相当干净**——全工程 UI 层（MainScreen/SettingsPage）零散落裸 hex、零 tween 字面量、圆角间距全走 token、列表已去卡片化、icon-only 控件已具名、呼吸动画已 gate 减动效；**本轮剩余缺口集中在 v0.5.7–0.5.11 新增「无字图形选择控件」的深浅色对比（浅色主题白系色点 / 场景圆钮 figure-ground <3:1，P0）、HOME 浮层化+透明度滑杆的可读性无下限（P1）、自制单选控件缺 role/radio 组语义、个性化草稿丢稿与两段式收集跨页丢窗口等**。主体已接近「可放行」，P0 收尾 + P1 六个点 + P2 打磨后即可达标。

---

## 1. 审计基准与范围

- Skill：`SKILL.md` v1.1.0（2026-08-17）+ `references/` 13 篇逐篇通读（提炼清单见 §2.0；决策要点与旧 md3-audit §1 相同，不重复摘录）。
- 旧审计承接：`docs/md3-audit.md`（v0.5.1a）的 **结构** 与已下发的 P0–P2 任务清单作为「前序结论」，本次对其逐项做**代码现状复检**（§2.1 变化对照），并对新代码给出新证据。
- 既有设计定稿：`docs/ui-design.md` §4.1（主页面两态）/ §4.1b（个性化页 v0.5.8 定稿）/ §4.2（抽屉）/ §4.8–4.9（设置/权限页）/ §4.10（外观草案）/ §5（全局交互）/ §6（ADR）——审计发现与定稿冲突处标注「与规范冲突：xxx」。

### 2.0 决策要点核对清单（skill 提炼，供逐节对照）

语义色层级（reference→semantic→component）、on* 成对、深浅双验、禁止纯色表状态；组件按行为不按外观（列表≠逐行卡片、单选≠三按钮、破坏性要显式）；4dp 节奏间距、shape scale 不最大圆角、elevation 只强化物理分离；type 五组语义角色、可缩放不裁切；动效须 gate reduced-motion、utility 快而轻；目的地与动作分离、导航选择持久；反馈就近可恢复、模态不叠模态、成功反馈与重要性相称；空态给下一步动作；触达 ≥48×48、正文 ≥4.5:1、图形 ≥3:1、icon-only 须具名、自定义控件须 role/state/name/focus/target、非色双通道、liveRegion 宣告、焦点可见、文本缩放保护；表单立即 vs 事务区分、字段常驻 label、错误可恢复保留输入。

### 2.1 现状变化对照（md3-audit(v0.5.1a) → 本次 v0.5.11 复检）

| md3-audit 缺口 | v0.5.11 现状 | 复检结论 |
|---|---|---|
| C1 默认紫主题 / 无 token 文件 | BluelinkColor.kt 全量 light/dark scheme（品牌蓝 seed）+ 扩展 success/warning 对 + MaterialTheme.extended | ✅ 已落地（P0-1） |
| C2–C4 5 处裸 hex 文字/徽标/色点 | MainScreen/SettingsPage **零裸 hex**；同网/网络=extended.success、传输/握手=primary、蜂窝/未知=surfaceVariant 中性对、徽章三档 token 对 | ✅ 已落地（P0-2/P1-4） |
| C5 权限横幅 errorContainer 过重 | PermissionBanner：容器=surfaceContainer（中性）、文字+点=error | ◐ 部分（文字仍 error，见 C6） |
| C6/C7 emoji 双通道 | StatusDot/NetworkBadge/LanStatusLine 已 icon(点)+label+token；事件行=色点+文字 | ✅ 已落地 |
| K1 列表逐行 OutlinedCard | ScanListPanel=Surface 整块 + DeviceRow(ListItem + HorizontalDivider) | ✅ 已落地（P1-2） |
| K2/K3 ☰× 无 description | ☰/× 均已 contentDescription；× 进 IconButton | ◐ 具名 ✓ 但仍为字形非 Icons（K4） |
| K4 PIN 三态 3×OutlinedButton | SettingsPage 安全区 SingleChoiceSegmentedButtonRow | ✅ 已落地 |
| K6 sheet「关闭」filled | DeviceDetailSheet 底部仍全宽 Button「关闭」 | ◐ 未处理（K3） |
| K8 全宽 TextButton「刷新网络」 | 仍全宽 TextButton（本端卡内） | ◐ 未处理（保留意见 P2） |
| K11 设置页裸 Column | SettingsPage 五区 SettingsGroup（surfaceContainerLowest 分组容器 + Divider 分节） | ✅ 已落地 |
| K12 清除配对无确认 | OutlinedButton(error 边框/字色) + AlertDialog 确认 | ✅ 已落地 |
| K10/F7 空态纯文案 | EmptyState：四态文案 + OutlinedButton 下一步动作 | ✅ 已落地 |
| S1 离群间距 | SpacingTokens 4dp 节奏全量；徽章 2dp / 抽屉头 20dp / 分割 1dp 等注明例外保留 | ◐ 例外需复审（S1） |
| S4/A8 58dp 时间列缩放保护 | MetricTokens.TimeColumnWidth=58，**仍无 2x 保护** | ◐ 未收（S3） |
| S6 色点 6dp | EventDot=8dp / AdvertiseKnobDot=8dp | ✅ 已落地 |
| S7/S8 shape/elevation | ShapeTokens 两档（小件 8 / 块级与浮层 10）显式接线；全 App 无 elevation/无阴影 | ✅ 已落地 |
| M1 无限脉冲无豁免 | PulseRing 已移除；呼吸钮 gate `advertisingWanted && !reduceMotion` | ✅ 已落地 |
| M2/M3/M5 动效字面量 | MotionTokens 全量；layoutSpec/crossfadeSpec 减动效直切 | ✅ 已落地 |
| M4 自动滚顶打断阅读 | TimeFlowList 近顶（firstVisible≤2）才 scrollToItem(0) | ✅ 已落地 |
| N2 抽屉无 icon | 仍无 icon（4 项纯文字） | ◐ 未处理（N1） |
| F2 sheet 叠 dialog | DeviceDetailSheet 内事务弹窗仍与 sheet 同屏（dialog 盖 sheet） | ◐ 未处理（F1） |
| F3 Toast→Snackbar | Engine/MainScreen 统一 SnackbarHost；AboutPage 导出/外链失败仍 Toast | ◐ 部分例外（F2） |
| F5 liveRegion | 无（流程/阶段行仍无宣告） | ◐ 未处理（A8） |
| A1–A10 无障碍 | contentDescription 全覆盖、破坏性确认、减动效、对比度 token 化 | ◐ 主要完成；新缺口见 A2/A3/A6/A8/A10 |
| K5/K7/K9/N1/N4/F4/F6/F8/T1/T2 等符合项 | 均保持 | ✅ |

### 2.2 token 体系现状（v0.5.11）

- **语义色**：`BluelinkColor.kt` Light/Dark 全量 role（含 surfaceContainerLowest..Highest、surfaceDim/Bright、outline/outlineVariant、inverse*、scrim）+ `BluelinkExtendedColors`（success/warning 全家族）经 `LocalExtendedColors` 下发（`MaterialTheme.extended`）；`withAccentPrimary`（v0.5.8 UI1b-B2）运行态强调色→primary 系四元组覆写（亮度启发式 clamp）。
- **尺度/形状/动效**：`Dimens.kt` SpacingTokens（4/8/12/16/24/32）、ShapeTokens（xs4/sm8/md12/Modal10）、MotionTokens（Short200/Medium350/Long650/Fast400/Gentle450/Pulse1300/Ring1000/Breath 3200-1900-80-120 + Reduced0 + layoutSpec/crossfadeSpec）、MetricTokens（58/360/24/30/8/20/48dp 等非节奏内容度量）。
- **主题接线**：`BluelinkTheme(darkTheme, accent)` → MaterialTheme（shapes large/extraLarge=Modal10；typography 默认五组）+ Provide `LocalEffectiveDark`/`LocalExtendedColors`；MainActivity 持 themeMode（三态持久化）+ accent state，edge-to-edge 系统栏随 themeMode 重设。
- **浮层/壁纸**：HOME 内容容器与顶栏 `surfaceContainer* .copy(alpha=containerAlpha)`（透明度 5–50% 运行态，默认 0.80）；壁纸根层 WallpaperBackdrop（surfaceVariant 遮罩 0–80%）。

---

## 3. 分主题审计（13 节；缺口行 = 现状→缺口→建议→优先级）

### 3.1 color-system

| ID | 现状（证据） | 缺口 | 建议改法 | 优先级 |
|---|---|---|---|---|
| C1 | 场景三钮无字圆钮填充 = 固定灰阶 `SCENE_DARK_GRAY #3A3A3A` / `SCENE_LIGHT_GRAY #E8E8E8`（PersonalizePage.kt:744-747，注释自声明「数据色，非语义 token」），直接铺在页面 background（浅 #FDFBFF / 深 #111318）上；未选环 = outlineVariant 1dp（:714）。**对比实测**：浅灰钮 on 浅底 **1.19:1**；深灰钮 on 深底 **1.63:1**（附录 7.2）——每主题下必有一个「同色调」场景钮几乎不可见（深色主题下深灰钮、浅色主题下浅灰钮），而圆钮本身是需被识别出的 UI 控件（≥3:1） | 深浅主题 figure-ground 硬伤（v0.5.8b 新增无字钮） | 两方案取一：①灰阶改随主题的双 token 常量（浅色主题用中灰系 #6E6E6E 类作「浅色」语义、深色主题把「深色」语义灰提亮到 ~#565656 并让「浅色」保持亮灰）→ 两档同源 `MaterialTheme` surfaceVariant/onSurfaceVariant 派生；②未选态外环由 outlineVariant 1dp 升为 **outline 2dp**（浅 #74777F / 深 #8F9099，≥2.5–3:1 级）兜底可辨 | **P0** |
| C2 | 具体色点行：白系（FAMILY_WHITE v=0.87–0.98，PersonalizePage.kt:1130）10 个近白色圆点在**浅色主题**底色 #FDFBFF 上 1.06:1（附录 7.2），未选外环 outlineVariant 1dp 仅 1.66:1——浅色主题下白/浅灰系基本「盲选」（只能按位置点）；灰/黑系 OK（11–20:1）；深色主题白系 OK | 色板为数据色可豁免 token 规则，但「可选中的 UI 控件」仍需 ≥3:1 图形对比 | 白/浅色系在浅色主题：未选环改 **outline（或 onSurfaceVariant）2dp**，或色点外再加 1dp 深描边（描边收进视觉 40dp 内不胀尺寸）；FamilyDotsRow 同款共用 | **P0** |
| C3 | 彩虹钮（WallpaperPickEntry 8 色 sweepGradient，:633-661）与 13 色系代表色点 = 装饰/取色数据（色板数据、非语义色） | **豁免成立**：色板数据与装饰渐变属「用户可选数据值/装饰」，与语义 token 分层（PersonalizePage.kt:1104-1112 注释已声明）——不视为裸 hex 违规 | 保留；建议彩虹钮在浅色主题外环同样用 outline 2dp 保证 3:1（目前 48dp 无环、纯色块贴浅底时可辨性依赖本身彩色，基本达标，P2 复核即可） | P2（复核） |
| C4 | HOME 浮层容器 alpha 运行态 0.50–0.95（WallpaperStore.containerTransparency 5–50% → containerAlpha，WallpaperStore.kt:103-129）；5 个容器 + 顶栏 = `surfaceContainer* .copy(alpha=containerAlpha)`（MainScreen.kt:413,776,882,1098,1253,1347,1932,1965）叠在任意壁纸 + surfaceVariant 遮罩（0–80%）之上；无壁纸回纯色 | 极端组合无下限：透明度 50%（alpha 0.50）+ 深色壁纸 + 遮罩 0% → 容器混色后背景中暗，onSurface #1A1C20 文字对比可跌至 ~2:1 级不可读；无任何钳制/警示。与规范冲突：docs §4.1b 原文建议「alpha≈0.88–0.92，具体以文字可读为准」，实现默认 0.80、下限 0.50 已低于该区间；注释称「至少保持半透明基底可读」但无机制保证 | ①透明度滑杆下加「对比警示」或在 50% 端标注「过高可能影响文字可读」；②或容器文字/图标随 alpha 联动切 onSurface 深/浅（复杂，不推荐）；③最小可读底线建议：容器 alpha < 0.6 时要求遮罩 ≥ 某阈值联动提示；④至少保留默认 0.80 并文档化「0.50 档为风险区」 | **P1** |
| C5 | `withAccentPrimary` 亮度启发式派生（BluelinkColor.kt:160-209）：暗主题 `onPrimaryContainer` 只保证 ≥0.6 lum、`primaryContainer` ≤0.16 lum → 中亮度强调色（lum≈0.6）下最坏 **3.1–3.4:1**（附录 7.2），低于正文 4.5:1；「已连接」状态徽标（primaryContainer/onPrimaryContainer，MainScreen.kt:1174）为 labelSmall 消费点 | 派生对未按 4.5:1 钳制（启发式无色调表，边缘档可破线） | 收紧暗主题钳制：primaryContainer maxLum 0.14、onPrimaryContainer minLum 0.66（保证 ≥4.5:1 余量）；或实现期对选色后的两对做一次性实测校验并微调 | P2 |
| C6 | PermissionBanner 仍以 error 色文字+点表「需要权限: 蓝牙+位置」（MainScreen.kt:1922-1955）；但缺权限=「待动作/阻塞可恢复」而非「已出错」（md3-audit C5 遗留） | error 语义日常化，稀释真实错误识别 | 文字/点改 `secondary`（或保留 error 但文案明确「去授权」可恢复——现已有按钮）→ 建议 secondary + 图标双通道 | P2 |
| C7 | 徽标/状态三档 token 对：同Wi-Fi/接入=successContainer 对、已连接=primaryContainer 对、蜂窝/未知/未连接=surfaceVariant 对（MainScreen.kt:1016-1050,1170-1178）；statusColor 行（SettingsPage PermCheckItem ✓/✗+success/error，:566-576）双通道 | 符合（色不单独表状态；深浅各派生） | 保留 | — |

### 3.2 component-selection

| ID | 现状（证据） | 缺口 | 建议改法 | 优先级 |
|---|---|---|---|---|
| K1 | 无字圆钮组（场景三钮 SceneDotButton 56dp 触达/52 视觉、色系入口 FamilyEntryStrip 48、色点 AccentSwatch 48/40、取色钮 48）均为自制「点选单选」控件：已有 contentDescription+`selected`（PersonalizePage.kt:476-477,598,706）但**无 role、无 selectableGroup 容器**——TalkBack 播「双击激活」而非「单选按钮/选中态」 | 自制单选控件缺 role/state 语义规范（skill：custom controls 须 role/state/name/focus/target） | 色点/色系点/入口钮加 `role = Role.RadioButton`，所在 LazyRow/行容器加 `selectableGroup`；场景三钮同为单选组（或语义 role=Button+selected 亦可，读屏至少能报 selected） | **P1** |
| K2 | 广播呼吸钮：`clickable(role=Role.Switch)` + `stateDescription 广播开启/广播停止`（MainScreen.kt:637-644），无**名称**（旁边「广播/停止」Text 为独立节点，未绑定）；未用 toggleable/checked | 读屏孤立节点可播「开关，广播开启」，但缺 name 与 checked 位 | 加 `contentDescription="广播"`（或语义 name）并补 `checked=advertisingWanted`（toggleable 双态更规范） | P2 |
| K3 | DeviceDetailSheet 底部「关闭」= 全宽 `Button`（filled，MainScreen.kt:2179-2182）；区内主操作「组网/同网直连」也是全宽 Button——同视区两个 filled | 关闭=低风险 dismiss，filled 强调过重且与主操作抢层级（md3-audit K6 遗留） | 「关闭」改 TextButton（右对齐）或 OutlinedButton；filled 只留主操作 | P2 |
| K4 | 「×」移除设备=字形 Text 在 IconButton 内（MainScreen.kt:998-1003，contentDescription 已具名）；☰ 菜单同（:430-433） | 字形图标跨字体/渲染（TalkBack 拼读字形）不稳定；M3 建议 Icons.* 矢量 | 换 `Icons.Filled.Close` / `Icons.AutoMirrored.Filled.Menu` + 保留 contentDescription | P2 |
| K5 | 滑块两处（遮罩 0–80%、容器透明度 5–50%）均为 M3 `Slider`（PersonalizePage.kt:883,919），steps 离散步进、% 文本随行、label 常驻 | 符合（滑块即 M3；离散 bounded 输入用 Slider 正确） | 保留 | — |
| K6 | 热点组：SSID/密码两字段 + Switch「组网时自动用预设」+ 显式「保存预设」按钮（SettingsPage.kt:313-340）——多字段一事务 + 显式保存 | 符合 forms-and-input（事务组保存钮正确，Switch 非「立即生效」是组内草稿——文字与保存钮已表明） | 保留 | — |
| K7 | 破坏性动作：清空全部配对/重置本端指纹 = OutlinedButton(error 边框字色) + AlertDialog 确认（SettingsPage.kt:253-260,477-533） | 符合（显式、防误触、error 语义、恢复路径） | 保留 | — |
| K8 | 行条目自绘：AboutLinkRow（整行 clickable，MainScreen.kt:1756-1791）、SourceSheetOption（PersonalizePage.kt:985-1014，已 role=Button）、SettingsPage 权限/配对行（Row+TextButton）——样式近似列表行但非 ListItem | 部分（SourceSheetOption 有 role；AboutLinkRow/权限行 clickable 无 role） | AboutLinkRow 等加 `role=Role.Button`（A6 合并处理） | P2 |
| K9 | 主页面底部动作行：发送=OutlinedButton、收尾/取消=TextButton 动态切换（MainScreen.kt:1250-1300）；对端卡：重新扫描=Outlined、组网/直连=filled Button 主操作 | 符合（主次分明；收尾为事务性次要操作用 TextButton 可接受） | 保留 | — |

### 3.3 spacing-and-layout

| ID | 现状（证据） | 缺口 | 建议改法 | 优先级 |
|---|---|---|---|---|
| S1 | 全 App 间距主走 SpacingTokens（4dp 节奏）；非 4dp 残留：徽章内 padding `vertical=2.dp`×2（MainScreen.kt:1040,1139，注明「内部留白例外」）、抽屉头 `vertical=20.dp`（:1893，注明例外）、竖分割线 `width(1.dp)`、PersonalizePage 固定列宽 48/96/44dp（:881,931,894）与圆钮环尺寸 2/1dp、色区高度 80–128dp（:273） | 少数例外已有注释声明属「内容度量/内部留白」——可接受但建议集中登记 | 保留原值，把 1/2dp、48/96/44dp 移入 MetricTokens 或加「非间距 token」注释（文档级收口，不改布局） | P2 |
| S2 | MaskRow/ContainerTransparencyRow 标签与 % 用固定宽文本列（48dp「遮罩」/96dp「容器透明度」/44dp 百分比，PersonalizePage.kt:869-938） | 大字 2x 下 CJK 行会换行/错位（96dp 放不下 5 字 ×2x labelLarge）——布局跳变风险 | 列宽改 `widthIn(max=…)` + Text maxLines=1/ellipsis，或行改 Column 两行堆叠（竖排 label 上、slider 下）自适应 | P2 |
| S3 | 时间列固定 `MetricTokens.TimeColumnWidth=58dp`（MainScreen.kt:1396,1403）+ 事件文本 maxLines=1 | md3-audit S4/A8 遗留：labelSmall monospace 8 字符 2x 超 58dp（~104dp 需求）会折行/挤占 | 58dp → `8ch` 估算（maxLines=1 + 不换行溢出保护）或字号随缩放收紧；事件长文本可点开全文（P2 可展开已在计划） | P2 |
| S4 | 个性化页 = 无上下滚动一屏（BoxWithConstraints weight 布局，PersonalizePage.kt:256-363；颜色区收 80–128dp + 场景钮 56 + 标题 + 遮罩/透明度两行固定高），无 verticalScroll | 与规范冲突：docs §4.1b「无上下滚动一屏放完」在系统大字 1.3x+ 或矮横屏下内容溢出（weight 预览区被压到 0，仍可能溢出）——一屏布局以默认字号为假设 | 保持一屏规格（默认字号下成立）；补「内容超高时预览区收缩 + 页面允许滚动」兜底或 fontScale≥1.3 时切换可滚动（最省事：外层加 verticalScroll + 内部 heightIn 下限，冲突最小化） | P2 |
| S5 | 主页两栏/横幅/时间流区块间距（SpaceMd 12 / SpaceLg 16）+ 底部 inset 由 Scaffold 承担 | 符合 | 保留 | — |

### 3.4 typography

| ID | 现状（证据） | 缺口 | 建议改法 | 优先级 |
|---|---|---|---|---|
| T1 | type 全走 M3 语义角色：headlineSmall/Large、titleLarge/Medium/Small、bodyLarge/Medium/Small、labelSmall/Medium/Large、displayMedium（PIN 码）、Monospace 时间/码字（MainScreen.kt:430-460,1400,2516-2525 等）；无字号 DIY、无固定高容器（maxLines+ellipsis 为主） | 符合（含 displayMedium 大号码字——正用「强调数据」语义） | 保留 | — |
| T2 | 字形当图标/状态字形：☰（:430）、×（:1001）、›（:1744）、✓/✗（SettingsPage statusText，:566-576）；大多已配 contentDescription 或为文本双通道 | 字形非矢量图标：跨字体渲染不一致、TalkBack 拼读字形（×/› 会读「乘号/大于号」） | ☰/×/› 换 Material Icons（同 K4）；✓/✗ 属「文本状态字形+颜色双通道」可保留（或换 Check/Close 图标 + 文字） | P2 |
| T3 | 徽标 labelSmall、状态 labelMedium、元数据 bodySmall/onSurfaceVariant 用法一致；无「字号造层级」反例 | 符合 | 保留 | — |

### 3.5 shape-and-elevation

| ID | 现状（证据） | 缺口 | 建议改法 | 优先级 |
|---|---|---|---|---|
| SH1 | 圆角 token 两档：小件 8（shapes.small：徽章/按钮/输入框/行点击 ripple）、块级内容容器与浮层面板 10（shapes.large/extraLarge=ShapeTokens.Modal，BluelinkTheme.kt:85-95）；无 elevation/无装饰阴影（全 App 容器 Surface 均不设 elevation）；呼吸 glow 为功能性贴钮光晕（3dp，非 box-shadow 滥用） | 符合（未最大圆角、未胶囊化、无阴影滥用；圆形仅用于真圆形钮/点语义） | 保留 | — |
| SH2 | 选中/未选环为字面量尺寸/厚度：2dp primary 环 / 1dp outlineVariant 环、环底衬 44/42、圆点 40、场景钮 56/54/52（PersonalizePage.kt:472-497,589-618,702-725） | 新控件「选中环」视觉语言无 token（2/1dp、44/42 系列散落 3 处） | 提炼 SwatchTokens（选中环厚 2dp/未选 1dp、底衬尺寸、外环色档：selected=primary、normal=outlineVariant、**highContrast=outline**——C1/C2 修复共用此档） | P2 |

### 3.6 motion

| ID | 现状（证据） | 缺口 | 建议改法 | 优先级 |
|---|---|---|---|---|
| M1 | 动效 token 化 + 减动效：两栏宽度=layoutSpec(reduceMotion)、面板 Crossfade=crossfadeSpec（MainScreen.kt:703,721）；广播呼吸 gate `advertisingWanted && !reduceMotion`（:547-575）；PulseRing 已移除；延迟/缓动全走 MotionTokens（BreathPeriod/Expand/Press* 等） | 符合（旧 M1-M6 全部落地；无字面量动画） | 保留 | — |
| M2 | 呼吸钮唯一「无限动画」：3.2s 非对称 keyframes 1.0↔1.3 + 3dp 光晕（MainScreen.kt:530-665） | 装饰性但克制（幅度 26dp 峰 < 命中区、仅外扩无晃动、点击回弹 80/120ms）；有 reduceMotion 静止分支——符合 motion「克制/utility 快」 | 保留；2x 用户若不适可在后续把「呼吸」并入减动效判据（不止 scale==0，含 0.5 档）——P2 记录 | P2 |
| M3 | 抽屉路由切页（when currentPage：LOG/个性化/设置/关于）为直切无过渡；主页面两态 Crossfade 已有 | 页面身份切换无任何过渡（skill：导航过渡保持空间连续性） | 路由容器套 AnimatedContent/Crossfade（fade 150–200ms，token 化 + reduceMotion 直切）——低优先 | P2 |
| M4 | 保存/取色/移除等一次性反馈无动画（Snackbar 系统自带） | 符合（不为反馈加 spectacle） | 保留 | — |

### 3.7 navigation

| ID | 现状（证据） | 缺口 | 建议改法 | 优先级 |
|---|---|---|---|---|
| N1 | 抽屉 4 栏（文件传输记录/个性化/设置/关于）NavigationDrawerItem（MainScreen.kt:1900-1925）selected 由 currentPage 驱动、目的地顺序稳定、跨页持久 | 无 icon（md3-audit N2 遗留）；纯文字行扫读慢 | 每项配 Material 图标（History/Palette/Settings/Info）+ 可选 trailing badge（如记录数） | P2 |
| N2 | 顶栏：☰（开抽屉）最左 + 应用名「蓝鲸·X」（primary titleLarge）——非主页时应用名可点返回主页（contentDescription「返回主页」，MainScreen.kt:438-455）；主页时不可点 | 自定义「标题即返回」导航：发现性弱（无下划线/图标指示），与 ☰ 抽屉、页内「返回」按钮三入口并存 | 保持（v0.5.11 UI1b-E 改① 已拍板）；建议非主页时标题前置「←」图标强化返回语义（可选） | P2 |
| N3 | 无 bottom bar/rail/tab 滥用、无 bar+rail+drawer 同屏；抽屉+各页「返回」回主页面闭环成立（个性化页补了返回避免死胡同） | 符合 | 保留 | — |

### 3.8 adaptive-design

| ID | 现状（证据） | 缺口 | 建议改法 | 优先级 |
|---|---|---|---|---|
| AD1 | 主页两栏 1/3|2/3 ⇄ 1/2|1/2（Row weight 动画）+ 时间流 fillMaxHeight(0.45f)（MainScreen.kt:757）——实机验证档 | 0.45f 无 heightIn(max) 上限（md3-audit P2-5 遗留）：矮屏/横屏主任务区被挤；无 compact 断点验证清单 | 时间流加 `heightIn(max=…)`（如 max 260dp）+ 横屏/360dp 宽验证；不推翻实机定稿 | P2 |
| AD2 | edge-to-edge：MainActivity enableEdgeToEdge 透明 + themeMode 变化重设系统栏图标明暗（LaunchedEffect）；内容 insets 归 Scaffold/TopAppBar/弹层系统处理（MainScreen.kt:290-300 注释），页面内无重复 padding | 符合（系统栏/insets 处理正确） | 保留 | — |
| AD3 | 个性化页固定一屏布局（同 S4）；设置/关于/记录页 verticalScroll 正常 | 见 S4（横屏矮高溢出风险） | 见 S4 | P2 |
| AD4 | 主页/设置内容为单列 fillMaxWidth 平铺，宽窗（平板/桌面 840dp+）无阅读宽度上限、无多栏增强 | 宽窗仅拉伸（skill：宽屏须改善上下文/生产率，不只拉长） | 内容列 `widthIn(max=840.dp)` + 居中；后续多窗格（设备列表+详情）进路线图 | P2 |

### 3.9 feedback-and-overlays

| ID | 现状（证据） | 缺口 | 建议改法 | 优先级 |
|---|---|---|---|---|
| F1 | DeviceDetailSheet（ModalBottomSheet，MainScreen.kt:2067-2110）内发起组网/配网事务 → 同组合层级后随渲染 ManualPwdDialog/JoinFailDialog/WriteSettingsDialog/LocalOnlyPwdDialog 等 AlertDialog——dialog 视觉盖在 sheet 之上 | 模态叠模态（md3-audit F2 遗留）；焦点/层级复杂 | 事务弹窗提升到 sheet 外层（BluelinkRoot 层按 ui 状态渲染，或事务触发先关 sheet）——低优先 | P2 |
| F2 | Toast 残留 3 处：收集日志导出完成（MainScreen.kt:1553,1591）、外链打开失败（:1805）；其余反馈统一 SnackbarHost | Toast 非 M3 通道（旧 F3 部分遗留）；导出路径依赖 SAF launcher 回调、外链为系统 Activity——Snackbar 上下文不连续，可作工具性例外 | 能改的改 ui.showSnack；launcher 回调路径注明「工具性例外」 | P2 |
| F3 | NetPairingDialog 极简进度弹窗：进行中禁 dismiss（onDismissRequest 仅终态生效，MainScreen.kt:2537-2540）、终态错误保留+关闭、ThinProgress=LinearProgressIndicator indeterminate（:2551-2556）、PIN 大号码字 displayMedium | 符合反馈规范（阻断度与重要性相称、不可知进度用 indeterminate、错误就近可恢复）；唯一缺口=阶段行无 liveRegion（见 A8） | 见 A8 | P2 |
| F4 | 关于页「收集日志」两段式：collecting/collectStart 为 AboutPage 局部 remember（MainScreen.kt:1537-1577）——用户「开始收集」后切页（路由切走 AboutPage 出组合）→ 窗口静默丢失（collecting 复位、collectStart 丢），再次进入点「收集日志」重新开始；无 busy 保护（导出 IO 期间可再点） | 两段式状态机生命周期与页面组合耦合 → 跨页丢窗口（v0.5.10 新增交互） | 把 collecting/collectStart 提升到 MainScreen（随 aboutLogUnlocked 同级）或 ui 层 state；导出期间加 busy 禁点 + 进度反馈 | **P1** |
| F5 | Snackbar 一次性信号通道（ui.snackbarMsg → host，MainScreen.kt:215-223）；空态四态可操作（EmptyState + OutlinedButton）；错误就近（字段 supportingText 红字 / 弹窗内错误行 + 重试按钮）；收集中状态行文本双行（标题色变 primary + 说明句） | 符合 | 保留 | — |

### 3.10 m3-expressive

| ID | 现状（证据） | 缺口 | 建议改法 | 优先级 |
|---|---|---|---|---|
| E1 | 表达性盘点：广播呼吸钮（尺度 1.3 + 光晕 3dp + 非对称节奏 + 点击回弹）为全 App 唯一「表达性」时刻，且克制度高（峰径 26dp < 48dp 命中区、光晕贴钮、减动效静止）；其余（设置/表单/记录）保持平静 M3 | 符合 expressive restraint（「几个强表达时刻 > 均匀强度」；无全面 loud） | 保留 | — |
| E2 | 个性化无字圆钮图形语言（半黑半白/灰阶圆/色点环）为产品定制视觉（docs §4.1b v0.5.8b 拍板） | 无字语言本身 OK（语义经 contentDescription/sceneHint/selected 补足）；对比问题归 C1/C2 | 见 C1/C2 | — |

### 3.11 anti-patterns

| ID | 现状（证据） | 缺口 | 建议改法 | 优先级 |
|---|---|---|---|---|
| AP1 | 反模式逐条核查：无逐行卡片（列表已 ListItem 化）、无每控件胶囊、无 FAB 滥用（无 FAB）、无 chip 当按钮/导航（色系行曾用 FilterChip 方案 v0.5.8 已废）、无 tabs 乱用、无 switch+冗余保存（热点组为事务组+保存钮、符合）、破坏性与例程样式分离（error OutlinedButton + 确认）、无 emoji 状态卡（已 icon+label+token） | 符合（全部通过） | 保留 | — |
| AP2 | 隐藏入口五连击：v0.5.10c 连击目标=版本号行条目（MainScreen.kt:1501-1503,1613-1626：2s 窗口内 5 次点按解锁「收集日志」） | 隐藏手势可发现性差（刻意为之——开发者入口）；**无障碍缺失**：TalkBack 用户每次激活间隔难 <2s、5 连击几乎不可达，且无替代入口（对比：设置页无「开发者选项」通道） | 提供替代解锁（如版本行「长按 1s」或连续激活计数放宽/暂停窗口、或设置页隐藏开发者开关）；文档记录该入口刻意隐藏 | P2 |
| AP3 | 两段式收集行（同卡两击：开始→停止导出）文案双行说明清晰、Snackbar 起始提示——基础防呆 OK | 状态机跨页丢失见 F4；导出无 busy 见 F4 | 见 F4 | — |

### 3.12 accessibility

| ID | 现状（证据） | 缺口 | 建议改法 | 优先级 |
|---|---|---|---|---|
| A1 | contentDescription 覆盖：☰（:431）/×移除（:1002）/返回主页（:447）/色系入口（:477）/色点 hex 与色系名（:593-597）/从壁纸取色（:643）/场景钮（:707）/壁纸预览（:783）；装饰图不读屏：壁纸 Image contentDescription=null（WallpaperBackdrop.kt:100）、PicturePlaceholderIcon 纯装饰 | 符合（旧 A1 全量修复） | 保留 | — |
| A2 | 无字单选钮无 role（见 K1）——读屏无法播报「单选/选中」 | 见 K1 | 见 K1 | **P1** |
| A3 | 广播呼吸钮无 name/checked（K2）；SettingsPage「组网时自动用预设」Switch 无 label 绑定（SettingsPage.kt:322-330：Switch 与左侧文字列无关联，读屏孤立读「开关」） | 两处开关类控件读屏名称缺失 | 广播钮加 contentDescription「广播」；预设 Switch 所在 Row 加 `toggleable`/或 Switch 加 `semantics{contentDescription="组网时自动用预设"}` | **P1** |
| A4 | 对比度：正文角色全 token 化后基线 OK（旧 P0 已修）；本轮实测缺口=C1/C2（P0 图形 <3:1）、C4（P1 浮层无下限）、C5（P2 派生边缘档） | 见 C1/C2/C4/C5 | 见对应条目 | P0/P1/P2 |
| A5 | 触达 ≥48dp 复核：广播钮 48 命中区、色点/色系/取色钮 48、场景钮 56、ListItem 行、IconButton、按钮/Switch M3 默认 48、滑块默认高 | 符合 | 保留 | — |
| A6 | 可点行 role 缺失：AboutLinkRow（版本/GitHub/项目/反馈/王宝煲行，MainScreen.kt:1756-1791）、SettingsPage 权限/配对行（Row+按钮语义靠内部按钮）、收集日志卡（:1681-1697 clip+clickable） | 可点整行 TalkBack 语义不完整（点了但角色未知） | clickable 处补 `role=Role.Button`（与 K8 合并实施） | P2 |
| A7 | 文本缩放：S2（固定文本列）/S3（58dp 时间列）/S4（个性化一屏） | 见 S2/S3/S4 | 见对应条目 | P2 |
| A8 | liveRegion 宣告缺失（md3-audit F5/A8 遗留）：NetPairingDialog 阶段短语、BottomActionRow transferState 行、Mask/透明度滑块值变化、两段式收集状态 | 读屏用户不知阶段/值变化 | 关键状态行（阶段/传输/收集状态）加 `semantics { liveRegion = Polite }`；滑块值文本可省（Slider 自带 value 播报） | P2 |
| A9 | 减动效：ui.reduceMotion（Engine init 读 ANIMATOR_DURATION_SCALE==0）→ layoutSpec/crossfadeSpec 0ms、呼吸静止 | 符合 | 保留 | — |
| A10 | 五连击解锁 TalkBack 不可达（AP2） | 见 AP2 | 见 AP2 | P2 |

### 3.13 forms-and-input

| ID | 现状（证据） | 缺口 | 建议改法 | 优先级 |
|---|---|---|---|---|
| FI1 | PIN 输入：OutlinedTextField label 常驻「配对码（数字）」、数字过滤 take(8)、错误红字（MainScreen.kt:2558-2570）；热点预设：SSID/密码 label+supportingText，密码 8–63 位 inline 红字（SettingsPage.kt:270-311） | 部分：SSID 1–32 越界只在点「保存预设」时 Snackbar（:114-118），字段无 inline 错误/无红边 | SSID supportingText 同样做实时长度校验红字（与密码一致）；错误信息带恢复路径（已给范围） | P2 |
| FI2 | 个性化页=本地草稿（三槽/mask/透明度/accent）——任何改动即时页内预览，右上「保存」一次性写 prefs；离开页面未保存=丢弃且**无提示、无 BackHandler**（PersonalizePage.kt:136-260；docs §4.1b 明言「首版不做未保存提示」） | 与规范冲突：docs §4.1b 豁免 vs skill forms「保留已输入数据/防误丢」——调色半天后点「返回」即全丢，无挽回 | 折中：离开时（返回/切页）若草稿≠prefs 初值 → Snackbar「有未保存改动」+ 或 BackHandler 一次确认；成本低收益高 | **P1** |
| FI3 | 遮罩/透明度滑杆：值域/步进离散正确、label+% 双读（PersonalizePage.kt:869-938）；拖动即时预览（遮罩）或本地草稿（透明度，保存提交） | 符合（滑块=直接操纵 bounded 值；草稿+保存=事务组正确） | 保留 | — |
| FI4 | PIN 模式三态/深浅三态 SegmentedButton：单选互斥、选中态即时（PinStore 直写/themeMode 持久化） | 符合（立即生效设置、无冗余保存） | 保留 | — |

---

## 4. 重点核查项（逐条结论）

1. **语义 token 覆盖 / 裸 hex**：UI 代码层（MainScreen.kt / SettingsPage.kt / WallpaperBackdrop.kt）**零散落裸 hex**（grep 证据见附录 7.4）；裸色仅存在于 `ui/theme/BluelinkColor.kt`（token 实现，合规）与 `PersonalizePage.kt` **色板数据/装饰常量**（13 色系 HSV 数据、彩虹 sweep 渐变、SCENE 灰阶 ×2）——**豁免声明**：色板数据与装饰渐变按 skill「literal 属于 token 实现文件/数据层」豁免语义 token 约束（代码注释已声明分层——色板注释 PersonalizePage.kt:1101-1104「用户可选数据值，非语义 token……裸 hex 限制不约束本文件色板数据」、SCENE 灰阶注释 :744「数据色，非语义 token」）；但 SCENE 灰阶同时是「识别控件」，其**对比**缺口不豁免（C1）。
2. **深浅色正确性（新增 40dp 圆点/半黑半白钮/彩虹钮/容器透明度叠加）**：色板数据色深浅两主题均 OK（实测表 7.2）；**浅色主题白系色点 1.06:1、浅灰场景钮 1.19:1、深色主题深灰钮 1.63:1 = 缺口 C1/C2（P0）**；彩虹钮两主题可辨（彩色高饱和，复核 P2）；容器透明度叠加深色壁纸无对比下限 = C4（P1）。
3. **组件按行为选择**：新增滑块=真 M3 Slider ✓；设置行/配对行/权限行=分组容器内 Row（ListItem 语义近似，未逐行卡片）✓；弹层=AlertDialog/ModalBottomSheet ✓（NetPairingDialog 模态策略正确）；无字圆钮=自制单选控件，**触达 48–56dp ✓ 但 role/selected 组语义缺失（K1/A2，P1）**。
4. **间距/圆角/动效 token**：间距走 SpacingTokens（离群 2/20/1dp 均注明例外，S1）、圆角 ShapeTokens 两档、动效全走 MotionTokens + reduceMotion 分支（gated）✓；**仅选中环尺寸/厚度系列未 token 化（SH2，P2）**。
5. **无障碍**：icon-only 全具名 ✓；无字钮读屏文案全（色系名/hex/场景名/取色）✓；对比缺口 C1/C2（P0）+C4（P1）+C5（P2）；触达 ✓；文本缩放 S2/S3/S4（P2）；liveRegion 缺失 A8（P2）；开关名称 A3（P1）；减动效 ✓。
6. **弹窗/覆盖层/浮层**：Sheet/Dialog/收集两段式总体符合 feedback-and-overlays（不阻断过重、终态可关、错误可恢复、空态可操作）；缺口=F1 sheet 叠 dialog（P2，遗留）、F4 两段式跨页丢窗口（P1，v0.5.10 新增）、F2 Toast 例外（P2）。
7. **v0.5.7–0.5.11 新增交互 anti-patterns**：色系右区同款切换=简单状态直切无过渡（非动画反例，合规）；5 连击隐藏入口=可发现性/无障碍缺失（AP2，P2，刻意隐藏入口）；两段式收集=状态机耦合页面组合（F4，P1）；无「switch+保存」冗余、无「逐行卡片」回潮、无 FAB/chip 滥用——**新增交互总体无大反模式**。

---

## 5. 符合项清单（不要动，或仅 token 化/文档化）

- 语义 token 体系（light/dark 全量 + success/warning 扩展 + extended 读取）与运行态强调色派生
- 主页两态布局/横幅/空态四态/动作行主次/时间流近顶自动滚/45% 面板（实机定稿）
- ListItem 列表化、徽标三档 token 对、StatusDot≥8dp 双通道、SegmentedButton 单选×2、破坏性确认弹窗
- 广播呼吸钮（克制 + 减动效静止 + role=Switch + 48dp 命中区）
- Snackbar 统一反馈通道、NetPairingDialog 极简模态策略、壁纸解码失败可见文案、装饰图不读屏
- MotionTokens + reduceMotion 全链路、无 elevation/无阴影、圆角两档
- edge-to-edge + themeMode 系统栏重设 + insets 归 Scaffold
- docs §4.1b 个性化页结构（色系左 1/6 右 5/6、场景三槽、预览复用同渲染、遮罩行、保存事务）——除已标注冲突外保持

---

## 6. 方案汇总与实施顺序

### P0（先做；对比/可读性底线，改动小、集中新控件）
| # | 内容 | 文件 |
|---|---|---|
| P0-1 | C1：场景三钮灰阶/描边随主题化——浅色主题「浅色」钮不再用 #E8E8E8 直贴浅底（改中灰或 outline 2dp 环兜底），深色主题「深色」钮提亮/加 outline 环；统一走 token（建议 sceneDark/sceneLight 双主题常量或 onSurfaceVariant 派生 + outline 环） | PersonalizePage.kt |
| P0-2 | C2：白/浅灰系色点（含 FamilyDotsRow）未选环 outlineVariant→outline 2dp（或 1dp onSurfaceVariant 内描边），浅色主题可达 ≥3:1 | PersonalizePage.kt + SH2 SwatchTokens |

### P1（体验/语义/丢稿）
1. C4 容器透明度可读性下限：滑杆风险提示 / 联动警示 / 默认 0.80 保留并文档化风险区（WallpaperStore/MainScreen/PersonalizePage）
2. K1/A2 无字单选钮 role=RadioButton + 行容器 selectableGroup（色点/色系/场景）
3. A3 开关名称：广播钮 contentDescription+checked；预设 Switch 语义 label
4. FI2 个性化未保存草稿离开提示（BackHandler/切页 Snackbar）
5. F4 两段式收集状态提升（collecting/collectStart → MainScreen/ui 层）+ 导出 busy 禁点

### P2（打磨清单，按节编号引用）
S1/S2/S3/S4、T2、SH2、M2 复核/M3 页切换过渡、N1 抽屉图标、N2 返回语义图标、AD1 时间流上限、AD4 宽窗宽度上限、F1 弹窗提升、F2 Toast 收口、AP2/A10 解锁替代、A6 行 role、A8 liveRegion、C5 派生钳制、C6 横幅容器、C3 彩虹钮复核、K2 呼吸钮语义、K3「关闭」按钮降级、K4 字形→Icons、FI1 SSID inline 校验——按实现排期穿插，无硬性顺序。

**验收（P0）**：浅色主题下白系色点/场景钮与背景 ≥3:1（附录 7.2 复测）；TalkBack 走查无字钮播报「单选按钮…已选中」；灰度截图深浅双主题对比复核通过。

---

## 7. 附录

### 7.1 声明
- 本审计为**只读 + 写作**：仅读取 `/srv/android/material-design-3-ui-skill-main/` 与工程内 UI 源码/docs；**唯一写入物** = 本文件（`bluelink/docs/md3-audit-2.md`）。
- **未修改任何应用代码、未构建、未下载依赖、未联网、未运行应用**；对比度数值为本机 python 计算（WCAG 相对亮度，见 7.2）。
- 文中建议值均以「默认 token/参数替换」给出，供实现阶段直接执行；最终发行前以真机双主题截图复核。

### 7.2 对比度计算记录（WCAG 相对亮度，正文 ≥4.5:1 / 图形 ≥3:1）
| 前景 | 背景 | 比值 | 判定 |
|---|---|---|---|
| 场景浅灰钮 #E8E8E8 | 浅色页底 #FDFBFF | 1.19:1 | ✗（<3:1，C1） |
| 场景深灰钮 #3A3A3A | 深色页底 #111318 | 1.63:1 | ✗（<3:1，C1） |
| 白系色点 ~#F4F5F6 | 浅色页底 #FDFBFF | 1.06:1 | ✗（<3:1，C2） |
| 浅 outlineVariant 环 #C4C6D0 | 浅色页底 | 1.66:1 | ✗（<3:1，C2 环） |
| 深 outlineVariant 环 #44474F | 深色页底 | 2.00:1 | ✗（环不足，C1/C2 兜底方案） |
| 浅 outline #74777F | 浅色页底 | 3.39:1 | ✓（3:1 图形，环修复目标） |
| 深 outline #8F9099 | 深色页底 | 4.29:1 | ✓ |
| 场景深灰钮 #3A3A3A | 浅色页底 #FDFBFF | 11.06:1 | ✓ |
| 场景浅灰钮 #E8E8E8 | 深色页底 #111318 | 15.16:1 | ✓ |
| primary 浅 #0B57D0（选中环） | 浅底 | 6.21:1 | ✓ |
| primary 深 #AAC7FF（选中环） | 深底 | 10.90:1 | ✓ |
| 暗主题派生边缘档：onPrimaryContainer lum0.6 vs primaryContainer lum0.142 | — | 3.39:1 | ✗ 边缘（C5） |
| 极端浮层组合：onSurface #1A1C20 vs surfaceContainerLow alpha0.5 混深壁纸 | — | ~2:1（估算） | ✗ 无下限（C4） |

### 7.3 引用文件清单
- Skill：`SKILL.md` + `references/` 13 篇（全部通读）
- Bluelink（证据）：`ui/MainScreen.kt`、`ui/SettingsPage.kt`、`ui/personalize/PersonalizePage.kt`、`ui/personalize/WallpaperBackdrop.kt`、`ui/personalize/WallpaperStore.kt`、`ui/theme/BluelinkColor.kt`、`ui/theme/Dimens.kt`、`ui/theme/BluelinkTheme.kt`、`ui/BluelinkUiState.kt`、`ui/BluelinkRoot.kt`、`MainActivity.kt`、`docs/ui-design.md`（§4.1/4.1b/4.2/4.8/4.9/5/6）、`docs/md3-audit.md`（前序结构参考）

### 7.4 grep 证据摘要（裸 hex / 字面量扫描结果）
- `Color(0x…)` 于 MainScreen/SettingsPage/WallpaperBackdrop/theme 消费点：**0 处**（仅 BluelinkColor.kt token 定义与 PersonalizePage 数据/装饰常量 745/747/650-657）
- 动画 spec 字面量（tween/keyframes/infiniteRepeatable）：仅 MotionTokens 内定义 + MainScreen 消费点（530-665 呼吸、703/721 布局/面板）——全部经 token
- `.dp` 字面量残留（非 theme 文件）：48/44/42/40/56/54/52/2/1/96/80/128/18/20/3/5dp——集中在徽章内部留白、抽屉头、PersonalizePage 新控件（见 S1/S2/SH2）
