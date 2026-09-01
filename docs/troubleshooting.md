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

## 3. GATT 握手 —— 写入确认永不回调 + JSON 截断（已修复 ✅）

- **版本/日期**：v0.1.2 → **v0.1.7 双机握手成功结案**（2026-09-01）
- **现象**：扫描互通、连接成功、MTU 协商成功（517/512）、150B 握手 JSON 已入队写入——`onCharacteristicWrite` 不回调 / 或回调成功但对端 decode 失败「握手 JSON 无效」→ 10s 超时
- **排查演进（关键数据点）**：
  | 版本 | 改动 | 实测发现 |
  |---|---|---|
  | v0.1.2 | MTU 协商 + 发送确认 | 写入后无任何回调，10s 超时 |
  | v0.1.3 | App 内置诊断日志 | 实锤 `onCharacteristicWrite` 从未回调；对端 Server 未收到 JSON |
  | v0.1.4 | 连接仲裁 + 200ms 窗口 + 3s 写入兜底 | 按地址仲裁**失效**（BLE 随机地址假名：广播身份 ≠ 连接身份）；写入仍无回调 |
  | v0.1.5 | 写返回值检查 + 握手期拒连（地址无关） | 拒连生效仍无回调——排除双连接互锁；锁定 ATT 操作时序 |
  | v0.1.6 | **ATT 操作串行化**（CCC 写完 → onDescriptorWrite 再发 WRITE） | 🎉 `onCharacteristicWrite status=0` 首次出现——前向打通；回程仍超时 |
  | v0.1.7 | 解除 150B JSON 硬截断（上限 500、不 copyOf） | 🎉 对端「收到握手」→ 回 notify → **握手成功，全链路闭环** |
- **最终根因（两层叠加）**：
  1. **ATT 操作未串行化**：`onServicesDiscovered` 里 CCC 写入未完成就背靠背发 WRITE → 蓝牙栈单请求模型静默丢弃（v0.1.6 修）
  2. **握手 JSON 被 150B 静态上限硬截断**：MTU 517 时代 150B 属过时限制，`encode` 的 `copyOf(150)` 切坏 JSON 尾部 → 对端 `JSONObject` 解析抛异常 →「握手 JSON 无效」→ Server 不回复（v0.1.7 修）
- **关键经验**：BLE 链路每一环（广播→扫描→连接→MTU→服务发现→CCC→写入→解码→notify→收包）都可能静默失败且互不报错——不靠 App 内置诊断逐步收敛，几乎无法定位；随机地址假名（RPA）会进一步扰乱地址级逻辑
- **验证**：v0.1.7 双机（Android 12+/国产 ROM）**握手成功**，详情弹层显示对方网络信息与同网判定

---

## 附：诊断手段（v0.1.3+ 以 App 内置为主）

- **App 内置诊断**（推荐）：主页面状态卡 →「诊断」→ 查看/复制全部/导出文件（`Android/data/com.zglinus.bluelink/files/diag_*.txt`）——不依赖 adb
- **logcat**：`adb logcat -d -s BleAdvertiser:* BleScanner:* GattClient:* GattServer:* BluelinkEngine:*`
- 标签速查：`GattClient`（连接/MTU/发现/写入/确认/失败）、`GattServer`（收到 JSON/订阅/通知）、`BleScanner`（v0.1.4+ 同设备 1s 去抖）、`BluelinkEngine`（状态机）
- 注意：扫描日志 1s 去抖后约每秒 1 条，512 条缓冲可覆盖约 8 分钟完整链路