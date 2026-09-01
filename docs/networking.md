# Bluelink · 异网组网设计（热点同网）

> 2026-09-01 定稿（无码讨论收敛）。配套：ui-design.md（UI）、troubleshooting.md（排障）。

## 1. 定位

BLE 握手判定双方为**异网**（一方蜂窝/不同 Wi-Fi）后，通过热点临时组建局域网，为 LocalSend 直连铺路：

```
握手完成 → 异网判定 → 用户确认组网 → 角色协商 → 开热点 → 发offer → 对端接入 → 获IP
→ 同网复核(子网+TCP 53317) → LocalSend 传输 → 收尾(自动关热点/断连/恢复原网络)
```

## 2. 角色协商（可切换 + 冲突自动裁定）

- 默认由「扫描发起方」先当热点方；**任何一方开热点失败可切换角色**（对方来试）
- 双方**同时**都想开（冲突）→ 自动裁定，等级：
  - **L1（同级）：root 可用 与 私有 API 可用**——二者不分先后，**同级内电量高者开**
  - L2：LocalOnlyHotspot 可用（⑧⓯不可用时的次级，不参与 L1 仲裁；单方自动降级到此级）
  - 手动 ④ 不参与仲裁（全失败时各自落入人工引导）
- 手握阶段已交换：root 能力（握手 JSON 已含）、网络状态；电量由握手 JSON **新增 `battery` 字段**交换（本期扩展）

## 3. 热点开启四级策略（自动逐级降级 + 手动最终兜底）

| 优先级 | 方式 | 条件 | 密码 |
|---|---|---|---|
| ① | **root 真热点**（shell `cmd wifi` / 8.0 反射 `setWifiApEnabled`+改 softap.conf，见 troubleshooting §7） | 本机 root | 自设（App 生成）→ 自动发 |
| ② | **私有 API 常规热点**（反射 `setWifiApEnabled`，MacroDroid 式） | 非 root；**按版本/机型运行时 try 实测降级**（不预验——机型差异太大；8–9 可靠，10–12 撞运，13+ 大概率不可） | 自设 → 自动发 |
| ③ | **LocalOnlyHotspot**（公开 API 8.0+） | 非 root 且⓪⓵不可用；**仅密码可取版本启用**：8–9＝getWifiConfiguration 自动读；13+＝系统弹窗展示→用户回填；**10–12 密码盲区 → 本机此级直接禁用（跳过）** | 8–9 自动 / 13+ 回填 |
| ④ | **手动系统常规热点** | 全版本最终兜底（①②③全失败/禁用时） | **用户自定义**：用户自行决定密码（App 提供输入框仅作登记，不生成不指定）→ 同密码在系统设置开热点 → BLE 发对端；**对端接入失败才弹窗要求手动输入** |

- 说明：② 不要求预先机型验证——运行时按当前版本尝试，能开就开、抛异常即降级；③ 在 10–12 非 root 直接标记不可用（App 侧读不到密码、系统 UI 不展示，自动组网无从谈起，落入 ④）
- SSID/密码策略：①②由 App 自设固定格式（`Bluelink-XXXX` + 随机 8 位密码）；③④口令由系统/用户决定 → 回填转达

## 4. GATT 持久信令会话（传输通道）

现有握手的 GattClient 完成后 cleanup 断开——**需要扩展为持久会话**：握手完成后保留连接（或握手后自动重连），通过 WRITE/NOTIFY 双向承载组网信令。消息（JSON，单条 ≤500B，复用现有编解码能力）：

| 消息 | 方向 | 字段 | 说明 |
|---|---|---|---|
| `offer` | 热点方→对端 | `{type, ssid, pwd, ip, hotspotType}` | 热点已开，附本机热点 IP |
| `joined` | 对端→热点方 | `{type, ip}` | 已接入热点，报自己的热点 IP |
| `abort` | 双向 | `{type, reason}` | 取消/失败/切换角色 |
| `ack` | 双向 | `{type}` | 关键步骤确认（超时重发） |

## 5. 组网状态机

```
IDLE ──用户确认──▶ NEGOTIATING(角色裁定/可切换)
   ──▶ HOTSPOT_STARTING(①root → ②私有API try → ③LocalOnlyHotspot(10-12盲区禁用) → ④手动+回填)
   ──▶ OFFER_SENT(BLE发offer,等joined)
   ──▶ WAIT_JOIN(对端接入: 11+ Specifier弹窗/8-10 addNetwork+WRITE_SETTINGS/root静默)
   ──▶ JOINED(互知IP → 同网复核: 子网+TCP 53317)
   ──▶ TRANSPORT(LocalSend直连, 见后续传输设计)
   ──▶ TEARDOWN(⑨自动收拾: 关热点/断连/恢复原网络)
每步超时(默认15s)或失败 → abort → 降级/切换角色/人工引导
```

## 6. 对端接入矩阵（非 root 主线，root 增强可选）

| 版本 | 接入方式 | 用户交互 |
|---|---|---|
| 8–10 | `WifiManager.addNetwork/enableNetwork`（需先引导授权「修改系统设置」AppOps） | 一次授权页 |
| 11–12 | `WifiNetworkSpecifier`（API 29+） | 一次系统弹窗确认 |
| 13+ | 同左（NEARBY_WIFI_DEVICES） | 一次系统弹窗确认 |
| root | 8.0 `LocalSocket @android:wpa_wlan0`（已实测）/ 11+ `cmd wifi connect-network` | 无 |

## 7. UI 交互（对齐 ui-design）

- 设备详情弹层：异网时出现「**组建临时局域网**」按钮（用户确认触发——已定）
- 组网进度复用三泳道（②组网泳道展开：开热点/发offer/等待接入/获IP）
- 开热点**自动 + Toast 轻提示**「热点开启，本机 Wi-Fi 将断开」（已定）
- 密码登记 UI：④ 使用**用户自定义制**——App 提供密码输入框供用户登记（App 不生成/不指定密码），用户按自己定的密码在系统设置开热点；登记值随 offer 发出
- **对端接入失败才弹窗**：对端默认按 offer 自动接入（11+ 一次系统确认框）；仅当接入失败/密码不符/超时重试后 → 弹「手动输入密码」对话框（信息取自 offer 或用户手输）
- 失败降级提示可操作（手动开热点按钮、重试、切换角色）

## 8. 收尾（已定）

传输完成 → 热点方自动关热点、对端自动断连、双方尝试恢复原 Wi-Fi（省电/回归常态）。

## 9. 接线关系

- 握手 JSON 扩展：`battery` 字段（角色裁定用）；可复用现有 `root`/`net` 字段
- 同网判定：`SameLanChecker` 组网后重跑（hotspot 子网比较）+ `probeTcp(53317)` 实线接通（本地已有接口待接线）
- LocalSend：组网后双方 IP 互知 → 免 mDNS 直连（后续传输包）

## 10. 待验证（开码前/并行）

1. 荣耀 8（8.0）：③ LocalOnlyHotspot 首走（getWifiConfiguration 读密码）与对端接入、BLE 密码转达全流程
2. 12+ 测试机：③ 在 10–12 直接禁用 → 验证 ④ 手动开热点 + 回填 + BLE 转达路径
3. ② 私有 API 不预验：运行时 try/catch 实测，纳入排障手册按机型回填结果
4. 13+ 设备（后续有机器时）：③ 弹窗回填路径
---

## 11. 热点最终方案（2026-09-01 决策定稿）

- **主优先级（2026-09-01 定案）**：
  1. **②' 系统预配热点自动开**（Binder 直呼 `ITetheringConnector.startTethering`，sdk 26-33，无条件直连+NEARBY/写设置前置+密码登记一次）——最高级（v0.3.4 落实中）
  2. **③ LocalOnlyHotspot**——8-9 自动读密码（全自动）；13+ 系统弹窗+App 登记回填（自动开+抄密码一次）；10-12 密码盲区禁用
- **兜底**：④ 手动系统热点（全版本；10-12 现实出口）
- **② 私有 API**：仅保留「开启/关闭」能力，可用版本（8-9，targetSdk27 legacy 豁免）直接反射；其余版本 try 失败即降级（Android 12 blacklist 无条件拦截已实测）
- **root 路线废弃**（A15/KernelSU 上 cmd wifi 残缺+反射方法移除，已删 B1）
- **Shizuku / Binder 直呼 connectivity.startTethering：暂不引入**（MacroDroid 逆向作为技术档案；结论存档于 docs/macrodroid-notes.md）
- targetSdk=27 保留（② 8-9 路径需要 legacy 豁免；对 ③ ④ 无副作用）
- **③ 实测定案（v0.3.9.1/0.3.9.2）**：onStarted 统一先试读 preSharedKey（不按 sdk 分开）——**A15 自动读密码成功**（NEARBY 33+ 授权前置做过即读得到）；29-32 由「盲区禁用」改为**放行调用+先试读**（A12 实测定案）；读空才回填（33+）/降 ④（26-32）。
- **OFFER_SENT 等 joined 120s**（v0.3.9.2，PEER_JOIN_TIMEOUT_MS）：对端接入含系统 Specifier 确认弹窗，15s 必不够。
- **同网复核放宽（v0.4.0）**：子网一致即通过；probeTcp(53317) 降辅助（服务监听前不阻塞）。
