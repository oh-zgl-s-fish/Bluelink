# MacroDroid 热点开关机制 · 逆向档案

> 2026-09-01 提取自 MacroDroid 5.63.11（com.arlosoft.macrodroid, versionCode 896300011, minSdk 23, **targetSdk 35**, 11 个 dex）。方法：apk 拆包 + SDK dexdump（build-tools/34）。
> 目的：评估「Android 12 无 root 自动开常规热点」可否照搬。结论：**不引入**（见文末）。

## 实证（dexdump 符号）

### SetHotspotAction（classes6.dex）关键串
```
android.os.ServiceManager / getService / asInterface / Failed to get service interface
startTethering / stopTethering
android.net.TetheringRequestParcel
wifi / WifiAPState / ForceLegacy / com.arlosoft.macrodroid.MACRO_NAME
```
`TetheringRequestParcel` → 面向 **Android 12+（sdk31+）的 ConnectivityManager.startTethering 新 Parcel 形态**。

### 其它
- `SetAirplaneModeAction.W0`：`const-string "svc wifi enable"` → `common.w1.w0(String[])`（shell 执行工具）
- `SetWifiAction`：`setWifiEnabled` + sdk29 版本门
- classes7：`WIFI_AP_STATE`（Settings.Global）
- 内置 **Shizuku**：`rikka.shizuku.ShizukuProvider` / `moe.shizuku.api.BinderContainer`（classes3/11）

## 机制推断（高置信）

1. **开关核心 = Binder 直呼 `connectivity` 服务**：`ServiceManager.getService("connectivity")` → `asInterface`（动态 Proxy/InvocationHandler）→ 按方法名 `startTethering`/`stopTethering` 走 **IBinder.transact**（旁路 hidden API 限制，因为 transact 是公开 API，方法名/事务在服务端匹配）；`TetheringRequestParcel` 构造 sdk31+ 的参数载荷。
2. **版本分支**：`ForceLegacy` / `WifiAPState` / `WifiManager` 路径 = 老版本或状态查询分支；sdk 门控（31）选择 Parcel 形态。
3. **shell 兜底**：`svc wifi enable` 等命令经 common shell 工具（需 ADB/Shizuku/root 身份）。
4. **Shizuku 集成**＝以 shell(2000)/root 身份运行上述 Binder 调用与 shell 命令（MacroDroid「ADB/Shizuku 模式」的载体）。

## 可行性定论（待实测修正）

- **基于 AOSP 权限模型推断**：Binder 直呼 `connectivity.startTethering` 通常会被服务端权限校验拒绝（要求 `NETWORK_SETTINGS`/网络栈特权）——但 **ROMI/版本差异可能未严格校验**，且若 MacroDroid 是「先无条件 transact、失败才引导 Shizuku/ADB」，就能解释「**无 Shizuku 也能开**」的用户实测反馈。
- ⏳ 待第 3 次逆向确认 SetHotspotAction 内是否存在无条件直连路径；并结合真机实测（无 Shizuku 的 A12/MacroDroid 开热点成功与否）修正此节。
- 与我们的 ② 反射（blacklist → sdk11/12 起 NoSuchMethod）同为「私有 API 只能做开关」；Binder 直呼有无权限门以实测为准。

## 对 Bluelink 的结论

1. 「私有 API 仅能开关」认知成立（② 维持现状即可）。
2. **无感常规热点只有两条路**：Shizuku/ADB（引入新依赖+授权流程）或 ④ 手动——**维持 LocalOnlyHotspot + 手动兜底主线（§11）**。
3. 若未来有 root 设备（如 12S），root 侧可直接 `cmd wifi`/`svc wifi`（B1 废弃前的验证结论），不依赖 MacroDroid 手法。

## 素材位置
- `/root/user/macrodroid/`：macrodroid.apk（89MB）、apk/（11 dex）、set_hotspot_action.txt（4499 行）、hotspot-symbols.txt（378 行）