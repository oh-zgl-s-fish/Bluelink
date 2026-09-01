# Bluelink · 真机排障手册

> 记录真机验证中发现的问题：现象 → 排查过程 → 根因 → 修复 → 状态。
> 每条以日期与构建版本标注；修复合入主文档对应章节（ADR / §7.x）。

---

## 1. BLE 扫描无结果（Android 12+ 静默坑）

- **版本/日期**：v0.1.0 → **已修复 v0.1.1**（2026-09-01）
- **现象**：两台 Android 12+ 设备，App 均广播成功（状态卡无错误红字），扫描列表为空，无任何扫描异常提示
- **排查**：
  1. 代码审查：`startScan` 正常、`onScanFailed` 未触发（UI 无「扫描异常」红字）、广播/扫描配置标准
  2. 现场确认：荣耀 8 蓝牙关且未装 App，排除参与；测试机均为 12+
- **根因**：Manifest `BLUETOOTH_SCAN` 未声明 `android:usesPermissionFlags="neverForLocation"`。Android 12+ 将扫描结果视为位置数据：未授定位权限时**静默不投递扫描结果**——`startScan` 成功、无回调、无错误；广播走 ADVERTISE 不受影响
- **修复（v0.1.1）**：Manifest 加 `neverForLocation`；扫描回调加日志；文档 ADR 22
- **验证**：v0.1.1 双机重测扫描互通 ✅
- **关联**：8–11 机型仍需 `ACCESS_FINE_LOCATION`

---

## 2. GATT 握手 10s 超时（MTU 未协商）

- **版本/日期**：v0.1.1（2026-09-01），**已修复 v0.1.2**（MTU 环节）
- **根因**：BLE 默认 ATT MTU=23，单包载荷上限 20B；握手 JSON 约 150B 全程未协商 MTU → 两端消息都传不过去 → 10s 超时
- **修复（v0.1.2）**：`requestMtu(512)` 先行 → `onMtuChanged` 联动 discoverServices → `onCharacteristicWrite` 发送确认 → 写入前 `mtu-3` 长度校验
- **验证**：v0.1.2 实测 **MTU 517/512 协商成功、150B 写入入队成功** ✅——但由此暴露下一问题（§3）

---

## 3. GATT 握手写入确认永不回调（攻坚中）

- **版本/日期**：v0.1.2 → v0.1.5（2026-09-01），**未定案，v0.1.5 待装机验证**
- **现象**：扫描互通、连接成功、MTU 协商成功（517/512）、150B 握手 JSON 已入队写入——但 **`onCharacteristicWrite` 永不回调**，对端 GattServer 也收不到「收到握手 JSON」，3s 兜底超时（v0.1.4 起可快速失败，v0.1.2/v0.1.3 干等 10s）
- **排查演进（关键数据点）**：
  | 版本 | 改动 | 实测发现 |
  |---|---|---|
  | v0.1.2 | MTU 协商 + 发送确认 | 写入后无任何回调，10s 超时 |
  | v0.1.3 | App 内置诊断日志 | 日志实锤：写入发起后 `onCharacteristicWrite` 从未回调；对端 Server 未收到 JSON |
  | v0.1.4 | 连接仲裁 + 200ms 窗口 + 3s 写入兜底 | 仲裁按地址查 Server 连接表**失效**——BLE 随机地址假名：广播/扫描身份（59:9C…）≠ 连接身份（76:9B…），`isDeviceConnected(扫描地址)` 匹配不到 → 双向连接仍并发成立；写入仍无回调（3s 兜底如期触发） |
  | v0.1.5 | `writeCharacteristic` 返回值检查 + 握手期拒连（地址无关） | **待装机验证**——若返回 false 则坐实「写入被栈拒绝」；握手期 Server 掐断一切新连接消除双连接 |
- **当前假设**（按嫌疑排序）：
  1. `writeCharacteristic` 实际返回 false（写入从未入队）——v0.1.5 新增检测，待实测
  2. 双连接并发互锁（双向连接时 ATT 队列写入挂起）——握手期拒连已治，待实测
  3. 其它（国产蓝牙栈对 150B 长写入/快节奏写入的兼容问题）——必要时降级分片写（每包 ≤20B）对照
- **验证计划**：v0.1.5 出包后做 **A/B 对照**——A 组严格单向（对端完全不碰 App）、B 组互点（验证握手期拒连）

---

## 附：诊断手段（v0.1.3+ 以 App 内置为主）

- **App 内置诊断**（推荐）：主页面状态卡 →「诊断」→ 查看/复制全部/导出文件（`Android/data/com.zglinus.bluelink/files/diag_*.txt`）——不依赖 adb
- **logcat**：`adb logcat -d -s BleAdvertiser:* BleScanner:* GattClient:* GattServer:* BluelinkEngine:*`
- 标签速查：`GattClient`（连接/MTU/发现/写入/确认/失败）、`GattServer`（收到 JSON/订阅/通知）、`BleScanner`（v0.1.4+ 同设备 1s 去抖）、`BluelinkEngine`（状态机）
- 注意：扫描日志 1s 去抖后约每秒 1 条，512 条缓冲可覆盖约 8 分钟完整链路