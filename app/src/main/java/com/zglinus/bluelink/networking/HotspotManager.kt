package com.zglinus.bluelink.networking

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.zglinus.bluelink.ble.RootDetector
import com.zglinus.bluelink.diag.DiagLogger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 热点等级（A3b 热启动维度）：
 * ①② 自动热点（L1）细分 root 通道 / 私有 API 通道，③ L2 本地热点（无密码局域网），④ 手动配网。
 *
 * 命名收敛（A3c）：本枚举原名 `HotspotLevel`，与同包 [Arbiter] 内已存在的 `HotspotLevel`
 * （L1_AUTO/L2_LOCAL_ONLY/MANUAL）同名，两处声明处于同一包
 * （com.zglinus.bluelink.networking），构成 Kotlin 重名声明（编译期 redeclaration）。
 * 按收敛方案将**本侧更名为 [HotspotStartLevel]**（Arbiter 侧不动），值与语义不变：
 * [HotspotStartLevel.L1_ROOT] / [HotspotStartLevel.L1_PRIVATE_API] 对应仲裁 L1_AUTO，
 * [HotspotStartLevel.L2_LOCAL_ONLY] 对应仲裁 L2_LOCAL_ONLY，[HotspotStartLevel.MANUAL] 对应仲裁 MANUAL。
 */
enum class HotspotStartLevel {
    /** ① L1 自动热点：root 通道（su 提权创建系统热点；B1 已实现真路径）。 */
    L1_ROOT,

    /** ② L1 自动热点：私有 API 通道（反射系统隐藏接口；一期按 `sdkInt in 26..33` 可尝试启发，与 [Arbiter] 一致；真实可行性由 B 包反射 try 实测收口）。 */
    L1_PRIVATE_API,

    /** ③ L2 本地热点：Local-only 无密码局域网（Android 8-9 或 13+，10-12 盲区禁用）。 */
    L2_LOCAL_ONLY,

    /** ④ 手动配网：UI 提示用户手工输入/分享热点。 */
    MANUAL,
}

/**
 * 热点启动结果。
 *
 * @param success 是否成功开启热点；false 时 [error] 给出降级/等待原因。
 * @param ssid 热点 SSID（成功时返回，供对端连接；手动路径由 UI 回填）。
 * @param pwd 热点密码（成功时返回；root 路径由本包自设随机密码；手动路径由 UI 回填）。
 * @param ip 热点本机 IPv4（B1：root 真热点启动后采集，供 offer 携带；未取到为空串 ""，一期允许）。
 * @param error 失败/等待原因（如 "本包实现(B包)" 降级、`"AwaitingManual"` 等待手动）。
 */
data class HotspotResult(
    val success: Boolean,
    val ssid: String? = null,
    val pwd: String? = null,
    val ip: String? = null,
    val error: String? = null,
)

/**
 * 热点生命周期回调（由 UI / 引擎实现并注入 [HotspotManager]）。
 */
interface HotspotListener {
    /** ④ 手动路径：请求 UI 引导用户手动配网（用户输入密码后回填并经 [HotspotManager.setPassword] 登记）。 */
    fun onManualRequest()

    /** 热点就绪（④ 由 UI 触发、回填密码后走 ready，携带最终 [HotspotResult]）。 */
    fun onHotspotReady(result: HotspotResult)
}

/**
 * 热启动管理器（A3b，单文件；B1 已实现 ① root 真热点，②③④ 仍为降级 stub）。
 *
 * 对应设计文档 docs/networking.md §2「热点角色仲裁」：仲裁器 [Arbiter] 决策 who/level 后，
 * 由本管理器按 [HotspotStartLevel] 实际启动热点。
 *
 * - ①（[HotspotStartLevel.L1_ROOT]，B1 真实现）：
 *   - 前置：复用 [ble.HandshakeProtocol] 内 [RootDetector.isRoot]（应用启动时后台探测缓存），
 *     false → 如实返回失败，交状态机降级试下一级 ②；
 *   - `sdkInt >= 29`（11+，主目标 12S root）：root shell `cmd wifi`——
 *     `set-softap disabled`（best-effort 重配）→ 兼容 try 设置 SSID/密码
 *     （`set-softap config`/`set-softap set`/`set-softap-config`，按 `cmd wifi help` 实际子命令）→
 *     `start-softap`/`set-softap enabled` → 延时 600ms → `cmd wifi status` 校验 started；
 *   - `sdkInt in 26..28`（8–10，荣耀8 兜底路径）：反射 `WifiManager.setWifiApEnabled(WifiConfiguration,true)`
 *     （hidden API；SSID 加引号 "Bluelink-XXXX"、preSharedKey）→ 轮询 `isWifiApEnabled`；
 *     反射失败/返回 false 时经 root 改写 `/data/misc/wifi/softap.conf`（SSID+明文密码）兜底重试一次；
 *   - 两端成功均返回 `HotspotResult(success=true, ssid, pwd, ip=热点本机 IPv4)`（pwd 自设随机，
 *     可随 offer 发对端）；热点本机 IP 至少尝试 2 种采集方式（`ip -4 addr` 定向/全量、ifconfig、
 *     Java NetworkInterface 枚举），失败 ip 为空串 ""；
 *   - 失败（权限/命令不存在/超时/校验不过）→ 如实返回 `HotspotResult(false, error=具体原因)`，
 *     不吞异常，[DiagLogger] 记录退出码/stdout 片段（含密码的命令不回显输出防泄露）；
 *   - root shell 执行后不残留（流关闭、超时 destroyForcibly、不落临时文件）。
 * - ②③（[HotspotStartLevel.L1_PRIVATE_API] / [HotspotStartLevel.L2_LOCAL_ONLY]）：
 *   真实现与私有 API / Local-only 按机型实测均为 B 包范围，本包一律返回失败降级
 *   `HotspotResult(false, error = "本包实现(B包)")`；
 * - ④（[HotspotStartLevel.MANUAL]）：触发 [HotspotListener.onManualRequest] 走 UI 手动配网，
 *   返回骨架 `HotspotResult(false, error = "AwaitingManual")`；用户密码经 [setPassword] 登记，
 *   供后续 offer（热点信息广播）使用。
 *
 * 边界（B1）：只做「启动 + 取信息 + 返回 Result」；关闭/收尾（stop）留 B4；不改状态机。
 * 线程模型：与状态机一致，[start] 同步执行（root shell / 轮询有总预算兜底，正常路径约 2-4s，
 * 最坏失败路径亦有预算护栏，不超状态机 15s 步骤超时窗口）。
 *
 * 私有 API 一期按 `sdkInt in 26..33` 启发（可尝试范围，与 [Arbiter.buildLocalCapability] 的
 * `privateApiCapable` 判定一致）；真实可行性由 B 包反射 try 实测收口。
 *
 * @param listener 生命周期回调（UI / 引擎注入）。
 * @param context 26–28 反射路径需要（经 Context 取 WifiManager）；缺省 null 时该路径返回明确失败，
 *   待接线方注入 applicationContext。29+ `cmd wifi` 路径不依赖。
 */
class HotspotManager(
    private val listener: HotspotListener,
    private val context: Context? = null,
) {

    private val tag = "HotspotManager"

    /** ④ 用户手动配网密码登记（App 不生成不指定，仅登记，供后续 offer 使用）。 */
    @Volatile
    private var manualPwd: String? = null

    /**
     * 按仲裁结果 [level] 启动热点。
     *
     * - [HotspotStartLevel.L1_ROOT]：B1 真路径（root 探测前置 + 按 `Build.VERSION.SDK_INT` 分流
     *   `cmd wifi` / 反射 `setWifiApEnabled`，见 [startL1Root]）；
     * - [HotspotStartLevel.L1_PRIVATE_API] / [HotspotStartLevel.L2_LOCAL_ONLY]：
     *   真实现为 B 包范围（私有 API / Local-only 按机型实测），本包 stub 降级，一律返回
     *   `HotspotResult(false, error = "本包实现(B包)")`；
     * - [HotspotStartLevel.MANUAL]：触发 [HotspotListener.onManualRequest] 走 UI 手动配网，
     *   返回骨架 `HotspotResult(false, error = "AwaitingManual")`（后续 offer 由 UI 回填密码走 ready）。
     */
    fun start(level: HotspotStartLevel): HotspotResult {
        DiagLogger.log(tag, "start(level=$level) 调用")
        return when (level) {
            // ① root 真热点（B1）：root 探测前置 + 按版本分流
            HotspotStartLevel.L1_ROOT -> startL1Root()

            // ②③ 真实现与私有 API / Local-only 按机型实测为 B 包范围，本包一律降级
            HotspotStartLevel.L1_PRIVATE_API, HotspotStartLevel.L2_LOCAL_ONLY ->
                HotspotResult(success = false, error = "本包实现(B包)")

            // ④ 手动路径：请求 UI 引导用户手动配网；密码回填后走 onHotspotReady
            HotspotStartLevel.MANUAL -> {
                DiagLogger.log(tag, "MANUAL：请求 UI 手动配网")
                listener.onManualRequest()
                HotspotResult(success = false, error = "AwaitingManual")
            }
        }
    }

    /**
     * 登记 ④ 用户自定义密码（App 不生成不指定，仅登记，供后续 offer 使用）。
     *
     * 仅记录登记动作与长度，不回显明文密码，避免敏感信息进入日志。
     */
    fun setPassword(pwd: String) {
        manualPwd = pwd
        DiagLogger.log(tag, "已登记用户自定义密码，长度=${pwd.length}")
    }

    /**
     * ③ L2 本地热点（Local-only，无密码局域网）可用性：
     * Android 8-9（`sdkInt in 26..28`）或 13+（`sdkInt >= 33`）可用；
     * 10-12 为盲区禁用（与 [Arbiter] 的 `localOnlyAvailable` 判定一致）。
     */
    fun isLevel2Available(sdkInt: Int): Boolean = sdkInt in 26..28 || sdkInt >= 33

    /**
     * ① root 通道可用性：复用 [ble.HandshakeProtocol] 内 [RootDetector] 的能力
     * （应用启动时后台探测 `su -c id` 校验 uid=0 并缓存结果，探测失败/未授权一律 false）。
     */
    fun isRootAvailable(): Boolean = RootDetector.isRoot()

    // ================= ① L1_ROOT 真路径（B1） =================

    /**
     * ① root 真热点（B1）：
     * 1) 前置：[RootDetector.isRoot] 为 false → 如实返回失败（交状态机试下一级 ②）；
     * 2) root 为 true 时按 `Build.VERSION.SDK_INT` 分流：
     *    - `sdk >= 29`：root shell `cmd wifi`（主目标 12S root）；
     *    - `sdk in 26..28`：反射 `WifiManager.setWifiApEnabled`（荣耀8 兜底路径）；
     * 3) 成功返回 `HotspotResult(success=true, ssid, pwd, ip=热点本机 IPv4)`；
     * 4) 失败如实返回具体原因（权限/命令不存在/超时/校验不过），不吞异常。
     */
    private fun startL1Root(): HotspotResult {
        // 前置：root 不可用 → 返回失败，交状态机降级试下一级 ②（L1_PRIVATE_API）
        if (!RootDetector.isRoot()) {
            DiagLogger.log(
                tag,
                "L1_ROOT 前置失败：RootDetector.isRoot()=false（无 su / 探测未授权），返回失败降级下一级",
            )
            return HotspotResult(success = false, error = "root 不可用（RootDetector.isRoot()=false），降级")
        }

        val ssid = generateSsid()
        val pwd = generatePassword()
        DiagLogger.log(tag, "L1_ROOT：sdk=${Build.VERSION.SDK_INT} ssid=$ssid pwdLen=${pwd.length}（密码不回显）")

        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startSoftApViaCmdWifi(ssid, pwd)
            } else {
                startSoftApViaReflection(ssid, pwd)
            }
        } catch (e: Exception) {
            // 不吞异常：记录 + 如实透传原因
            DiagLogger.log(tag, "L1_ROOT 启动异常（不吞）: $e")
            HotspotResult(
                success = false,
                ssid = ssid,
                pwd = pwd,
                error = "root 热点启动异常: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    /**
     * sdk >= 29（11+，主目标 12S root）：root shell `cmd wifi` 依次
     * 停旧热点（best-effort）→ 兼容 try 设配置（SSID/密码）→ 启动 → 延时 → `cmd wifi status` 校验。
     *
     * 配置命令按 `cmd wifi help` 实际子命令做兼容 try（先 `set-softap config`/`set`，再
     * `set-softap-config` 明文/hex）；启动命令先 `start-softap`、失败再 `set-softap enabled`。
     * 全程有 [CMD_WIFI_BUDGET_MS] 总预算护栏，保证不超状态机 15s 步骤超时窗口。
     */
    private fun startSoftApViaCmdWifi(ssid: String, pwd: String): HotspotResult {
        val budget = System.currentTimeMillis() + CMD_WIFI_BUDGET_MS

        // 0) 诊断：cmd wifi help（核对实际子命令；仅记录，不参与判定）
        val help = runRoot("cmd wifi help")
        DiagLogger.log(tag, "cmd wifi help exit=${help.exitCode} 片段=${snippet(help.output)}")

        // 1) 先停旧热点（best-effort 保证重配生效；仅启动前瞬态，收尾 stop 属 B4）
        val stop = runRoot("cmd wifi set-softap disabled")
        DiagLogger.log(tag, "cmd wifi set-softap disabled exit=${stop.exitCode}")

        // 2) 设置配置：按实际子命令兼容 try
        var lastConfigError: String? = null
        var configOk = false
        for ((cmd, label) in configCmds(ssid, pwd)) {
            if (System.currentTimeMillis() > budget) {
                lastConfigError = "总预算 ${CMD_WIFI_BUDGET_MS}ms 用尽（未完成配置设置，候选=$label）"
                break
            }
            val r = runRoot(cmd)
            if (r.clean) {
                configOk = true
                DiagLogger.log(tag, "热点配置命令成功（$label）exit=${r.exitCode}（不回显输出防密码泄露）")
                break
            }
            lastConfigError = "$label exit=${r.exitCode} out=${snippet(r.output)}"
            DiagLogger.log(tag, "热点配置命令失败（$label）exit=${r.exitCode}")
        }
        if (!configOk) {
            DiagLogger.log(tag, "L1_ROOT 失败：cmd wifi 设置热点配置全部尝试失败：$lastConfigError")
            return HotspotResult(false, ssid, pwd, error = "cmd wifi 设置热点配置失败：$lastConfigError")
        }

        // 3) 启动：先 start-softap，失败再 set-softap enabled
        var lastStartError: String? = null
        var startOk = false
        for ((cmd, label) in START_CMD_TEMPLATES) {
            if (System.currentTimeMillis() > budget) {
                lastStartError = "总预算 ${CMD_WIFI_BUDGET_MS}ms 用尽（未完成启动，候选=$label）"
                break
            }
            val r = runRoot(cmd)
            if (r.clean) {
                startOk = true
                DiagLogger.log(tag, "热点启动命令成功（$label）exit=${r.exitCode}")
                break
            }
            lastStartError = "$label exit=${r.exitCode} out=${snippet(r.output)}"
            DiagLogger.log(tag, "热点启动命令失败（$label）exit=${r.exitCode}")
        }
        if (!startOk) {
            DiagLogger.log(tag, "L1_ROOT 失败：cmd wifi 启动热点全部尝试失败：$lastStartError")
            return HotspotResult(false, ssid, pwd, error = "cmd wifi 启动热点失败：$lastStartError")
        }

        // 4) 延时等待软热点拉起（任务约定 500–800ms）
        Thread.sleep(START_SETTLE_MS)

        // 5) cmd wifi status 校验 started
        val status = runRoot("cmd wifi status")
        val statusOk = isSoftApEnabled(status.output)
        DiagLogger.log(
            tag,
            "cmd wifi status exit=${status.exitCode} statusOk=$statusOk 输出片段=${snippet(status.output)}",
        )

        // 6) 取热点本机 IP（多方式，见 collectHotspotIp；失败 ip 可空占位 ""）
        val ipInfo = collectHotspotIp()
        DiagLogger.log(tag, "热点 IP 采集：ip=${ipInfo.ip.ifEmpty { "<空>" }} strong=${ipInfo.strong}")

        // 校验通过判定：status 显示 started，或 AP 接口/热点网段 IP 强证据
        if (statusOk || ipInfo.strong) {
            return HotspotResult(success = true, ssid = ssid, pwd = pwd, ip = ipInfo.ip.ifEmpty { "" })
        }
        val detail =
            "status 未显示 started（exit=${status.exitCode} out=${snippet(status.output)}）；IP 采集=${ipInfo.ip.ifEmpty { "<空>" }}"
        DiagLogger.log(tag, "L1_ROOT 失败：启动命令成功但校验未通过：$detail")
        return HotspotResult(false, ssid, pwd, error = "热点启动后校验失败：$detail")
    }

    /**
     * sdk 26–28（8–10，荣耀8 兜底路径）：反射 `WifiManager.setWifiApEnabled(构造的
     * WifiConfiguration(SSID, 密码), true)`（hidden API 反射；SSID 加引号 "Bluelink-XXXX"、
     * preSharedKey）→ 轮询 `isWifiApEnabled`。
     *
     * 反射抛异常/返回 false 时，经 root 改写 `/data/misc/wifi/softap.conf`（SSID+明文密码）兜底后
     * 重试一次；仍失败如实返回。26–28 路径需要注入 [context] 取 WifiManager。
     */
    @Suppress("DEPRECATION") // WifiConfiguration / KeyMgmt 为 26–28 兜底路径唯一可用通道（已被弃用但仍在公开 SDK）
    private fun startSoftApViaReflection(ssid: String, pwd: String): HotspotResult {
        val ctx = context
        if (ctx == null) {
            DiagLogger.log(tag, "L1_ROOT(26–28) 失败：未注入 Context，无法取 WifiManager（待接线方注入 applicationContext）")
            return HotspotResult(
                false,
                ssid,
                pwd,
                error = "26–28 反射路径需要 Context（HotspotManager 构造注入），当前为 null",
            )
        }
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        if (wm == null) {
            DiagLogger.log(tag, "L1_ROOT(26–28) 失败：WifiManager 不可用")
            return HotspotResult(false, ssid, pwd, error = "WifiManager 不可用")
        }

        val config = WifiConfiguration()
        config.SSID = "\"$ssid\"" // SSID 需加引号（WifiConfiguration 约定）
        config.preSharedKey = "\"$pwd\""
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)

        var enabled: Boolean
        try {
            enabled = invokeSetWifiApEnabled(wm, config, true)
        } catch (e: Exception) {
            // 反射异常 → root 改 softap.conf 兜底后重试一次
            DiagLogger.log(tag, "反射 setWifiApEnabled 异常（先尝试 softap.conf 兜底）: $e")
            writeSoftApConf(ssid, pwd)
            enabled = try {
                invokeSetWifiApEnabled(wm, config, true)
            } catch (e2: Exception) {
                DiagLogger.log(tag, "softap.conf 兜底后重试仍异常（不吞，如实透传）: $e2")
                return HotspotResult(
                    false,
                    ssid,
                    pwd,
                    error = "反射 setWifiApEnabled 失败: ${e2.message ?: e2.javaClass.simpleName}",
                )
            }
        }
        if (!enabled) {
            // 返回 false → 同样 softap.conf 兜底重试一次
            DiagLogger.log(tag, "setWifiApEnabled 返回 false（先尝试 softap.conf 兜底）")
            writeSoftApConf(ssid, pwd)
            enabled = try {
                invokeSetWifiApEnabled(wm, config, true)
            } catch (e: Exception) {
                DiagLogger.log(tag, "兜底重试异常（按 false 处理）: $e")
                false
            }
        }
        if (!enabled) {
            DiagLogger.log(tag, "L1_ROOT(26–28) 失败：setWifiApEnabled=true 返回 false（含 softap.conf 兜底后）")
            return HotspotResult(false, ssid, pwd, error = "反射 setWifiApEnabled 返回 false（含 softap.conf 兜底）")
        }

        // 轮询 isWifiApEnabled（≤ AP_ENABLED_POLL_TIMEOUT_MS）
        val deadline = System.currentTimeMillis() + AP_ENABLED_POLL_TIMEOUT_MS
        var apOn = try {
            invokeIsWifiApEnabled(wm)
        } catch (e: Exception) {
            DiagLogger.log(tag, "isWifiApEnabled 反射异常（按 false 处理）: $e")
            false
        }
        while (!apOn && System.currentTimeMillis() < deadline) {
            Thread.sleep(AP_ENABLED_POLL_INTERVAL_MS)
            apOn = try {
                invokeIsWifiApEnabled(wm)
            } catch (e: Exception) {
                false
            }
        }
        DiagLogger.log(tag, "isWifiApEnabled 轮询结果=$apOn")
        if (!apOn) {
            return HotspotResult(
                false,
                ssid,
                pwd,
                error = "isWifiApEnabled 在 ${AP_ENABLED_POLL_TIMEOUT_MS / 1000}s 内未开启",
            )
        }

        // 取 IP（best-effort，失败可空占位 ""）
        val ipInfo = collectHotspotIp()
        DiagLogger.log(tag, "L1_ROOT(26–28) 成功：ip=${ipInfo.ip.ifEmpty { "<空>" }}")
        return HotspotResult(success = true, ssid = ssid, pwd = pwd, ip = ipInfo.ip.ifEmpty { "" })
    }

    // ================= root shell / 反射 / IP 采集工具（B1 单文件内聚） =================

    /** root shell 执行结果（stderr 已合并入 [output]）。 */
    private data class ShellResult(val exitCode: Int, val output: String) {
        /** 退出码 0 且无异常标记 → 命令可认为成功。 */
        val clean: Boolean
            get() {
                if (exitCode != 0) return false
                val t = output.lowercase(Locale.US)
                return !t.contains("unknown command") && !t.contains("exception") &&
                    !t.contains("error") && !t.contains("failed") && !t.contains("illegal")
            }
    }

    /** 热点本机 IP 采集结果：[strong] 表示有 AP 接口/热点网段强证据（可作启动成功佐证）。 */
    private data class HotspotIpInfo(val ip: String, val strong: Boolean)

    /**
     * 执行 `su -c <cmd>`（root），带超时：关闭 stdin 防挂起；超时 destroyForcibly 不残留；
     * 异常如实记录并返回标记退出码（调用方按失败处理）。
     */
    private fun runRoot(cmd: String, timeoutMs: Long = SHELL_TIMEOUT_MS): ShellResult = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        p.outputStream.close() // 关闭 stdin，避免进程挂起等待输入
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) {
            p.destroyForcibly()
            ShellResult(-1, "[su 命令超时 ${timeoutMs}ms]")
        } else {
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            ShellResult(p.exitValue(), out)
        }
    } catch (e: Exception) {
        ShellResult(-2, "[su 执行异常 ${e.message}]")
    }

    /** 26–28 兜底：root 改写 /data/misc/wifi/softap.conf（SSID+明文密码），经 stdin 写入避免 shell 引号转义问题。 */
    private fun writeSoftApConf(ssid: String, pwd: String) {
        // hostapd 风格最小配置（EMUI 8.0 由 root 可写；具体字段按机型可再调）
        val content = "ssid=$ssid\nwpa=2\nwpa_passphrase=$pwd\nwpa_key_mgmt=WPA-PSK\nrsn_pairwise=CCMP\n"
        val result = try {
            val p = ProcessBuilder("su", "-c", "cat > $SOFTAP_CONF_PATH").redirectErrorStream(true).start()
            try {
                p.outputStream.write(content.toByteArray(Charsets.UTF_8))
            } finally {
                p.outputStream.close() // EOF → cat 结束
            }
            val done = p.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroyForcibly()
                ShellResult(-1, "[写 softap.conf 超时]")
            } else {
                ShellResult(p.exitValue(), p.inputStream.readBytes().toString(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            ShellResult(-2, "[写 softap.conf 异常 ${e.message}]")
        }
        DiagLogger.log(tag, "写入 $SOFTAP_CONF_PATH exit=${result.exitCode}（内容不回显防密码泄露）")
    }

    /** hidden API 反射：WifiManager.setWifiApEnabled(WifiConfiguration, boolean)。 */
    @Suppress("DEPRECATION")
    private fun invokeSetWifiApEnabled(wm: WifiManager, config: WifiConfiguration, enabled: Boolean): Boolean {
        val m = WifiManager::class.java.getMethod(
            "setWifiApEnabled",
            WifiConfiguration::class.java,
            java.lang.Boolean.TYPE, // boolean 基本类型 Class（Kotlin 中 javaPrimitiveType 为可空，这里用 Java 静态常量最稳）
        )
        m.isAccessible = true
        return m.invoke(wm, config, enabled) as Boolean
    }

    /** hidden API 反射：WifiManager.isWifiApEnabled()。 */
    private fun invokeIsWifiApEnabled(wm: WifiManager): Boolean {
        val m = WifiManager::class.java.getMethod("isWifiApEnabled")
        m.isAccessible = true
        return m.invoke(wm) as Boolean
    }

    /**
     * 热点本机 IP 采集（至少 2 种方式）：
     * 1) `ip -4 addr show ap0/ap1/softap0`（root；热点拉起后通常存在 AP 接口，命中即强证据）；
     * 2) `ip -4 addr` 全量（root）→ 按接口名/网段打分（ap 系接口、192.168.43.x 热点默认网段优先）；
     * 3) `ifconfig`（root，部分精简 ROM 无 ip 命令）；
     * 4) Java NetworkInterface 枚举（免 root 兜底）。
     * 全部失败 → 空串 ""（任务允许占位）。
     */
    private fun collectHotspotIp(): HotspotIpInfo {
        // 方式 1：AP 系接口定向查询（命中即强证据）
        for (iface in AP_IFACES) {
            val r = runRoot("ip -4 addr show $iface")
            val hit = parseIpv4(r.output).firstOrNull()
            if (hit != null) {
                return HotspotIpInfo(hit.second, strong = true)
            }
        }
        // 方式 2：ip -4 addr 全量
        val full = pickBestIpv4(parseIpv4(runRoot("ip -4 addr").output))
        if (full != null) return full
        // 方式 3：ifconfig 兜底
        val ifc = pickBestIpv4(parseIpv4(runRoot("ifconfig").output))
        if (ifc != null) return ifc
        // 方式 4：Java NetworkInterface 枚举（免 root）
        val java = pickBestIpv4(javaIpv4s())
        if (java != null) return java
        return HotspotIpInfo("", strong = false)
    }

    /** 按接口名/网段打分选最优 IPv4；无候选返回 null。 */
    private fun pickBestIpv4(candidates: List<Pair<String, String>>): HotspotIpInfo? {
        if (candidates.isEmpty()) return null
        val best = candidates.maxByOrNull { scoreIfaceIp(it.first, it.second) } ?: return null
        return HotspotIpInfo(best.second, strong = isStrongEvidence(best.first, best.second))
    }

    /** 接口名/网段打分：ap 系接口 +100（热点接口强信号）、wlan +10、192.168.43.x +50、192.168.x +20、10./172. +5。 */
    private fun scoreIfaceIp(iface: String, ip: String): Int {
        val n = iface.lowercase(Locale.US)
        var s = 0
        if (n.startsWith("ap") || n.contains("softap")) s += 100
        if (n.contains("wlan")) s += 10
        if (ip.startsWith("192.168.43.")) s += 50 // Android 默认热点网段
        if (ip.startsWith("192.168.")) s += 20
        if (ip.startsWith("10.") || ip.startsWith("172.")) s += 5
        return s
    }

    /** 强证据：ap 系接口，或 192.168.43.x 默认热点网段。 */
    private fun isStrongEvidence(iface: String, ip: String): Boolean {
        val n = iface.lowercase(Locale.US)
        return n.startsWith("ap") || n.contains("softap") || ip.startsWith("192.168.43.")
    }

    /** 解析 `ip addr` / `ifconfig` 输出 → (接口名, IPv4) 列表（排除回环/链路本地）。 */
    private fun parseIpv4(output: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var current = ""
        for (raw in output.lines()) {
            val t = raw.trim()
            when {
                // ifconfig: "inet addr:192.168.43.1  Bcast:...  Mask:..."
                t.startsWith("inet addr:") -> {
                    val ip = IPV4_REGEX.find(t)?.value ?: continue
                    if (ip == "127.0.0.1" || ip.startsWith("169.254.")) continue
                    result.add(current to ip)
                }
                // ip: "inet 192.168.43.1/24 brd ... scope global ap0"
                t.startsWith("inet ") -> {
                    val ip = IPV4_REGEX.find(t)?.value ?: continue
                    if (ip == "127.0.0.1" || ip.startsWith("169.254.")) continue
                    result.add(current to ip)
                }
                // ifconfig 首行: "ap0 Link encap:Ethernet  HWaddr ..."
                t.contains("Link encap:") -> current = t.substringBefore(" ").trimEnd(':')
                // ip 首行: "3: ap0: <BROADCAST,...> mtu ..." / "3: ap0@wlan0: ..."
                IFACE_LINE_REGEX.containsMatchIn(t) ->
                    current = t.substringAfter(": ").substringBefore(":").substringBefore("@").trim()
            }
        }
        return result
    }

    /** Java NetworkInterface 枚举（免 root 兜底方式 4）。 */
    private fun javaIpv4s(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                ni.interfaceAddresses.forEach { ia ->
                    val a = ia.address
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        out.add(ni.name to a.hostAddress)
                    }
                }
            }
        } catch (e: Exception) {
            DiagLogger.log(tag, "Java 枚举网络接口失败: $e")
        }
        return out
    }

    /** 解析 `cmd wifi status` 输出：softap/hotspot 相关行命中 started 类标记（enabled/started/running/up…）。 */
    private fun isSoftApEnabled(statusOutput: String): Boolean {
        for (raw in statusOutput.lines()) {
            val t = raw.lowercase(Locale.US)
            if (!t.contains("softap") && !t.contains("soft ap") && !t.contains("hotspot")) continue
            // 明确关闭标记的行跳过（避免 "Wifi is enabled, SoftAp is disabled" 误判）
            if (t.contains("disab") || t.contains("stopped") || t.contains("inactiv") || t.contains(" off")) continue
            if (t.contains("enabl") || t.contains("started") || t.contains("running") || t.contains("activ") || t.contains(" up")) {
                return true
            }
        }
        return false
    }

    /** 日志用输出片段（单行化 + 截断，避免刷屏/泄露长输出）。 */
    private fun snippet(s: String, max: Int = 160): String = s.replace('\n', ' ').trim().take(max)

    /** SSID = "Bluelink-" + 4 位随机数字（如 Bluelink-0831）。 */
    private fun generateSsid(): String {
        val n = kotlin.random.Random.nextInt(0, 10_000)
        return SSID_PREFIX + String.format(Locale.US, "%0${SSID_SUFFIX_LEN}d", n)
    }

    /** 随机密码：8 位字母数字（去易混淆字符集）。 */
    private fun generatePassword(len: Int = PWD_LEN): String {
        val rnd = SecureRandom()
        val sb = StringBuilder(len)
        repeat(len) { sb.append(PWD_CHARSET[rnd.nextInt(PWD_CHARSET.length)]) }
        return sb.toString()
    }

    /** `cmd wifi set-softap config` 兼容候选（ssid/pwd 为生成的纯字母数字，无 shell 注入面）。 */
    private fun configCmds(ssid: String, pwd: String): List<Pair<String, String>> = listOf(
        "cmd wifi set-softap config \"$ssid\" \"$pwd\"" to "set-softap config",
        "cmd wifi set-softap set \"$ssid\" \"$pwd\"" to "set-softap set",
        "cmd wifi set-softap-config \"$ssid\" \"$pwd\"" to "set-softap-config",
        "cmd wifi set-softap-config ${toHex(ssid)} ${toHex(pwd)}" to "set-softap-config(hex)",
    )

    /** UTF-8 十六进制（AOSP 13+ `set-softap-config` 需 hex 编码参数的兼容候选）。 */
    private fun toHex(s: String): String {
        val sb = StringBuilder(s.length * 2)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    companion object {
        /** SSID 前缀。 */
        private const val SSID_PREFIX = "Bluelink-"

        /** SSID 随机后缀位数（4 位随机数字）。 */
        private const val SSID_SUFFIX_LEN = 4

        /** 密码长度（8 位）。 */
        private const val PWD_LEN = 8

        /** 密码字符集（去 0/O/1/l/I 等易混淆字符）。 */
        private const val PWD_CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

        /** 单条 root 命令超时。 */
        private const val SHELL_TIMEOUT_MS = 2_000L

        /** cmd wifi 路径总预算（含各候选 try，护栏保证不超状态机 15s 步骤超时窗口）。 */
        private const val CMD_WIFI_BUDGET_MS = 10_000L

        /** 启动后等待软热点拉起的延时（任务约定 500–800ms）。 */
        private const val START_SETTLE_MS = 600L

        /** 26–28 轮询 isWifiApEnabled 的最长等待。 */
        private const val AP_ENABLED_POLL_TIMEOUT_MS = 5_000L

        /** 26–28 轮询 isWifiApEnabled 的间隔。 */
        private const val AP_ENABLED_POLL_INTERVAL_MS = 400L

        /** 8.0 兜底 softap.conf 路径（root 可写）。 */
        private const val SOFTAP_CONF_PATH = "/data/misc/wifi/softap.conf"

        /** IP 采集方式 1：优先定向查询的 AP 系接口。 */
        private val AP_IFACES = listOf("ap0", "ap1", "softap0")

        /** `cmd wifi status` / `ip addr` 输出解析用。 */
        private val IPV4_REGEX = Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b")
        private val IFACE_LINE_REGEX = Regex("^\\d+: [a-zA-Z0-9@._-]+")

        /** 启动命令候选（先 start-softap，再 set-softap enabled 兜底）。 */
        private val START_CMD_TEMPLATES = listOf(
            "cmd wifi start-softap" to "start-softap",
            "cmd wifi set-softap enabled" to "set-softap enabled",
        )
    }
}
