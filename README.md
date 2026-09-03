# 蓝鲸·X（Bluelink）

> **离线 · 端到端 · 无服务器文件传输**：BLE 发现握手 → 同网判定 → 异网自动组网 → LocalSend 协议直传。
> 状态快照：v0.5.12（2026-09-02）· 仓库：`github.com/oh-zgl-s-fish/Bluelink`

Android 8+（API 26+）设备间文件传输客户端。两台设备**不需要任何服务器/账号/互联网**：打开 App 即可被 BLE 发现，扫描握手交换网络信息，自动判断双方是否同网——同网直接传输，异网则由一方自动开启热点（系统预配热点 / LocalOnlyHotspot / 手动四级兜底），另一方自动接入，随后经 **LocalSend v2 协议**（HTTP 53317）完成文件收发。

## 特性

- 🔵 **BLE 全链路**：广播/扫描 → GATT 握手（MTU 512）→ 持久信令会话（写队列串行化）
- 🧭 **同网免热点直连**：双方同一 Wi-Fi（SSID+子网一致）→ 握手即传，跳过热点
- 📶 **异网自动组网**：系统预配热点自动开（Android 8–15 多版本机制）→ offer → 自动接入（Specifier）→ 复核直放
- 🔐 **PIN 配对验证**：关 / 仅首次 / 每次 三模式，防陌生设备误连
- 📤 **LocalSend v2 传输**：HTTP 53317、multipart 流式、进度/取消、SAF 选文件与保存目录
- 🧹 **温和收尾**：传输完可选关热点/断网，BLE 保留可续传
- 🎨 Material 3 语义化 UI（MD3 Skill 驱动重构）+ 全版本权限矩阵适配
- 🔒 密码/PIN 全程不回显；内置诊断日志（App 内查看/复制/导出）

## 工作流

```
两台设备开 App（同一局域网或面对面）
      │
      ▼
BLE 广播 ⇄ 扫描 → 点设备握手（GATT 交换网络信息/电量/设备标识）
      │
      ▼
同网判定（双方 Wi-Fi 同 SSID 且子网一致？）
      ├─ 是 → 同网直连：双方起 LocalSend 服务 → 传输就绪
      └─ 否 → 仲裁（系统预配/私有 API/LocalOnly/手动 等级 + 电量决定谁开热点）
              ├─ 系统预配热点自动开（②'：MD 同源按名枚举；③ LocalOnly：全版本密码自动读）
              ├─ offer（SSID/密码/IP 经 BLE 下发）→ 对端自动 Specifier 接入（或手动连，SSID 匹配自动回 joined）
              ├─ 复核直放（热点方 joined 即同网）→ TRANSPORT
              └─ 兜底：④ 手动系统热点（用户开+登记密码一次）
      │
      ▼
LocalSend v2：发送（SAF 选文件 → prepare-upload → multipart 流式）⇄ 接收（落盘用户所选目录）
      │
      ▼
温和收尾：可选「关闭热点/断开网络」，BLE 保留可继续
```

### 版本分支矩阵

| 场景 | 路径 |
|---|---|
| 同 Wi-Fi（最常用） | 握手 → **同网直连**，免热点 |
| 异网自动（Android 8–15） | 仲裁 → ②'/③ 自动开+读密码 → offer → Specifier 接入 → TRANSPORT |
| 手动连热点 | Wi-Fi 监听 SSID 匹配 → 自动回 joined |
| 密码盲区兜底（个别 ROM/手动） | ④ 手动系统热点+密码登记 |

### Agent 写作工作流（AI 协作开发规范）

本项目由**人类 + coding agent（pi 子代理）**协作推进，流程固定为：

```
1. 需求/修复 → 先文字确认细节（设计定稿入 docs/）——避免返工
2. 任务切割 → 单任务≤1 主题（大需求拆 UI/功能多期），交 pi 子代理
3. 子代理写码约束（硬性）：
   · 只写代码：不构建 / 不下载 / 不联网；bash 仅 pwd/ls 级只读
   · 交付 = write 真实落盘 + grep 回显佐证（纯描述不算交付）
   · 写 staging 工作目录（不直写 /srv 仓库，主管收割同步）
4. 主管（协调者）验收：代码入库 → 构建出包（SHA 记录）→ 归档 /root/user
5. 真机实测（用户）→ 反馈日志 → 主管判读 → 下轮修复
6. 每版本同步文档（README/TODO/troubleshooting/networking）
```

**角色边界**：子代理只写代码与静态验证；构建/打包/资源生成/文档/发布由主管执行；真机操作与视觉判定由人类完成。

### 技术要点（docs/）

- `docs/networking.md` — 组网设计定稿（仲裁/热点等级/同网判定/超时语义）
- `docs/ui-design.md` — UI 设计（两态左右布局/抽屉/个性化规格）
- `docs/md3-audit.md` — Material 3 审计与落地（语义 token/组件行为/无障碍）
- `docs/troubleshooting.md` — 真机排障档案
- `docs/TODO.md` — 任务看板（未完成项见下）

## 未完成（TODO 摘要）

- **UI1b-C**：设置-设备页（PIN 管理/重置指纹、热点密码预设、下载目录、权限检测）+ 全局深浅切换
- **工程收尾**：移除 LocalOnly 测试包开关（`DISABLE_PRIVATE_API`）并回归 ②；release 签名
- **迭代候选**：PIN 短时窗过期/失败锁定；直连加密（握手指纹互认）；8-9 段机型回归（荣耀 8）
- 详细见 `docs/TODO.md`

## 构建

```bash
./gradlew --no-daemon assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17、Android SDK（compileSdk 37 / targetSdk 34 / minSdk 26）、Gradle 9.7.1 + AGP 9.3.2 + Kotlin 2.4.10。

## 开源许可

本项目采用 **GNU General Public License v3.0**（GPL-3.0）。完整文本见 [LICENSE](LICENSE)。

### 第三方许可兼容性

| 组件 | 许可 | 与 GPL-3.0 兼容性 |
|---|---|---|
| AndroidX（Compose/Material3/Activity 等） | Apache-2.0 | ✅ 兼容（Apache 2.0 与 GPLv3 可共存；分发时保留其 NOTICE） |
| org.json（Android 平台内置） | JSON License（宽松） | ✅ 宽松许可不构成冲突 |
| Kotlin/AGP/Gradle | Apache-2.0 / 各工具链许可 | ✅ 构建工具，非分发组件 |
| 自研代码（本仓库全部源码） | GPL-3.0 | — |
| 逆向参考（MacroDroid 热点机制） | 仅方法启发，**不含其代码** | ✅ 无拷贝即无衍生义务（已在 docs/macrodroid-notes.md 记录边界） |

> 说明：本仓库不包含任何第三方源码副本；LocalSend 仅为**协议形状参考**（见下致谢），实现为本项目自研，故以 GPL-3.0 分发无外部传染源。

## 致谢

- **[LocalSend](https://github.com/localsend/localsend)** — 传输协议（HTTP 53317 / prepare-upload / upload / cancel 形状）的设计启发；本项目按该协议形状自研实现，用于端到端互通。感谢 LocalSend 社区的出色工作。🫶
- **MacroDroid** — 系统热点「按名枚举 startTethering」与 LocalOnlyHotspot 密码自动读取等机制的逆向参考（仅方法论，未使用其代码）。
- **Material Design 3 / material-design-3-ui-skill** — UI/UX 决策系统与落地指导。

## Contributors

- **zglinus** — 项目维护与集成（编码协调、构建发布）
- **[DeepSeek](https://www.deepseek.com)** — 本仓库绝大多数代码与设计由 DeepSeek 模型生成（需求-设计-编码-修复全程）
- **pi agent（DeepSeek-powered coding subagent）** — 按任务切割执行模块编码/修复/逆向分析（工作流见上文「Agent 写作工作流」）
- **[王宝煲](https://space.bilibili.com/1978636705/)** — 应用图标表情包来自于她 🫶

---

© 2026 zglinus — [GPL-3.0](LICENSE)
