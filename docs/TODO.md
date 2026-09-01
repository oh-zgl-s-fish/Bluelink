# Bluelink · 待办清单

> 2026-09-01 快照。配套：README（路线）、docs/networking.md（组网设计）、docs/troubleshooting.md（排障）。
> 代码约定：编码/编译修复一律 pi 子 agent，主管负责构建/验收/文档/归档。

## A. 进行中 / 待验证

| # | 项 | 状态 | 依赖/验证机 |
|---|---|---|---|
| A1 | v0.2.2-verify **GATT 长期收发自测**（120s ping/pong） | 📦 已出包，**待双机实测** | 成功=发送==成功 且跑满 2 分钟 |
| A2 | **offer 发送失败**（对端 PEER 等 offer 15s ≠ 热点方④手动 120s 超时不对等，对端先 abort） | 已定位，待 pi 修 | 修：PEER 等 offer 超时 → 120s |
| A3 | 「root 失败不静默落④」：①② 失败给原因提示，④ 仅作无自动路径兜底 | 讨论中，待并入 B 包规格 | — |

## B. 组网 B 包（热点真实现）

| # | 项 | 状态 | 验证机 |
|---|---|---|---|
| B1 | ① **root 真热点**：root 时反射 `setWifiApEnabled`（8-13）/ `cmd wifi`；自设 `Bluelink-XXXX`+随机密码；取热点 IP | 待开 | **小米 12S(root) 现成** |
| B2 | ② **私有 API**：反射 `setWifiApEnabled` + WRITE_SETTINGS AppOps 尝试，try 实测降级（不预验） | 待开 | 12S 无 root 端 / 各机型 |
| B3 | ③ **LocalOnlyHotspot**：8-9 `getWifiConfiguration` 读密码自动；13+ 系统弹窗用户回填；10-12 盲区禁用 | 待开 | 荣耀8(8.0) |
| B4 | root 静默接入（对端 root：`cmd wifi connect-network` / 8.0 LocalSocket）+ **传输完自动收尾** | 待开 | — |
| B5 | WifiJoiner 补 root 静默路径（A4 留 stub） | 并入 B4 | — |

## C. LocalSend v2 传输（同网直连）

| 项 | 内容 |
|---|---|
| C1 | HTTP Server（端口 53317）+ `prepare-upload`/`upload`/`cancel`/`info`；先 HTTP 明文自互通，官方互通+HTTPS 二期 |
| C2 | 同网 TCP 探测接线（SameLanChecker + probeTcp 53317，组网完成/握手后复核） |
| C3 | 发送入口（SAF 起步，自建文件浏览器后按 UI 定稿替换）+ 接收落盘 + 进度显示 |

## D. 完整 UI（按 docs/ui-design.md 全量）

抽屉 / 设置页 / 权限总览 / 文件浏览器（最近/收藏/浏览） / 传输记录 / 外观皮肤（深浅分开可选）/ 前台服务通知状态行 / 接收目录自定义。

## E. Root 增强全量 & 机型验证

| 项 | 内容 |
|---|---|
| E1 | root 命令矩阵落地（8.0 已实测 LocalSocket；11+ `cmd wifi` 需第二台 11+ 设备验证） |
| E2 | 10/11 段真机验证：`WifiNetworkSpecifier`、8-10 `addNetwork`+WRITE_SETTINGS、LocalOnlyHotspot 盲区行为 |

## F. 仓库/发布

| 项 | 内容 |
|---|---|
| F1 | git 远端创建 + 提交者身份正式化（现占位 zglinus@users.noreply.github.com）+ 推远端 |
| F2 | release 签名（正式 keystore）、版本管理规范、sideload 分发 |

## 近期顺序建议

**A1 验证（进行中）→ A2 超时对齐 → B1(12S root 自动热点) → B2/B3 → C(LocalSend 同网直连) → 异网全链路整合 → D/E/F 收尾**