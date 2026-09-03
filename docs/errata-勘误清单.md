# Bluelink 文档勘误清单（errata）

> 审查性质：**只读审查**，未修改任何文件、未构建、未联网。
> 审查基线：代码 HEAD = `13dca48`（feat(log): v0.5.14d，2026-09-03 15:32 +0800，working tree clean）；`app/build.gradle.kts`：minSdk 26 / targetSdk 34 / compileSdk 37 / versionName "0.5.10" / versionCode 2；releases/ 已有 Bluelink-v0.5.14d-debug.apk。
> 对照范围：README.md、docs/TODO.md、docs/ui-design.md、docs/networking.md、docs/troubleshooting.md、docs/macrodroid-notes.md、docs/md3-audit-2.md ↔ `app/src/main/java/com/zglinus/bluelink/`（MainScreen/SettingsPage/PersonalizePage/BluelinkEngine/HotspotManager/HotspotPresetStore/NetworkingStateMachine/PinStore/DiagLogger/MainActivity 等）。

## 分类汇总

| 类别 | 条数 |
|---|---|
| 版本/状态（快照版本、未完成却已实现、已废弃仍现行、日期、数据不符） | 18 |
| 流程/功能（文档描述与代码行为不符） | 7 |
| 易误解（用户侧序号/文案歧义，代码内可见文案） | 4 |
| 笔误（失效链接/重复条目/排版编号） | 3 |
| 自相矛盾（同文档前后冲突或与自身定稿冲突） | 3 |
| **合计** | **35** |

---

## 一、版本/状态类（18 条）

### README.md

1. **README.md:4** → 现描述：`状态快照：v0.5.14d（2026-09-02）`。v0.5.14d 提交（13dca48）与出包均为 **2026-09-03**（15:32 +0800），且本行本身就是在该提交里改的。应修正为：`状态快照：v0.5.14d（2026-09-03）`。类别：版本/日期。

2. **README.md:80**（「未完成（TODO 摘要）」）→ 现描述：`**UI1b-C**：设置-设备页（PIN 管理/重置指纹、热点密码预设、下载目录、权限检测）+ 全局深浅切换` 列为未完成。实际 **v0.5.9 已实现**：设置五区真页 `ui/SettingsPage.kt`（安全=PIN 三态+配对列表+重置本端指纹+清空配对、热点=预设+「② 私有 API 热点」开关、传输=接收目录更改/恢复默认、外观=深浅三态、权限检测=五项清单）＋ MainActivity 深浅三态持久化（themeMode）。建议：从未完成清单删除该条，改为「✅ 已交付（v0.5.9 UI1b-C）」或直接移除；保留「工程收尾/release 签名」与「迭代候选」两条。类别：版本/状态错误。

3. **README.md:74**（技术要点）→ 现描述：`docs/md3-audit.md — Material 3 审计与落地`。仓库 docs/ 已无 `md3-audit.md`（被 `md3-audit-2.md` 取代，git 全历史亦无该文件）。应修正为：`docs/md3-audit-2.md — Material 3 全 App 只读审计与调整方案（md3-audit v0.5.1a 的承接与复检）`。类别：笔误/失效引用（记入版本类亦可，因涉及审计版本演进）。

### docs/TODO.md（头部已更新到「v0.5.14d 时点」，正文仍停留在 v0.5.8/v0.2–0.4 状态，属全文档过时）

4. **docs/TODO.md:3** → 现描述：`2026-09-02 快照（v0.5.14d 时点：组网+LocalSend 闭环 ✅ / …）`。①日期应为 2026-09-03；②快照内容未含 v0.5.9–v0.5.14d 演进（设置五区/深浅三态/关于页 v0.5.10 收集日志/容器透明度 v0.5.11/系统返回键 v0.5.13/传输记录页与扫描 0dB 移除 v0.5.14d），读者会误以为这些未做。应修正为补一句：`…v0.5.9–v0.5.14d 已交付：设置五区真页+深浅三态 / 关于页 v0.5.10 重做（隐藏收集日志两段式）/ 个性化容器透明度 v0.5.11 / 系统返回键+留白提醒 v0.5.13 / 文件传输记录页 v0.5.14d`。类别：版本。

5. **docs/TODO.md:15（A6）** → 现描述：`Wi-Fi 变化监听：手动连上热点（SSID 匹配 offer）→ 自动回 joined | 📝 已确认，后续做`。实际已实现：BluelinkEngine（监听目标 SSID=最近 offer、会话内注册/收尾注销，A6 注释）＋ README 工作流已有「手动连热点｜Wi-Fi 监听 SSID 匹配→自动回 joined」分支。应修正为：`✅ 已实现（收 offer 后注册 Wi-Fi 监听，SSID 匹配自动回 joined）`。类别：版本/状态。

6. **docs/TODO.md:16（A7）** → 现描述：`「root 失败不静默落④」（①②失败给原因提示而非直接手动）| 📝 讨论中`。实际：① root 路径已整体废弃（B1 移除），② 各降级点均有显式原因透传（如 HotspotManager 失败文案「root 热点路径已停用(B1 移除)，降级 ②」「② 已关闭(设置开关)，降级 ③」「sdk34+ 不裸调 startTethering（逆向结论），降级 ③」交状态机/UI），不再「静默落④」。应修正为：`✅ 完成（降级原因显式透传；root 已废弃故原前提消失）`。类别：版本/状态。

7. **docs/TODO.md:17（A8）** → 现描述：`同网判定优先（无感）…| 📝 无感核心方案（待办）`。实际 **v0.4.7 已实现**：握手完成且 startNetworking 触发时 `sameLanForPeer()`（双方 wifi、SSID 相等、子网一致）→ 同网直接 TRANSPORT 免热点（BluelinkEngine A8 注释）。应修正为：`✅ 已实现（v0.4.7 同网免热点直连；异网才进仲裁+热点）`。类别：版本/状态。

8. **docs/TODO.md:23-26（B 包 B1–B4）** → 现描述：B1 root 真热点（验证机小米 12S）待做、B4 root 静默接入待做，整节无状态。实际：B1 **已废弃**（root 路线废弃，HotspotManager `L1_ROOT` 返回失败 stub，docs/networking.md §11 同记「已删 B1」）；B2/B3 已实现（② k1/c 按名枚举 Binder 直呼 + 反射兜底；③ LocalOnly 先试读）。应修正为：整节改为历史结项表（B1 废弃/原因，B2/B3 ✅ 及版本，B4 由 v0.4.6 温和收尾+Specifier 自动接入取代）或整段删除并指向 networking §11。类别：版本/状态。

9. **docs/TODO.md:32-34（C 包 C1–C3）** → 现描述：列 C1 HTTP Server 53317…、C2 同网 TCP 探测接线、C3 发送/接收/进度，**无状态列**。实际均已实现（transport/LocalSendClient+LocalSendServer、net/SameLanChecker+probeTcp、SAF 收发与进度；v0.4.1 A5 双机 TRANSPORT 实测）。应修正为：补状态列 ✅（并注明 v0.4.x 起 LocalSend v2 直连闭环）。类别：版本/状态。

10. **docs/TODO.md:38-42（D 节）** → 现描述：`**v0.5.13 待办**：响应系统返回键…；个性化「容器透明度」可读性提醒预留固定留白位…` ＋ 行 42 清单 `抽屉 / 设置 / 权限总览 / 文件浏览器…/ 传输记录 / 外观皮肤 / 前台服务通知状态行 / 接收目录自定义`。实际：v0.5.13 两项**均已实现**（31df823：BackHandler 子页返回+dirty 提示、透明度提醒固定留白位）；清单中 抽屉/设置/传输记录(v0.5.14d)/接收目录自定义 已实现；文件浏览器未实现（SAF 选择器代替）、权限总览为独立页未实现（并入设置页第五区权限检测）、外观皮肤已被个性化页取代（ui-design §4.10 已废弃）、前台服务通知状态行未实现（代码无任何 Notification 实现）。应修正为：v0.5.13 待办两行标 ✅；清单拆「已交付/未交付（SAF 替代说明）/已废弃（外观皮肤）」并保留「文件浏览器、前台服务通知、独立权限总览页」为真正未完成项。类别：版本/状态。

11. **docs/TODO.md:46-47（E 节 E1/E2）** → 现描述：root 命令矩阵落地、10/11 段真机验证 为待办。实际 root 增强已整体废弃（同上 B1）；E1 中「8.0 LocalSocket 已实测」属历史结论（ui-design §7.1 已验证页）。应修正为：标注「root 路线已废弃（A15/KernelSU），本节省略或转历史」，仅保留非 root 的 10/11 段 Specifier/盲区行为验证项并单独列状态。类别：版本/状态。

12. **docs/TODO.md:51-52（F1/F2）** → 现描述：`F1 git 远端 + 提交者身份正式化 + 推远端`。实际远端已配置并推送（git remote origin/main、README 仓库 oh-zgl-s-fish/Bluelink），剩余仅「提交者身份正式化」（提交仍署 zglinus-for-agent）。应修正为：`F1 ✅ 远端已推（oh-zgl-s-fish/Bluelink）；待办收敛为「提交者身份正式化」＋ F2 release 签名`。类别：版本/状态。

13. **docs/TODO.md:56（近期顺序）** → 现描述：`A5 联测（进行中）→ A6…→ B1（12S root 自动热点）→ B2/B3 → C…→ 异网全链路 → D/E/F 收尾`。A5 早已联测通过（同表 A5 ✅ v0.4.1）、A6/A8/B2/B3/C/D 大部已交付、B1 root 已废弃——整行过时。应修正为：按当前真实剩余项重写（如：10/11 段真机验证 → release 签名 → 文件浏览器/前台通知 → 迭代候选）。类别：版本/状态。

### docs/ui-design.md

14. **docs/ui-design.md:5** → 现描述：`状态：全部决策已拍板，未进入实现`。实际自 v0.5.6–v0.5.14d 已实现绝大部分（主页两态/个性化页/设置五区/关于页收集日志/传输记录页/系统返回键等），未实现仅剩：独立权限总览页（并入设置）、文件浏览器（SAF 替代）、前台服务通知、Root 命令审计日志，且实现形态与 §4.1/§4.2/§4.3 早期草案有差异（抽屉 4 栏替代 7 项、泳道页→配网极简进度弹窗、UI1b-B2 个性化重做）。应修正为：状态改为「v0.5.6–v0.5.14d 分期实现中，实现差异见文中版本注记/md3-audit-2」或加「实现现状对照」表头。类别：版本。

15. **docs/ui-design.md:281-311（§4.8 设置页规格）** → 现描述：热点区仅 SSID/密码/自动用预设/保存，外观区仅深浅三态。实际 v0.5.14c 起热点区新增**「② 私有 API 热点」运行时开关**（HotspotPresetStore.privateApiEnabled 直驱、默认开，SettingsPage.kt:358-387），外观区新增「强调色 / 壁纸 → 前往个性化」行。规格图与关键决策均未反映。应修正为：在 §4.8 补入「② 私有 API 热点」开关规格（含默认开、关闭=②直接降级③的语义）与外观区入口行，或加 v0.5.14c 修订注。类别：版本（规格缺漏）。

16. **docs/ui-design.md:333** → 现描述：`版本号：显示 BuildConfig.VERSION_NAME（v0.5.10 起 versionName 与发布版本对齐）`。实际仓库 `app/build.gradle.kts` 的 versionName 自 v0.5.10（0000a9f）后**未再提交更新**，HEAD 仍为 `0.5.10`，而发布/README 已到 v0.5.14d——按当前源码构建，关于页将显示 0.5.10，「对齐」约定已失效。应修正为：同步升级 `build.gradle.kts` versionName=0.5.14d（并每版出包前更新），或文档注明「versionName 由发布流程在构建时对齐，仓库默认值滞后」；勘误侧建议文案：`版本号显示 BuildConfig.VERSION_NAME——请确保每次出包同步提交 versionName（当前仓库滞后 4 个版本）`。类别：版本/数据不符。

### docs/networking.md

17. **docs/networking.md:99** → 现描述：`②' 系统预配热点自动开（Binder 直呼 ITetheringConnector.startTethering，sdk 26-33，…密码登记一次）——最高级（v0.3.4 落实中）`。实际该路径 v0.3.6–v0.3.8 已落实并演进（NEARBY/WRITE_SETTINGS 前置、回调码确认+轮询兜底、v0.3.8 改 k1/c 式按名枚举覆盖 sdk31 签名差异），v0.5.14 起默认启用、v0.5.14c 有设置页开关。应修正为：删除「（v0.3.4 落实中）」，标注 `✅ 已实现（v0.3.4 起，v0.3.8 k1/c 按名枚举；v0.5.14 默认启用）`。类别：版本/状态。

18. **docs/networking.md:102/105** → 现描述：`② 私有 API：仅保留「开启/关闭」能力，可用版本（8-9，targetSdk27 legacy 豁免）直接反射；其余版本 try 失败即降级（Android 12 blacklist 无条件拦截已实测）` 与 `targetSdk=27 保留（② 8-9 路径需要 legacy 豁免；对 ③ ④ 无副作用）`。实际：① `app/build.gradle.kts` targetSdk = **34**（v0.3.4 起恢复，README 同）；② ②路径已重构为 **k1/c 按名枚举 Binder 直呼为第一手段**（sdk 26–33，transact 不受 hidden API blacklist 限制，反射 setWifiApEnabled 仅作兜底），A12 blacklist「无条件拦截」只约束反射路径、不再适用为总体结论。应修正为：`② 私有 API：k1/c 按名枚举 ConnectivityManager.startTethering（sdk 26–33 第一手段）+ 反射 setWifiApEnabled 兜底；targetSdk=34（非 27）；A12 blacklist 仅影响反射兜底路径`。类别：版本/数据不符。

### docs/troubleshooting.md

19. **docs/troubleshooting.md:63（表 4.5 状态列）** → 现描述：`⏳ v0.2.6 修复中`。实际该修复已完成：Manifest 已含 CHANGE_NETWORK_STATE（含 v0.2.6 理由注释），31–32 查它 + requestNetwork SecurityException 兜底引导 WRITE_SETTINGS 均在代码中，v0.2.6–v0.2.9 及后续全部版本均已出包。应修正为：`✅ v0.2.6（Manifest CHANGE_NETWORK_STATE + 31–32 判定 + 兜底引导）`。类别：版本/状态。

### docs/md3-audit-2.md

20. **docs/md3-audit-2.md:3/§3/§6（全文定位为 v0.5.11 审计）** → 现描述：P0（C1/C2 对比缺口）、P1（K1/A2 单选 role、A3 开关名称、FI2 未保存草稿提示、F4 收集状态提升等）、P2 清单均以「当前缺口/待实施方案」表述，§6 为「供后续实现阶段直接执行」的计划。实际这些已按计划实施：v0.5.12（fc52d77）落地 P0+P1（对比度底线→outline 2dp 环/灰阶随主题、Role.RadioButton+selectableGroup、广播钮/预设 Switch contentDescription、dirty Snackbar、收集状态上提 MainScreen）、v0.5.13 落地 FI2 系统返回+AP2 长按替代解锁+A8 liveRegion、v0.5.14 P2 全量（9824dce）。应修正为：文首加「实施状态」注（P0/P1 已 v0.5.12 落地、P2 已 v0.5.14 落地，本文件为 v0.5.11 快照审计；遗留项以代码为准复检），避免按当前缺口误读。类别：版本（快照未随实施更新）。

---

## 二、流程/功能类（7 条，描述与代码行为不符）

21. **README.md:17（特性）** → 现描述：`内置诊断日志（App 内查看/复制/导出）`。实际「查看/复制/导出」入口（DiagnosticLogDialog/dump 弹窗）已于 v0.5.10 随旧开发者区**整体删除**；现仅剩关于页版本行 5 连击/长按解锁的「收集日志」两段式**脱敏导出 txt**（DiagLogger 仅内存环形缓冲，无查看/复制 UI）。应修正为：`🔒 密码/PIN 全程不回显；内置诊断日志（关于页隐藏入口两段式脱敏导出）`。类别：流程（功能描述过时）。

22. **README.md:31/48（热点链路）** → 现描述：`③ LocalOnly：全版本密码自动读`、矩阵 `②'/③ 自动开+读密码`。实际（HotspotManager v0.3.9.2 定案）：③ LocalOnly onStarted **统一先试读 preSharedKey**——读空时 sdk 33+ 走用户回填登记、sdk 26–32 按盲区失败降④；并非「全版本自动读到」；且②'（系统预配热点）SSID/密码为系统配置 **App 不可读**，需用户登记一次或 v0.5.14c 预设模式 offer 直带预设值，「读密码」不成立。应修正为：`②' 系统预配热点自动开（密码不可读：登记一次或预设直带）→ ③ LocalOnly 自动开+onStarted 先试读密码（读空 33+ 回填 / 26–32 降④）`；矩阵行改 `②' 自动开（登记/预设带密码）· ③ 自动开+先试读`。类别：流程（与代码不符）。

23. **docs/ui-design.md:289/308** → 现描述：设置页安全区 `已配对设备 列表（别名 + MAC，可单项移除）`、关键决策 `…列表显示 别名+MAC`。实际实现（SettingsPage.kt + PinStore）：配对表条目 = `对端指纹[|别名]`，列表显示**别名 + 「指纹 …<尾 6 位>」**（无 MAC 展示；指纹=对端本端指纹 fp，旧对端缺失时回落其 BLE 设备地址/MAC 存入同一 fingerprint 字段，仍以「指纹」名义显示）。应修正为：`已配对设备 列表（别名 + 指纹尾 6 位，可单项移除）`，并把 L308 的「列表显示 别名+MAC」改为「列表显示 别名+指纹（尾 6 位）」。类别：流程（与代码不符）。

24. **docs/ui-design.md:298/311（§4.8 接收目录）** → 现描述：`恢复默认=清除自定义 → Download/Bluelink`（并隐含默认自动落 Download/Bluelink）。实际（BluelinkEngine v0.4.5 起）：未自定义目录时收到的文件先入**暂存 filesDir/localsend/**，随后弹系统目录选择器（OpenDocumentTree，Downloads 为初始目录）由用户当场选位；不存在 App 自动创建的「Download/Bluelink」目录，「恢复默认」仅清除自定义 uri 并复位到「提示选择」逻辑。应修正为：`更改=SAF 目录选择+持久授权；恢复默认=清除自定义接收目录（此后收到文件提示选择保存位置，初始 Downloads）`。类别：流程（与代码不符）。

25. **docs/ui-design.md:342-343（「收集日志」两段式落盘）** → 现描述：`再次点击 = 停止并保存：…保存到接收目录（默认 Download/Bluelink，自定义则用自定义目录）`。实际（MainScreen v0.5.12 F4 上提后）：已自定义接收目录→直接 SAF 落盘该目录；**未自定义→本次弹目录选择器选落盘位置（Downloads 初始，且不改接收目录设置）**；无「Download/Bluelink」默认路径。应修正为：`…保存为 txt：已设自定义接收目录则直接落该目录；否则本次弹目录选择器选位置（初始 Downloads，不改接收目录设置）`。类别：流程（与代码不符）。

26. **docs/networking.md:51（§5 状态机行）** → 现描述：`每步超时(默认15s)或失败 → abort`。实际四处关键等待已对齐 **120s**（④手动配网回填 / 对端等 offer / 热点方等 joined / 从机等 ack，NetworkingStateMachine 常量注释），仅其余步骤 15s。应修正为：`每步超时：四处关键等待 120s（手动配置/等 offer/等 joined/等 ack，共用 MANUAL_TIMEOUT_MS），其余 15s；超时或失败 → abort → 降级/切换角色/人工引导`。类别：流程（与代码不符）。

27. **docs/troubleshooting.md:69-71（「附：诊断手段」整段）** → 现描述：`App 内置诊断（推荐）：主页面状态卡 →「诊断」→ 查看/复制全部/导出文件（Android/data/com.zglinus.bluelink/files/diag_*.txt）——不依赖 adb`。实际：①「诊断」入口与查看/复制/导出按钮已于 v0.5.10 删除；② DiagLogger 只做内存环形缓冲（512 条）+ logcat，**从不写文件**（不存在 diag_*.txt）。应修正为：`App 内置诊断（v0.5.10 起入口变更）：关于页「版本」行连点 5 次（或长按）解锁「收集日志」→ 点击开始记录 → 再次点击停止并脱敏导出 txt（SAF 落盘）；或 adb logcat`。类别：流程（入口已删除/路径不存在）。

---

## 三、易误解（用户侧序号/文案歧义，4 条）

> 注：以下为**代码内用户可见文案**（SettingsPage/MainScreen），经审查建议修正；对应文档若照抄同款文案（如 ui-design §4.8 关键决策文字）也一并改。

28. **SettingsPage.kt:369-373/386（热点区「② 私有 API 热点」开关）** → 现描述：开关标题 `② 私有 API 热点`、副文 `关闭后组网直接用 LocalOnly（③）`、读屏 contentDescription 同为 `② 私有 API 热点`。问题：②/③ 是内部降级链序号（①root→②私有API→③LocalOnly→④手动），且 ① root 已废弃——用户看到「②/③」无上下文、易误解为步数或版本号；「私有 API」对终端用户是黑话。建议文案：标题 `自动开热点（系统级）`（或保留技术名但去序号：`私有 API 热点`），副文 `开启：组网时自动尝试开启系统热点；关闭：跳过该通道，直接使用本地热点（LocalOnly）`；contentDescription 同步去序号。类别：易误解。

29. **SettingsPage.kt:275（热点区 SSID 说明）** → 现描述：`预设 SSID/密码：热点方自设 SSID 路径消费；LocalOnly 本地热点（③）由系统生成，不受预设影响`。问题：①「③」序号无上下文；②「热点方自设 SSID 路径消费」未反映 v0.5.14c 语义（预设=用户按预设名/密码预先配置系统热点，组网 offer 直带预设值，跳过登记）；③「LocalOnly」对用户是黑话。建议文案：`预设 SSID/密码：与你在系统里开启的热点名称/密码一致时，组网自动携带、对端免输入直接接入；本地热点（LocalOnly）由系统生成，不受预设影响`。类别：易误解。

30. **MainScreen.kt:2594/2637/2683（配网登记弹窗标题）** → 现描述：标题分别为 `系统热点登记（②）`、`手动配网（④）`、`本地热点密码登记（③）`。问题：②③④ 序号对用户无意义（用户从未见过 ①），且与设置页「② 私有 API 热点」呼应会造成「为什么有 ② 没有 ①」困惑。建议文案：去序号为 `系统热点登记`、`手动配网`、`本地热点密码登记`；如必须保留技术语义，把序号说明放弹窗说明文字（`第 2 级自动方案`等）而非标题。类别：易误解。

31. **SettingsPage.kt:720（权限检测「通知」项 note）** → 现描述：`13+ 运行时权限（传输状态/自测通知）`。问题：「自测通知」对应的自测功能 v0.5.10 已废弃删除；且全代码库无任何 Notification/前台服务实现（仅 Manifest 声明），「传输状态通知」亦未落地——note 描述的是不存在的通知能力。建议文案：`13+ 运行时权限（通知栏状态展示，功能开发中）` 或直接 `13+ 运行时权限（当前版本未使用，预留）`。类别：易误解（含过时引用）。另：同区第 4 项「修改系统设置」note 中「热点 Binder 直呼 / 8–10 接入路径需启用」可保留（有真实消费）。

---

## 四、笔误/排版类（3 条）

32. **docs/ui-design.md:338-339（§4.11 关于页条目 7/8）** → 现描述：第 7 条「致谢区（底部…）」与第 8 条「底部：致谢区」为**重复条目**。应修正为：第 7 条含「致谢+Contributors 合并区（文案对齐 README）」、第 8 条删除（或改为「版权/许可注记行」——实际页面底部还有 GPL-3.0 注记）。类别：笔误。

33. **docs/troubleshooting.md:74-77（排版错乱）** → 现描述：`注意：扫描日志 1s 去抖后约每秒 1 条，512 条缓冲可覆盖约 8 分钟完整链路### 4.7 对端「加入热点无反应」…`——「附：诊断手段」段落后直接粘连 `### 4.7`/`### 4.8` 两个小节，且编号从 4.5 直接跳到 4.7（**无 4.6**），小节位置也应在 §4 表格之后而非「附」段之后。应修正为：补 4.6（或顺延编号），4.7/4.8 移至 §4 表格后，「附：诊断手段」放最后，并在 4.7 标题前补换行。类别：笔误/排版。

34. **docs/macrodroid-notes.md:5/37-41 vs 54-55（结论自相矛盾）** → 现描述：头部与「对 Bluelink 的结论」写 `结论：不引入`、`维持 LocalOnlyHotspot + 手动兜底主线（§11）`；而同日「追加（第三次逆向确证）」却写 Binder 直呼为「可行新路径」并建议 `10-13 段增强 = ② 加 Binder 直呼分支`——且代码已实际采纳（v0.3.4/v0.3.6/v0.3.8 ② Binder 直呼=k1/c 第一手段，v0.5.14 默认启用）。应修正为：头部结论改为 `结论：Binder 直呼 startTethering（k1/c 手法）可行且已采纳为 ② 路径（v0.3.4+）；34+ 维持 LocalOnly；本文件为逆向档案`，并删/改第 40 行「不引入」表述。类别：自相矛盾（归档文档结论未随追加与代码更新）。

35. **docs/networking.md:19/28/51/53/60-67 vs 103（root 现役 vs 废弃）** → 现描述：§1-10 多处把 ① root 真热点列为现行主路径（角色协商 L1「root 可用与私有 API 同级」、四级策略表 ① root、状态机 `HOTSPOT_STARTING(①root→②私有API…→④)`、接入矩阵 root 行等），而 §11:103 已记 `root 路线废弃（…已删 B1）`、代码 L1_ROOT 为停用 stub。同文档前后冲突。应修正为：文首加档位注 `§1–10 为 2026-09-01 前设计稿（含已废弃 root 路线）；自 §11 定稿起以 §11 及代码 v0.3.4+ 演进为准（root 已废弃、②=k1/c Binder 直呼+反射兜底、③=LocalOnly 先试读）`；或将 §1-10 的 ① root 行/状态机链同步改为「①（废弃）」并去除 root 接入矩阵。类别：自相矛盾/过时。

---

## 附：核对通过、无需改动的项（抽样）

- ui-design §4.1b 个性化页规格（v0.5.8/8b/11/12/13 修订：一屏布局/1:5 取色/保存语义/遮罩 0–80%/容器透明度 5–50% 默认 20/提醒固定留白）与 PersonalizePage 实现一致。
- ui-design §4.7 传输记录（v0.5.14d 落地：摘要+展开+持久化+失败自动展开红字）与 LogPage/TransferRecord.kt 一致（ADR 33）。
- ui-design §5 系统返回键规则（v0.5.13）与 MainScreen BackHandler/navigateFrom 一致。
- 关于页结构（ui-design §4.11 1–5 项：应用名/版本/三个外链）与 MainScreen AboutPage 一致；版本行 5 连击 + v0.5.13 长按替代解锁已在代码（本清单第 25 条之外的新增替代入口，文档 §4.11 未提长按——建议顺手补一句，低优先）。
- networking §2 battery 字段（HandshakeProtocol 已含 `battery`）、§11 ③ 先试读定案（v0.3.9.2）与代码一致。
- macrodroid-notes 逆向事实（ITetheringConnector/targetSdk35/Shizuku 仅 sdk36 机制等）与代码约束（sdk34+ 不裸调）一致。
