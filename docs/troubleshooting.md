# Bluelink · 真机排障手册

> 记录真机验证中发现的问题：现象 → 排查过程 → 根因 → 修复 → 状态。
> 每条以日期与构建版本标注；修复合入主文档对应章节（ADR / §7.x）。

---

## 1. BLE 扫描无结果（Android 12+ 静默坑）

- **版本/日期**：v0.1.0 → 修复于 v0.1.1（2026-09-01）
- **现象**：两台 Android 12+ 设备，App 均广播成功（状态卡无错误红字），扫描列表为空，无任何扫描异常提示
- **排查**：
  1. 代码审查：`startScan` 正常、`onScanFailed` 未触发（UI 无「扫描异常」红字）、广播/扫描配置标准
  2. 现场确认：荣耀 8 蓝牙关且未装 App，排除参与；测试机均为 12+
- **根因**：Manifest 中 `BLUETOOTH_SCAN` 未声明 `android:usesPermissionFlags="neverForLocation"`。Android 12+ 将扫描结果视为位置数据：未授定位权限（本 App 12+ 只申请 BLE 三权限）时**静默不投递扫描结果**——`startScan` 成功、无回调、无错误；广播走 `BLUETOOTH_ADVERTISE` 不受影响，故出现「广播正常、扫不到」的表象
- **修复（v0.1.1）**：
  - `AndroidManifest.xml`：`BLUETOOTH_SCAN` 加 `android:usesPermissionFlags="neverForLocation"`
  - `BleScanner.onScanResult` 增加 `Log.i(TAG, "onScanResult device=… rssi=…")` 诊断日志
  - 文档：ADR 22、权限矩阵标注
- **验证**：v0.1.1 双机重测——扫描互通（进入下一问题即证明通）
- **关联**：8–11 机型仍需 `ACCESS_FINE_LOCATION`（行为不变）

---

## 2. GATT 握手 10s 超时（MTU 未协商）

- **版本/日期**：v0.1.1（2026-09-01），诊断完成、**待修复（拟 v0.1.2）**
- **现象**：两台设备扫描互相可见（问题 1 已解决），点设备触发握手，10s 后提示「握手超时」，未进入详情
- **排查（代码审查，未改码）**：
  - `GattClient`：连接成功 → `discoverServices` → 订阅 NOTIFY（写 CCC）→ `writeCharacteristic` 写入握手 JSON（约 100–150B）
  - **全程无 `requestMtu()`**；未实现 `onCharacteristicWrite`、未检查写返回值
  - `GattServer`：`onCharacteristicWriteRequest` 解析失败则静默不回复；`notifyCharacteristicChanged` 回发同样无 MTU 保护
  - `Constants`：`MAX_HANDSHAKE_BYTES = 150`
- **根因**：BLE 默认 ATT MTU = 23 字节，单次写入/通知载荷上限 = **20 字节**（23−3）。握手 JSON 约 150 字节远超上限：
  1. Client 写 150B → 失败或静默截断（无发送确认，失败无声）
  2. Server 收截断数据 → `decode` 失败 → 不回复；即使成功，回发 notify 150B 同样超 MTU → 发送失败
  - 消息两端都传不过去 → 只能等满 10s 超时（现象吻合）
- **修复方向（待实施）**：
  1. `GattClient` 连接后先 `requestMtu(512)`，在 `onMtuChanged` 回调中再 `discoverServices` / 写消息（payload ≤ MTU−3，150B JSON 无压力）
  2. 实现 `onCharacteristicWrite` 校验发送状态，失败即时回调（不再干等超时）
  3. 加固（可选）：Server `decode` 失败时回结构化错误；老设备 MTU 协商值低时的降级处理
- **已验证无问题**：CCC 订阅时序（Server 有订阅跟踪 + 挂起补发）、广播 `connectable=true`

---

## 附：logcat 排查标签

| 标签 | 关注点 |
|---|---|
| `BleAdvertiser` | 广播启动/失败原因 |
| `BleScanner` | `onScanResult device=<mac> rssi=…`（v0.1.1+） |
| `GattClient` | 连接/服务发现/写入/收到握手/失败原因 |
| `GattServer` | 收到握手 JSON/通知发送状态 |
| `BluelinkEngine` | 状态机与 UI 状态更新 |

抓取：`adb logcat -d -s BleAdvertiser:* BleScanner:* GattClient:* GattServer:* BluelinkEngine:*`