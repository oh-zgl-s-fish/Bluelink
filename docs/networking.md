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
- 双方**同时**都想开（冲突）→ 自动裁定，优先级：**root > 私有 API 可行 > 电量**；**同级时电量高者开热点**
- 手握阶段已交换：root 能力（握手 JSON 已含）、网络状态；电量由握手 JSON **新增 `battery` 字段**交换（本期扩展）

## 3. 热点开启策略（两级自动 + 手动兜底，无 LocalOnlyHotspot）

| 优先级 | 方式 | 条件 | 密码 |
|---|---|---|---|
| ① | **root 真热点**（shell `cmd wifi` / 8.0 反射 `setWifiApEnabled`+改 softap.conf，见 troubleshooting §7） | 本机 root | 自设（App 生成）→ 自动发 |
| ② | **私有 API 常规热点**（反射 `setWifiApEnabled`，MacroDroid 式） | 非 root 且反射可行（8–9 可靠；10–12 机型差异 try 实测；13+ 大概率不可） | 自设 → 自动发 |
| ③ | **手动系统常规热点**（①②不可用时：提示用户手动开热点，用户设的密码回填进 App） | 全版本兜底 | 用户自设 → 回填 → 经 BLE 发对端 |

- **设计决定**：不采用 LocalOnlyHotspot（密码读取受版本限制、无外网、系统 UI 不展示），全线走**常规热点**路线：能自动（①②）就自动，不能就**手动开 + 回填密码**（一次人工参与，全版本可行）
- SSID/密码策略：①②由 App 自设固定格式（`Bluelink-XXXX` + 随机 8 位密码）；③由用户在自己热点上设定后回填

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
   ──▶ HOTSPOT_STARTING(①root真热点 → ②私有API常规热点 → ③提示手动开+回填)
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
- 密码回填 UI（10–12/手动兜底时）：热点方表单 + 展示给用户
- 失败降级提示可操作（手动开热点按钮、重试、切换角色）

## 8. 收尾（已定）

传输完成 → 热点方自动关热点、对端自动断连、双方尝试恢复原 Wi-Fi（省电/回归常态）。

## 9. 接线关系

- 握手 JSON 扩展：`battery` 字段（角色裁定用）；可复用现有 `root`/`net` 字段
- 同网判定：`SameLanChecker` 组网后重跑（hotspot 子网比较）+ `probeTcp(53317)` 实线接通（本地已有接口待接线）
- LocalSend：组网后双方 IP 互知 → 免 mDNS 直连（后续传输包）

## 10. 待验证（开码前/并行）

1. 荣耀 8（8.0）：私有 API `setWifiApEnabled` 反射开常规热点（用户手动验证或最小实验 APK）
2. 12+ 测试机：私有 API 可行性（预期被拦 → 走 ③ 手动开热点 + 回填路径）
3. ③ 手动路径的 UI 流（开热点指引 + 密码回填 + BLE 转发）