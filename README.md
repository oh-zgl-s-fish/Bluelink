# Bluelink

> 正式命名：**Bluelink**（包名 `com.zglinus.bluelink`）。
> 状态：**热点全版本自动链路成型**（v0.3.9.2，2026-09-01）：②' 系统热点自动开（k1/c 按名枚举，sdk26-33）→ ③ LocalOnlyHotspot **全版本统一先试读 preSharedKey**（**A15 实测自动读密码成功**；A12/sdk31 放行实测定案盲区与否）→ ④ 手动兜底；组网链路 OFFER_SENT 等 joined 对齐 120s（对端点系统弹窗）；同网复核放宽至子网一致（v0.4.0 进行中，probe 53317 暂不阻塞）。工程根：/srv/android/bluelink。

Android 8+（API 26+）设备间文件传输客户端：

1. **蓝牙发现**：打开即广播（BLE 为主、经典蓝牙降级），可扫描周围设备
2. **蓝牙握手**：BLE GATT 交换网络状态 / IP / 证书指纹 / PIN / root 能力
3. **局域网判定**：子网比较 + TCP 连通的**实测**（不只看网段）
4. **异网组网**：热点方开热点（root=真热点带外网 / 非 root=LocalOnlyHotspot），对端接入（root=`cmd wifi connect-network` / 非 root=`WifiNetworkSpecifier`）
5. **传输**：内置 LocalSend v2 协议实现，端口 53317，与官方 LocalSend 互通
6. **Root 增强模式（可选）**：逐环节 root 快路径 + 失败自动降级到标准 API

技术栈：Kotlin · Jetpack Compose · Material 3（深色模式跟随系统）· targetSdk 34/35 · minSdk 26

## 目录

- `docs/ui-design.md` — UI 设计定稿（页面线框 + 交互规则 + 决策清单 + root 命令矩阵）
- `docs/troubleshooting.md` — 真机排障手册（BLE 扫描静默坑 / GATT 握手 MTU 超时 / logcat 标签）
- `docs/`（后续）— 架构/协议/权限矩阵细化文档

## 关键设计原则

- 全自动上限受系统限制：目标为「一次点击的引导式自动化」；双端 root 时可完全静默
- 信令与数据分离：BLE 只做握手信令，文件全走 LAN（LocalSend 协议）
- 未验证不启用：root 快路径需先在目标机型（含荣耀 8 / Android 8.0）验证命令矩阵再启用

## 协作与执行约定（2026-09-01 定）

- **编码与编译报错排查**：委派 **pi 子 agent**（DSH `pi_subagent`，one-shot、不支持追问/steering），只执行不反问
- **运行身份与仓库权限**（2026-09-01 修订）：
  - pi 以 **uid 1000（agent）** 运行（实测确认）；**仓库属主 root**
  - pi 对仓库**只读**（读设计文档/参考代码）；产物一律写 pi 自己的工作目录 `/home/agent/pi-subagent/session-<会话id>/`
  - 运行结束插件自动收割产物到父会话工作区（`/root/user`），**主管（root）负责归档进仓库并 git commit**
- **主会话（主管）职责**：设计决策、任务拆包、结果验收、文件归档、文档维护（本目录）
- **任务包格式**：每次委派必须自包含——项目路径 + 上下文背景 + 明确验收标准，一次讲清；产物路径明示为工作目录
- **设备实机操作**：荣耀 8 无线 adb（不稳定 + 需专用 key 环境）由主管负责保活重连与授权，不塞进 pi 任务包
- **执行节奏**：未验证不启用；开工按「后续路线」分期推进，每期由主管验收 + git commit 后归档

## 验证授权边界（2026-09-01 定）

- **允许（主管可远程执行）**：只读验证——读取属性/配置、`su -c id`、Wi-Fi/蓝牙状态查询、`/data/misc/wifi/` 只读
- **需用户手动（主管不得远程执行）**：开/关热点、连接/切换 Wi-Fi、改蓝牙可发现模式等**任何改变设备状态的操作**；用户手动实测，结果回填文档
- 二期/三期热点链路验证依赖用户手动实测；10/11 段 `cmd wifi` 矩阵需第二台 Android 10+ 设备（待确认）