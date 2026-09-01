package com.zglinus.bluelink.networking

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.zglinus.bluelink.diag.DiagLogger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * root 真热点「配置 × 启动」全矩阵穷举引擎（B1 穷举版；[HotspotManager] 的瘦身执行器）。
 *
 * 不再按 `sdkInt in 26..28 / >=29` 硬分版本路径（旧 L1_ROOT 29+ cmd wifi 路径与 26–28 反射路径
 * 统一并入本矩阵）；所有已知可用的方式全部写上，运行时逐个探测-尝试，直到任一组合
 * 「配置成功 → 启动命令执行 → 延时 600ms → 校验矩阵任一判定 started」即整体成功。
 *
 * - 配置矩阵（写 SSID=Bluelink-XXXX / 随机 8 位密码，任一成功即进入启动矩阵）：
 *   A1 `/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml`（Android 11+ apex；root 读原文件 →
 *      含 WifiApConfig 段则替换 ssid/明文密码 → 回写；不含则最小注入热点配置段；备份 `.bluelink.bak`）
 *   A2 `/data/misc/wifi/WifiConfigStore.xml`（传统路径，同上逻辑）
 *   A3 `/data/misc/wifi/softap.conf`（8.0 实测；替换 ssid/wpa_passphrase 两行直写，无内容则最小块）
 *   A4 `cmd wifi set-softap` 系列（仅当 `cmd wifi help` 含 set-softap 才试；config / set /
 *      set-softap-config 明文与 hex 共 4 变体）
 *   A5 反射 `WifiManager.setSoftApConfiguration(WifiConfiguration)`（hidden；SSID 加引号 + preSharedKey + WPA2）
 * - 启动矩阵（任一成功即热点就绪）：
 *   B1 `cmd wifi start-softap`（help 含才试）
 *   B2 `cmd wifi set-softap enabled`（help 含才试）
 *   B3 反射 `WifiManager.setWifiApEnabled(config, true)`（全版本都试；java.lang.Boolean.TYPE 精匹配）
 *   B4 LocalSocket 连 `@android:wpa_wlan0` 发 ENABLE（8.0 实测路径；连接失败跳过）
 *   B5 `service call wifi`（最后兜底：先 `service list` 确认 wifi 服务存在，再试常见方法代号；
 *      脆弱，失败属预期）
 * - 校验矩阵（每个启动尝试后延时 600ms 执行；任一判定 started 即成功）：
 *   `cmd wifi status` / `dumpsys wifi | grep -i softap` / `ip link` 找 ap 接口 /
 *   `WifiManager.isWifiApEnabled()` 反射。
 *   细化：`cmd wifi status` 显式给出与本机不同的 SSID 时判定「旧配置残留」，该组合判失败
 *   （避免返回成功但对端拿到错误凭据）。
 *
 * 工程质量：总体预算 [OVERALL_BUDGET_MS] 护栏（≤10s，不超状态机 15s 步骤窗口）；单条 root 命令
 * [SHELL_TIMEOUT_MS] 超时 destroyForcibly 不残留；root shell 统一封装（su -c / ProcessBuilder /
 * redirectErrorStream / stdin 关闭）；全程 [DiagLogger]（成功路径名「成功于: B3 反射 setWifiApEnabled」
 * 便于真机对账）；失败聚合每条原因（策略名 + exit + 输出摘要，密码一律脱敏不出现）。
 * 版本仅作探测顺序偏好：8.0（sdk 26–28）将实测路径 A3(softap.conf) 前置。
 *
 * @see HotspotManager 状态机/引擎不改动，仅替换 ① L1_ROOT 的实现载体。
 */
internal object RootSoftAp {

    private const val TAG = "RootSoftAp"

    // ---------- 预算 / 延时 ----------

    /** 总体预算护栏（≤10s；不超状态机 15s 步骤超时窗口）。 */
    private const val OVERALL_BUDGET_MS = 10_000L

    /** 单条 root 命令超时（超时 destroyForcibly）。 */
    private const val SHELL_TIMEOUT_MS = 2_000L

    /** 启动尝试后等待软热点拉起的延时（任务约定 500–800ms）。 */
    private const val START_SETTLE_MS = 600L

    // ---------- 路径 / 常量 ----------

    /** Android 11+ apex 版 WifiConfigStore。 */
    private const val APEX_STORE_PATH = "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml"

    /** 传统 WifiConfigStore。 */
    private const val LEGACY_STORE_PATH = "/data/misc/wifi/WifiConfigStore.xml"

    /** 8.0 hostapd 风格 softap.conf。 */
    private const val SOFTAP_CONF_PATH = "/data/misc/wifi/softap.conf"

    /** 回写前备份后缀（仅首次备份，避免备份到自改版本）。 */
    private const val BACKUP_SUFFIX = ".bluelink.bak"

    /** wpa_supplicant 软热点控制抽象套接字（8.0 实测；wpa_cli 记法为 @android:wpa_wlan0，@ 前缀不入名）。 */
    private const val WPA_CTRL_IFACE = "android:wpa_wlan0"

    /** `service call wifi` 常见 setWifiApEnabled 方法代号窗口（跨版本脆弱，失败属预期）。 */
    private val SERVICE_CALL_CODES = listOf(23, 24, 25, 26, 27, 28, 29, 30)

    /** IP 采集：优先定向查询的 AP 系接口。 */
    private val AP_IFACES = listOf("ap0", "ap1", "softap0")

    private val IPV4_REGEX = Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b")
    private val IFACE_LINE_REGEX = Regex("^\\d+: [a-zA-Z0-9@._-]+")

    // ---------- 结果载体 ----------

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

    /** 校验矩阵结果：[started] 任一信号判定 started 即 true；[source] 记录命中的信号名。 */
    private data class VerifyOutcome(val started: Boolean, val source: String)

    // ================= 入口：配置×启动双矩阵 =================

    /**
     * root 真热点全矩阵穷举入口（同步执行，总预算护栏内）。
     *
     * @param ssid 目标 SSID（Bluelink-XXXX）。
     * @param pwd 随机 8 位密码（仅用于脱敏与结果回填，全程不回显）。
     * @param context 反射路径取 WifiManager 用；null 时经 `ActivityThread.currentApplication()` 反射兜底。
     * @return 成功：`HotspotResult(success=true, ssid, pwd, ip=热点本机 IPv4)`；
     *   全部失败：`HotspotResult(success=false, ssid, pwd, error=聚合原因串)`（状态机照旧降级 ②）。
     */
    fun start(ssid: String, pwd: String, context: Context?): HotspotResult {
        val deadline = System.currentTimeMillis() + OVERALL_BUDGET_MS
        val reasons = mutableListOf<String>()

        // 探测：cmd wifi help（A4/B1/B2 可用性门槛，缓存一次）
        val help = probeCmdWifiHelp(deadline)
        // WifiManager（A5/B3/校验矩阵共用；Context 未注入时经 ActivityThread 兜底）
        val wm = resolveWifiManager(context)
        DiagLogger.log(
            TAG,
            "矩阵启动：sdk=${Build.VERSION.SDK_INT} help=${help != null} wifiManager=${wm != null} " +
                "ssid=$ssid pwdLen=${pwd.length}（矩阵：配置 5 × 启动 5 + 校验 4）",
        )

        // 配置矩阵（A1..A5）。版本仅作探测顺序偏好：8.0（sdk 26–28）实测路径 A3 softap.conf 前置。
        val configStrategies = if (Build.VERSION.SDK_INT in 26..28) {
            listOf(
                "A3 softap.conf" to { applySoftApConf(ssid, pwd, reasons, deadline) },
                "A2 WifiConfigStore.xml" to {
                    applyWifiConfigStore(ssid, pwd, LEGACY_STORE_PATH, "A2 WifiConfigStore.xml", reasons, deadline)
                },
                "A1 apex WifiConfigStore.xml" to {
                    applyWifiConfigStore(ssid, pwd, APEX_STORE_PATH, "A1 apex WifiConfigStore.xml", reasons, deadline)
                },
                "A4 cmd wifi set-softap" to { applyCmdSetSoftAp(ssid, pwd, help, reasons, deadline) },
                "A5 反射 setSoftApConfiguration" to { applyReflectSetSoftApConfig(ssid, pwd, wm, reasons, deadline) },
            )
        } else {
            listOf(
                "A1 apex WifiConfigStore.xml" to {
                    applyWifiConfigStore(ssid, pwd, APEX_STORE_PATH, "A1 apex WifiConfigStore.xml", reasons, deadline)
                },
                "A2 WifiConfigStore.xml" to {
                    applyWifiConfigStore(ssid, pwd, LEGACY_STORE_PATH, "A2 WifiConfigStore.xml", reasons, deadline)
                },
                "A3 softap.conf" to { applySoftApConf(ssid, pwd, reasons, deadline) },
                "A4 cmd wifi set-softap" to { applyCmdSetSoftAp(ssid, pwd, help, reasons, deadline) },
                "A5 反射 setSoftApConfiguration" to { applyReflectSetSoftApConfig(ssid, pwd, wm, reasons, deadline) },
            )
        }

        // 启动矩阵（B1..B5）
        val startStrategies = listOf(
            "B1 cmd wifi start-softap" to {
                runCmdStart("cmd wifi start-softap", "start-softap", "B1 cmd wifi start-softap", help, reasons, deadline)
            },
            "B2 cmd wifi set-softap enabled" to {
                runCmdStart("cmd wifi set-softap enabled", "set-softap", "B2 cmd wifi set-softap enabled", help, reasons, deadline)
            },
            "B3 反射 setWifiApEnabled" to { startReflectSetWifiAp(ssid, pwd, wm, reasons, deadline) },
            "B4 LocalSocket @android:wpa_wlan0 enable" to { startLocalSocketEnable(reasons, deadline) },
            "B5 service call wifi" to { startServiceCall(reasons, deadline) },
        )

        // ===== 双矩阵穷举（预算护栏内；任一组合成功即返回） =====
        for ((cfgLabel, cfgApply) in configStrategies) {
            if (overBudget(deadline)) {
                reasons += "$cfgLabel 未尝试：总预算用尽"
                continue
            }
            DiagLogger.log(TAG, "配置矩阵 → $cfgLabel")
            val cfgOk = try {
                cfgApply()
            } catch (e: Exception) {
                reasons += "$cfgLabel 异常: ${e.javaClass.simpleName}: ${e.message}"
                false
            }
            if (!cfgOk) continue // 失败原因已记录，试下一配置

            DiagLogger.log(TAG, "配置成功（$cfgLabel），进入启动矩阵")
            for ((startLabel, startTry) in startStrategies) {
                if (overBudget(deadline)) {
                    reasons += "$cfgLabel×$startLabel 未尝试：总预算用尽"
                    continue
                }
                val attempted = try {
                    startTry()
                } catch (e: Exception) {
                    reasons += "$cfgLabel×$startLabel 异常: ${e.javaClass.simpleName}: ${e.message}"
                    false
                }
                if (!attempted) continue // 门槛未过 / 硬失败已记录，试下一启动方式

                // 每个启动尝试后：延时 600ms → 校验矩阵
                sleepSafe(START_SETTLE_MS)
                if (overBudget(deadline)) {
                    reasons += "$cfgLabel×$startLabel 校验跳过：总预算用尽"
                    continue
                }
                val verify = verifyMatrix(ssid, wm)
                if (verify.started) {
                    val ipInfo = collectHotspotIp(deadline)
                    DiagLogger.log(
                        TAG,
                        "成功于: $startLabel（配置: $cfgLabel；校验: ${verify.source}；ip=${ipInfo.ip.ifEmpty { "<空>" }}）",
                    )
                    return HotspotResult(success = true, ssid = ssid, pwd = pwd, ip = ipInfo.ip.ifEmpty { "" })
                }
                reasons += "$cfgLabel×$startLabel 启动已尝试但校验未过（${verify.source}）"
            }
        }

        // ===== 全部失败：聚合每条失败原因（策略名 + exit + 输出摘要，密码已脱敏） =====
        val summary = aggregate(reasons)
        DiagLogger.log(TAG, "L1_ROOT 全矩阵失败（预算 ${OVERALL_BUDGET_MS / 1000}s）：$summary")
        return HotspotResult(success = false, ssid = ssid, pwd = pwd, error = summary)
    }

    // ================= A 配置矩阵 =================

    /**
     * A1/A2：WifiConfigStore.xml（apex / 传统）改写。
     * root 读原文件 → 含 WifiApConfig 段则替换 ssid/明文密码 → 回写；不含则最小注入热点配置段；
     * 备份原文件 `.bluelink.bak`（仅首次，避免备份到自改版本）。
     */
    private fun applyWifiConfigStore(
        ssid: String,
        pwd: String,
        path: String,
        label: String,
        reasons: MutableList<String>,
        deadline: Long,
    ): Boolean {
        if (overBudget(deadline)) {
            reasons += "$label 未尝试：总预算用尽"
            return false
        }
        val read = runRoot("cat $path")
        if (read.exitCode != 0 || read.output.isBlank()) {
            reasons += "$label：文件不存在或不可读(exit=${read.exitCode})"
            return false
        }
        val updated = rewriteWifiConfigStore(read.output, ssid, pwd)
        if (updated == read.output) {
            reasons += "$label：改写后内容未变化（解析失败？）"
            return false
        }
        // 备份（仅首次；test -e 短路避免覆盖原始备份）
        if (!overBudget(deadline)) {
            val bak = runRoot("test -e $path$BACKUP_SUFFIX || cp -f $path $path$BACKUP_SUFFIX")
            DiagLogger.log(TAG, "$label 备份检查/创建 exit=${bak.exitCode}")
        }
        val write = writeViaStdin(path, updated)
        if (write.exitCode != 0) {
            reasons += "$label：回写失败(exit=${write.exitCode} out=${snippet(redact(write.output, pwd))})"
            return false
        }
        DiagLogger.log(TAG, "$label 回写成功（备份 $path$BACKUP_SUFFIX；内容不回显防密码泄露）")
        return true
    }

    /**
     * 改写 WifiConfigStore 内容：含 `<section name="WifiApConfig">` 则替换 SSID / Passphrase /
     * SaePassphrase 为本次凭据；不含则最小注入该段（插到 `</WifiConfigStoreData>` 前，无则文件末尾）。
     */
    private fun rewriteWifiConfigStore(xml: String, ssid: String, pwd: String): String {
        val sectionRegex = Regex("(?is)<section name=\"WifiApConfig\">.*?</section>")
        val section = sectionRegex.find(xml)?.value
        val newSection =
            "    <section name=\"WifiApConfig\">\n" +
                "        <string name=\"SSID\">\"$ssid\"</string>\n" +
                "        <string name=\"Passphrase\">$pwd</string>\n" +
                "        <string name=\"SaePassphrase\">$pwd</string>\n" +
                "        <int name=\"Band\" value=\"0\" />\n" +
                "        <int name=\"Channel\" value=\"0\" />\n" +
                "        <boolean name=\"HiddenSSID\" value=\"false\" />\n" +
                "    </section>"
        if (section == null) {
            val close = Regex("</WifiConfigStoreData>")
            return if (close.containsMatchIn(xml)) {
                close.replaceFirst(xml, "$newSection\n$0")
            } else {
                xml.trimEnd() + "\n" + newSection + "\n"
            }
        }
        var s = section
        s = s.replace(Regex("(?is)(<string name=\"SSID\">).*?(</string>)"), "$1\"$ssid\"$2")
        s = s.replace(Regex("(?is)(<string name=\"Passphrase\">).*?(</string>)"), "$1$pwd$2")
        s = s.replace(Regex("(?is)(<string name=\"SaePassphrase\">).*?(</string>)"), "$1$pwd$2")
        return xml.replace(section, s)
    }

    /**
     * A3：/data/misc/wifi/softap.conf（8.0 实测路径）。
     * 有内容则替换 ssid / wpa_passphrase 两行直写（保留其余行）；无内容则写最小 hostapd 块。
     */
    private fun applySoftApConf(
        ssid: String,
        pwd: String,
        reasons: MutableList<String>,
        deadline: Long,
    ): Boolean {
        if (overBudget(deadline)) {
            reasons += "A3 softap.conf 未尝试：总预算用尽"
            return false
        }
        val read = runRoot("cat $SOFTAP_CONF_PATH")
        val original = if (read.exitCode == 0) read.output else ""
        val content = buildString {
            if (original.isNotBlank()) {
                var replaced = 0
                for (line in original.lines()) {
                    when {
                        line.startsWith("ssid=") -> {
                            append("ssid=$ssid\n")
                            replaced++
                        }
                        line.startsWith("wpa_passphrase=") || line.startsWith("wpa_psk=") -> {
                            append("wpa_passphrase=$pwd\n")
                            replaced++
                        }
                        else -> append(line).append('\n')
                    }
                }
                if (replaced == 0) {
                    // 无 ssid/密码行：补最小两行
                    append("ssid=$ssid\nwpa=2\nwpa_passphrase=$pwd\nwpa_key_mgmt=WPA-PSK\n")
                }
            } else {
                append("ssid=$ssid\nwpa=2\nwpa_passphrase=$pwd\nwpa_key_mgmt=WPA-PSK\nrsn_pairwise=CCMP\n")
            }
        }
        val write = writeViaStdin(SOFTAP_CONF_PATH, content)
        if (write.exitCode != 0) {
            reasons += "A3 softap.conf：写入失败(exit=${write.exitCode} out=${snippet(redact(write.output, pwd))})"
            return false
        }
        DiagLogger.log(TAG, "A3 softap.conf 写入成功（内容不回显防密码泄露）")
        return true
    }

    /**
     * A4：`cmd wifi set-softap` 系列（仅 `cmd wifi help` 含 set-softap 才试）。
     * 4 变体按序：config 明文 / set 明文 / set-softap-config 明文 / set-softap-config hex。
     */
    private fun applyCmdSetSoftAp(
        ssid: String,
        pwd: String,
        help: String?,
        reasons: MutableList<String>,
        deadline: Long,
    ): Boolean {
        if (help == null || !help.contains("set-softap")) {
            reasons += "A4 cmd wifi set-softap 跳过：cmd wifi help 无 set-softap 子命令"
            return false
        }
        val variants = listOf(
            "cmd wifi set-softap config \"$ssid\" \"$pwd\"" to "config 明文",
            "cmd wifi set-softap set \"$ssid\" \"$pwd\"" to "set 明文",
            "cmd wifi set-softap-config \"$ssid\" \"$pwd\"" to "set-softap-config 明文",
            "cmd wifi set-softap-config ${toHex(ssid)} ${toHex(pwd)}" to "set-softap-config hex",
        )
        for ((cmd, vlabel) in variants) {
            if (overBudget(deadline)) {
                reasons += "A4($vlabel) 未尝试：总预算用尽"
                break
            }
            val r = runRoot(cmd)
            if (r.clean) {
                DiagLogger.log(TAG, "A4($vlabel) 成功 exit=${r.exitCode}")
                return true
            }
            reasons += "A4($vlabel) exit=${r.exitCode} out=${snippet(redact(r.output, pwd))}"
            DiagLogger.log(TAG, "A4($vlabel) 失败 exit=${r.exitCode}（输出不回显防密码泄露）")
        }
        return false
    }

    /**
     * A5：反射 `WifiManager.setSoftApConfiguration(WifiConfiguration)`（hidden；
     * SSID 加引号 + preSharedKey + WPA2）。调用成功即视为配置成功，是否生效由启动+校验判定。
     */
    @Suppress("DEPRECATION")
    private fun applyReflectSetSoftApConfig(
        ssid: String,
        pwd: String,
        wm: WifiManager?,
        reasons: MutableList<String>,
        deadline: Long,
    ): Boolean {
        if (wm == null) {
            reasons += "A5 反射 setSoftApConfiguration 跳过：WifiManager 不可用"
            return false
        }
        if (overBudget(deadline)) {
            reasons += "A5 未尝试：总预算用尽"
            return false
        }
        return try {
            val m = WifiManager::class.java.getMethod("setSoftApConfiguration", WifiConfiguration::class.java)
            m.isAccessible = true
            m.invoke(wm, buildWifiConfig(ssid, pwd))
            DiagLogger.log(TAG, "A5 反射 setSoftApConfiguration 调用成功（是否生效由启动+校验判定）")
            true
        } catch (e: Exception) {
            reasons += "A5 反射 setSoftApConfiguration: ${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    // ================= B 启动矩阵 =================

    /**
     * B1/B2：`cmd wifi` 启动命令（help 含对应子命令才试）。门槛通过即执行并视为「已尝试」
     * （exit 非 0 也交由校验矩阵兜底判定，故返回 true）。
     */
    private fun runCmdStart(
        cmd: String,
        marker: String,
        label: String,
        help: String?,
        reasons: MutableList<String>,
        deadline: Long,
    ): Boolean {
        if (help == null || !help.contains(marker)) {
            reasons += "$label 跳过：cmd wifi help 无「$marker」子命令"
            return false
        }
        if (overBudget(deadline)) {
            reasons += "$label 未尝试：总预算用尽"
            return false
        }
        val r = runRoot(cmd)
        DiagLogger.log(TAG, "$label 已执行 exit=${r.exitCode}（输出不回显）")
        if (!r.clean) {
            reasons += "$label exit=${r.exitCode} out=${snippet(r.output)}"
        }
        return true // 已尝试；真实成败交由校验矩阵
    }

    /**
     * B3：反射 `WifiManager.setWifiApEnabled(config, true)`（全版本都试；java.lang.Boolean.TYPE 精匹配）。
     * 返回 true 才视为「已尝试」（交校验判定）；返回 false / 异常按硬失败记录原因。
     */
    @Suppress("DEPRECATION")
    private fun startReflectSetWifiAp(
        ssid: String,
        pwd: String,
        wm: WifiManager?,
        reasons: MutableList<String>,
        deadline: Long,
    ): Boolean {
        if (wm == null) {
            reasons += "B3 反射 setWifiApEnabled 跳过：WifiManager 不可用"
            return false
        }
        if (overBudget(deadline)) {
            reasons += "B3 未尝试：总预算用尽"
            return false
        }
        return try {
            val m = WifiManager::class.java.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                java.lang.Boolean.TYPE, // boolean 基本类型精匹配（Kotlin 中 javaPrimitiveType 可空，Java 静态常量最稳）
            )
            m.isAccessible = true
            val enabled = m.invoke(wm, buildWifiConfig(ssid, pwd), true) as? Boolean ?: false
            DiagLogger.log(TAG, "B3 反射 setWifiApEnabled 返回 enabled=$enabled")
            if (enabled) {
                true
            } else {
                reasons += "B3 反射 setWifiApEnabled 返回 false"
                false
            }
        } catch (e: Exception) {
            reasons += "B3 反射 setWifiApEnabled: ${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    /**
     * B4：LocalSocket 连 `@android:wpa_wlan0`（抽象套接字，8.0 实测路径）发 ENABLE。
     * 连接失败/发送超时（1.5s）跳过；daemon 线程防悬挂不残留。
     */
    private fun startLocalSocketEnable(reasons: MutableList<String>, deadline: Long): Boolean {
        if (overBudget(deadline)) {
            reasons += "B4 LocalSocket @android:wpa_wlan0 未尝试：总预算用尽"
            return false
        }
        val done = AtomicBoolean(false)
        val err = AtomicReference<Exception?>(null)
        val t = Thread {
            var s: LocalSocket? = null
            try {
                s = LocalSocket()
                s!!.connect(LocalSocketAddress(WPA_CTRL_IFACE, LocalSocketAddress.Namespace.ABSTRACT))
                s!!.soTimeout = 1200
                s!!.outputStream.write("ENABLE\n".toByteArray(Charsets.UTF_8))
                s!!.outputStream.flush()
                val reply = try {
                    s!!.inputStream.readBytes().toString(Charsets.UTF_8)
                } catch (e: Exception) {
                    "" // 读回复超时/EOF 均可接受（校验矩阵才是判定）
                }
                DiagLogger.log(TAG, "B4 LocalSocket 发送 ENABLE 完成 reply=${snippet(reply)}")
                done.set(true)
            } catch (e: Exception) {
                err.set(e)
            } finally {
                try {
                    s?.close()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        t.isDaemon = true
        t.start()
        t.join(1500)
        val e = err.get()
        if (e != null) {
            reasons += "B4 LocalSocket @android:wpa_wlan0：${e.javaClass.simpleName}: ${e.message}"
            return false
        }
        if (!done.get()) {
            reasons += "B4 LocalSocket @android:wpa_wlan0：连接/发送超时(1.5s)"
            return false
        }
        return true
    }

    /**
     * B5：`service call wifi`（最后兜底）。
     * 先 `service list` 确认 wifi 服务存在，再按常见方法代号（null 配置 + enabled=true）尝试；
     * 首个返回 `Result: Parcel` 的代号视为「调用进入」并交校验判定。跨版本代号脆弱，失败属预期。
     */
    private fun startServiceCall(reasons: MutableList<String>, deadline: Long): Boolean {
        if (overBudget(deadline)) {
            reasons += "B5 service call wifi 未尝试：总预算用尽"
            return false
        }
        val svc = runRoot("service list | grep -i wifi")
        if (svc.exitCode != 0 || !svc.output.contains("wifi:")) {
            reasons += "B5 service call wifi：service list 无 wifi 服务(exit=${svc.exitCode})"
            return false
        }
        for (code in SERVICE_CALL_CODES) {
            if (overBudget(deadline)) {
                reasons += "B5 code=$code 未尝试：总预算用尽"
                break
            }
            val r = runRoot("service call wifi $code i32 0 i32 1")
            if (r.output.contains("Result: Parcel")) {
                DiagLogger.log(TAG, "B5 service call wifi $code 调用进入（Result: Parcel；脆弱路径，交由校验判定）")
                return true
            }
            reasons += "B5 code=$code exit=${r.exitCode} out=${snippet(redact(r.output, ""))}"
        }
        reasons += "B5 service call wifi：常见代号(${SERVICE_CALL_CODES.joinToString()})均未命中 Parcel 返回"
        return false
    }

    // ================= 校验矩阵 =================

    /**
     * 校验矩阵（任一判定 started 即成功）：
     * 1) `cmd wifi status`（最权威，反映运行中 SoftAp 状态；显式 SSID 与本机不符 → 旧配置残留，判失败）；
     * 2) `dumpsys wifi | grep -i softap`；
     * 3) `ip link` 找 ap 接口；
     * 4) `WifiManager.isWifiApEnabled()` 反射。
     */
    private fun verifyMatrix(ssid: String, wm: WifiManager?): VerifyOutcome {
        // 1) cmd wifi status
        val status = runRoot("cmd wifi status")
        if (isSoftApStarted(status.output)) {
            val shown = extractSsid(status.output)
            return if (shown == null || shown.equals(ssid, ignoreCase = true)) {
                VerifyOutcome(true, "cmd wifi status")
            } else {
                VerifyOutcome(false, "cmd wifi status: AP 已启用但 SSID 不符（残留=$shown）")
            }
        }

        // 2) dumpsys wifi | grep -i softap
        val dump = runRoot("dumpsys wifi | grep -i softap")
        if (isSoftApStarted(dump.output)) {
            return VerifyOutcome(true, "dumpsys wifi softap")
        }

        // 3) ip link 找 ap 接口
        val link = runRoot("ip link")
        if (hasApInterface(link.output)) {
            return VerifyOutcome(true, "ip link ap 接口")
        }

        // 4) WifiManager.isWifiApEnabled 反射
        if (wm != null) {
            try {
                val m = WifiManager::class.java.getMethod("isWifiApEnabled")
                m.isAccessible = true
                if (m.invoke(wm) == true) {
                    return VerifyOutcome(true, "isWifiApEnabled 反射")
                }
            } catch (e: Exception) {
                DiagLogger.log(TAG, "校验: isWifiApEnabled 反射失败: $e")
            }
        }

        return VerifyOutcome(false, "四信号均未判定 started")
    }

    /** 解析 `cmd wifi status` / `dumpsys wifi` 输出：softap/hotspot 相关行命中 started 类标记。 */
    private fun isSoftApStarted(output: String): Boolean {
        val text = output.lowercase(Locale.US)
        if (text.contains("wifi_ap_state_enabled")) return true
        for (raw in output.lines()) {
            val t = raw.lowercase(Locale.US)
            if (!t.contains("softap") && !t.contains("soft ap") && !t.contains("hotspot")) continue
            // 明确关闭标记的行跳过（避免 "SoftAp is disabled" 误判）
            if (t.contains("disab") || t.contains("stopped") || t.contains("inactiv") || t.contains(" off")) continue
            if (t.contains("enabl") || t.contains("started") || t.contains("running") || t.contains("activ") || t.contains(" up")) {
                return true
            }
        }
        return false
    }

    /** 从输出提取显式出现的 SSID（引号/等号/冒号容错；无显式 SSID 返回 null）。 */
    private fun extractSsid(output: String): String? {
        val m = Regex("(?i)ssid\\s*[=:]\\s*[\"']?([^\"'\\s,;<>]+)").find(output) ?: return null
        return m.groupValues[1]
    }

    /** `ip link` 输出中是否存在 ap 系接口（ap0/ap1/softap0…）。 */
    private fun hasApInterface(linkOutput: String): Boolean {
        for (raw in linkOutput.lines()) {
            val t = raw.trim()
            val name = IFACE_LINE_REGEX.find(t)
                ?.value?.substringAfter(": ")?.substringBefore(":")?.substringBefore("@")
                ?: continue
            val n = name.lowercase(Locale.US)
            if (n.startsWith("ap") || n.contains("softap")) return true
        }
        return false
    }

    // ================= 成功收尾：热点本机 IP 采集 =================

    /**
     * 热点本机 IP 采集（保留四级）：
     * 1) `ip -4 addr show ap0/ap1/softap0`（定向，命中即强证据）；
     * 2) `ip -4 addr` 全量打分；
     * 3) `ifconfig` 兜底；
     * 4) Java NetworkInterface 枚举（免 root）。
     * 全部失败 → 空串 ""（一期允许占位）。
     */
    private fun collectHotspotIp(deadline: Long): HotspotIpInfo {
        if (overBudget(deadline)) {
            DiagLogger.log(TAG, "IP 采集跳过：总预算用尽")
            return HotspotIpInfo("", strong = false)
        }
        for (iface in AP_IFACES) {
            val r = runRoot("ip -4 addr show $iface", timeoutMs = 1200)
            val hit = parseIpv4(r.output).firstOrNull()
            if (hit != null) {
                return HotspotIpInfo(hit.second, strong = true)
            }
        }
        if (!overBudget(deadline)) {
            val full = pickBestIpv4(parseIpv4(runRoot("ip -4 addr", timeoutMs = 1200).output))
            if (full != null) return full
        }
        if (!overBudget(deadline)) {
            val ifc = pickBestIpv4(parseIpv4(runRoot("ifconfig", timeoutMs = 1200).output))
            if (ifc != null) return ifc
        }
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

    /** 接口名/网段打分：ap 系接口 +100、wlan +10、192.168.43.x +50、192.168.x +20、10./172. +5。 */
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
            DiagLogger.log(TAG, "Java 枚举网络接口失败: $e")
        }
        return out
    }

    // ================= root shell / 反射 / 工具 =================

    /**
     * 执行 `su -c <cmd>`（root），统一封装：ProcessBuilder + redirectErrorStream（stderr 并入 stdout）；
     * 关闭 stdin 防挂起；超时 destroyForcibly 不残留；异常如实记录并返回标记退出码。
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

    /** 经 stdin 写入文件（`su -c "cat > path"`），避免 shell 引号/转义问题；内容不回显。 */
    private fun writeViaStdin(path: String, content: String): ShellResult = try {
        val p = ProcessBuilder("su", "-c", "cat > $path").redirectErrorStream(true).start()
        try {
            p.outputStream.write(content.toByteArray(Charsets.UTF_8))
        } finally {
            p.outputStream.close() // EOF → cat 结束
        }
        val done = p.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!done) {
            p.destroyForcibly()
            ShellResult(-1, "[写入 $path 超时]")
        } else {
            ShellResult(p.exitValue(), p.inputStream.readBytes().toString(Charsets.UTF_8))
        }
    } catch (e: Exception) {
        ShellResult(-2, "[写入 $path 异常 ${e.message}]")
    }

    /** 构造 WifiConfiguration（SSID 加引号 + preSharedKey + WPA2），A5/B3 共用。 */
    @Suppress("DEPRECATION")
    private fun buildWifiConfig(ssid: String, pwd: String): WifiConfiguration {
        val c = WifiConfiguration()
        c.SSID = "\"$ssid\"" // SSID 需加引号（WifiConfiguration 约定）
        c.preSharedKey = "\"$pwd\""
        c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        c.allowedProtocols.set(WifiConfiguration.Protocol.RSN)
        c.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
        c.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
        c.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
        return c
    }

    /** `cmd wifi help` 探测（A4/B1/B2 门槛；exit=0 才缓存输出，否则 null）。 */
    private fun probeCmdWifiHelp(deadline: Long): String? {
        if (overBudget(deadline)) return null
        val r = runRoot("cmd wifi help")
        DiagLogger.log(TAG, "cmd wifi help exit=${r.exitCode} 片段=${snippet(r.output)}")
        return if (r.exitCode == 0) r.output else null
    }

    /** 取 WifiManager：优先注入的 Context；null 时经 ActivityThread.currentApplication() 反射兜底。 */
    private fun resolveWifiManager(context: Context?): WifiManager? {
        context?.let {
            return it.applicationContext.getSystemService(WifiManager::class.java)
        }
        // Context 未注入：ActivityThread.currentApplication() 反射（P+ 可能被 hidden API 拦截，失败如实记录）
        return try {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
            if (app == null) {
                DiagLogger.log(TAG, "ActivityThread.currentApplication()=null，无 WifiManager")
                null
            } else {
                DiagLogger.log(TAG, "经 ActivityThread.currentApplication() 取得 WifiManager（Context 未注入兜底）")
                app.applicationContext.getSystemService(WifiManager::class.java)
            }
        } catch (e: Exception) {
            DiagLogger.log(TAG, "ActivityThread 反射取 WifiManager 失败（P+ hidden API 可能拦截）: $e")
            null
        }
    }

    /** 失败聚合：预算信息 + 每条原因（去重、单条截断 120 字符，密码已脱敏）。 */
    private fun aggregate(reasons: List<String>): String {
        val head = "root 热点全矩阵失败（预算 ${OVERALL_BUDGET_MS / 1000}s，${reasons.size} 条）"
        val body = reasons.distinct().joinToString("；") { it.take(120) }
        return if (body.isBlank()) "$head：无失败记录（全部未尝试？）" else "$head：$body"
    }

    /** 密码脱敏（失败聚合与日志输出均经此，确保密码不出现在 out 记录）。 */
    private fun redact(s: String, pwd: String): String =
        if (pwd.isEmpty()) s else s.replace(pwd, "***")

    /** 日志用输出片段（单行化 + 截断，避免刷屏/泄露长输出）。 */
    private fun snippet(s: String, max: Int = 160): String = s.replace('\n', ' ').trim().take(max)

    /** UTF-8 十六进制（AOSP 13+ `set-softap-config` 需 hex 编码参数的兼容候选）。 */
    private fun toHex(s: String): String {
        val sb = StringBuilder(s.length * 2)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun sleepSafe(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun overBudget(deadline: Long): Boolean = System.currentTimeMillis() > deadline
}
