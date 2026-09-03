# Bluelink · 待办清单

> 2026-09-02 快照（v0.5.12 时点：组网+LocalSend 闭环 ✅ / UI 经 MD3 重构至扁平分层 / 广播钮呼吸定版 / 个性化页 UI1b-B 三槽壁纸 + UI1b-B2 重做（一屏无滚动布局+1:5 取色+右上保存+主页面浮层化+强调色主题）✅ / 品牌蓝鲸·X+图标 / GPL-3.0 开源 / GitHub 远端 oh-zgl-s-fish/Bluelink）。配套：README（路线）、docs/networking.md（组网设计）、docs/troubleshooting.md（排障）。
> 代码约定：编码/编译修复一律 pi 子 agent，主管负责构建/验收/文档/归档；**每次出包/修复同步更新文档**。

## A. 组网链路（修复批次）

| # | 项 | 状态 |
|---|---|---|
| A1 | GATT 长期收发自测（120s ping/pong） | ✅ **验证通过**（24 发 19 成功 RTT~80ms；通道可用但暴露并发互踩，已由 A3 修） |
| A2 | PEER 等 offer 超时对齐 120s | ✅ 已完成（与④手动配置同常量） |
| A3 | 信令发送串行队列（FIFO + inFlight） | ✅ 已完成（v0.2.4，消 ping/pong/offer 并发互踩） |
| A4 | 对端 offer 自动接管（收到 offer→join→回 joined） | ✅ 已完成（v0.2.4） |
| A5 | **双机联测全链路**：热点自动开 → offer → 对端接入 → joined → TRANSPORT | ✅ **通（v0.4.1）**：A12 ③ LocalOnly 密码自动读 → 荣耀 sdk35 Specifier 接入 → 复核放行 → TRANSPORT 双机实测 |
| A6 | Wi-Fi 变化监听：手动连上热点（SSID 匹配 offer）→ 自动回 joined | 📝 已确认，后续做 |
| A7 | 「root 失败不静默落④」（①②失败给原因提示而非直接手动） | 📝 讨论中，并入 B 包规格 |
| A8 | **同网判定优先**（无感）：握手后先探同网，同网直接直连、异网才进热点流程 | 📝 无感核心方案（待办） |

## B. 组网 B 包（热点真实现）

| # | 项 | 验证机 |
|---|---|---|
| B1 | ① **root 真热点**：root 反射 `setWifiApEnabled` / `cmd wifi`；自设 SSID/密码；取热点 IP | 小米 12S(root) 现成 |
| B2 | ② **私有 API**：反射 + WRITE_SETTINGS AppOps try 实测降级（sdk 26–33 可尝试已放宽） | 12S / 各机型 |
| B3 | ③ **LocalOnlyHotspot**：8-9 读密码自动；13+ 弹窗回填；10-12 禁用 | 荣耀8(8.0) |
| B4 | root 静默接入（cmd wifi connect-network / 8.0 LocalSocket）+ 传输完自动收尾 | — |

## C. LocalSend v2 传输（同网直连）

| 项 | 内容 |
|---|---|
| C1 | HTTP Server 53317 + prepare-upload/upload/cancel/info；先 HTTP 自互通，官方互通+HTTPS 二期 |
| C2 | 同网 TCP 探测接线（SameLanChecker + probeTcp 53317） |
| C3 | 发送入口（SAF 起步）+ 接收落盘 + 进度 |

## D. 完整 UI（按 ui-design.md 全量）

**v0.5.13 待办**：
- 响应系统返回键：子页（LOG/个性化/设置/关于）按系统返回 → 回主页（与页内返回/顶栏蓝鲸·X 同效）；个性化页有未保存改动时返回先提示（dirty 判定，同 v0.5.12 Snackbar 提示）；主页保持默认退出。接入 BackHandler（MainScreen 路由层）

抽屉 / 设置 / 权限总览 / 文件浏览器（最近/收藏/浏览）/ 传输记录 / 外观皮肤 / 前台服务通知状态行 / 接收目录自定义。

## E. Root 增强全量 & 机型验证

E1 root 命令矩阵落地（8.0 已实测 LocalSocket；11+ cmd wifi 需第二台 11+ 设备）
E2 10/11 段真机验证（Specifier / addNetwork+WRITE_SETTINGS / LocalOnly 盲区行为）

## F. 仓库/发布

F1 git 远端 + 提交者身份正式化 + 推远端
F2 release 签名（正式 keystore）、版本管理、sideload 分发

## 近期顺序

**A5 联测（进行中）→ A6（Wi-Fi 监听回报）→ B1（12S root 自动热点）→ B2/B3 → C（LocalSend 直连）→ 异网全链路 → D/E/F 收尾**