package com.zglinus.bluelink.networking

import android.os.Build
import android.os.Handler
import android.os.Looper
import com.zglinus.bluelink.ble.SessionManager
import com.zglinus.bluelink.ble.SignalMessage
import com.zglinus.bluelink.ble.SignalProtocol
import com.zglinus.bluelink.diag.DiagLogger
import com.zglinus.bluelink.net.LanStatus
import com.zglinus.bluelink.net.NetworkSummary
import com.zglinus.bluelink.net.SameLanChecker
import org.json.JSONObject

/**
 * 组网状态机状态（A3c）。
 *
 * 状态语义：
 * - [IDLE]：未开始（构造后默认态，`start()` 之前）；
 * - [NEGOTIATING]：已记录仲裁结果并分流（who==ME 热点方 / who==PEER 等 offer / MANUAL 手动 UI）；
 * - [HOTSPOT_STARTING]：热点方逐级启动热点（L1_ROOT→L1_PRIVATE_API→L2_LOCAL_ONLY→MANUAL，
 *   含 ④ 手动配网等待回填）；
 * - [OFFER_SENT]：offer 已发送，15s 内等待对端 joined（热点方）；
 * - [WAIT_JOIN]：对端已收到 offer，等待 WifiJoiner 包完成加入（随后发 joined）；
 * - [JOINED]：热点方已收 joined（同网复核中）；对端已发 joined（等 ack）；
 * - [TRANSPORT]：传输就绪，[NetworkingStateMachine.Callbacks.onTransportReady] 已上抛；
 * - [TEARDOWN]：已中止（失败/取消），abort 已发出，等待上层降级/切角色决策。
 */
enum class NetState {
    IDLE,
    NEGOTIATING,
    HOTSPOT_STARTING,
    OFFER_SENT,
    WAIT_JOIN,
    JOINED,
    TRANSPORT,
    TEARDOWN,
}

/**
 * 组网状态机（A3c）：编排 仲裁（Arbiter.kt：[Decision] / [HotspotLevel]）→ 热点启动（[HotspotManager]，
 * 枚举为 [HotspotStartLevel]）→ BLE 信令（[SessionManager] / [SignalProtocol]）→ 传输就绪。
 *
 * 对应设计文档 docs/networking.md 组网流程（仲裁 §2 之后的状态机编排）。
 *
 * 状态转移表：
 * - IDLE → `start()` → NEGOTIATING（记录仲裁结果并分流）；
 * - 热点方（`Decision.who == ME`）：
 *   NEGOTIATING → HOTSPOT_STARTING（逐级 [HotspotStartLevel.L1_ROOT]→[HotspotStartLevel.L1_PRIVATE_API]
 *   →[HotspotStartLevel.L2_LOCAL_ONLY]→[HotspotStartLevel.MANUAL]，逐级失败自动降级下一级）
 *   → 成功后 setPassword（④ 登记后）→ 构造 offer 发送 → OFFER_SENT（15s 等 joined）
 *   → 收到 joined → JOINED（同网复核 SameLanChecker+probeTcp 占位）→ 发 ack → TRANSPORT
 *   → [Callbacks.onTransportReady](peerIp)；
 * - 对端（`who == PEER`）：NEGOTIATING（120s 等 offer，与④ 手动配置对齐）→ 收到 offer → [Callbacks.onOfferReceived](ssid,pwd)
 *   → WAIT_JOIN（等 WifiJoiner）→ `onWifiJoined(ip)` 发 joined → JOINED（15s 等 ack）
 *   → 收到 ack → TRANSPORT → [Callbacks.onTransportReady]；
 * - 手动（`who == null` 即 MANUAL）：NEGOTIATING → HOTSPOT_STARTING（触发 ④ UI）→ `onManualConfigured(ssid,pwd)`
 *   → offer → OFFER_SENT（后续同热点方）；
 * - 超时/失败：任意等待步骤超时（两阶段 120s：手动配置/等 offer 对齐；其余 15s）或失败
 *   → 发 abort(type=abort, reason) → TEARDOWN → [Callbacks.onAbort](reason)；
 * - 切角色：收到对端 abort 且 reason 含「[REASON_CANT_OPEN_HOTSPOT]」、本机能力可用 →
 *   Arbiter 重算（对端能力按无法开启置零）→ 重新走热点方流程；
 * - `cancel()`：发 abort（用户取消）→ TEARDOWN。
 *
 * 线程模型：与 [SessionManager] 一致，所有公开方法由主线程调用（engine 的 BLE 回调均已切回主线程）；
 * 超时用 [Handler]（主 Looper，工程内既有 mainHandler 模式），默认每步 15s；
 * 两阶段 120s：④ 手动配网等待用户配置回填（MANUAL）与对端等待 offer（PEER）共用对齐常量
 * [MANUAL_TIMEOUT_MS]（120s；用户需跳系统开热点+设密码，期间对端不得 15s 先 abort）。
 *
 * @param session 持久信令会话（attach 后收发 [SignalMessage]）。
 * @param hotspot 热点管理器（启动等级枚举为 [HotspotStartLevel]；仲裁的 [HotspotLevel] 仅用于结果携带）。
 * @param arbiterResult [decide] 的仲裁结果（who/level/reason）。
 * @param callbacks 状态机上抛回调（offer 供 WifiJoiner 消费、传输就绪、中止）。
 * @param handler 超时调度器（默认主 Looper）。
 * @param mineCapability 本机能力（切角色时 Arbiter 重算用；缺省时无法重算，仅按 HotspotManager 探测兜底）。
 * @param peerCapability 对端能力（切角色时按"无法开启"置零重算用）。
 * @param localNetwork 本机网络摘要（同网复核 SameLanChecker 占位输入；缺省时按通过处理）。
 */
class NetworkingStateMachine(
    private val session: SessionManager,
    private val hotspot: HotspotManager,
    private val arbiterResult: Decision,
    private val callbacks: Callbacks,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val mineCapability: Capability? = null,
    private val peerCapability: Capability? = null,
    private val localNetwork: NetworkSummary? = null,
) {

    /**
     * 状态机上抛回调（由上层/接线方实现）。
     */
    interface Callbacks {
        /**
         * 对端流程收到 offer（ssid/pwd 供后续 WifiJoiner 包消费；pwd 为 null 表示无密码，如 L2 本地热点）。
         */
        fun onOfferReceived(ssid: String, pwd: String?)

        /**
         * 传输就绪（peerIp 为对端 IPv4；一期热点 IP 为占位 ""，可能为空串）。
         */
        fun onTransportReady(peerIp: String)

        /**
         * 中止/失败（reason 见 [NetworkingStateMachine] 常量）；上层决定降级、切角色或提示用户。
         */
        fun onAbort(reason: String)
    }

    private val tag = "NetworkingStateMachine"

    @Volatile
    private var state: NetState = NetState.IDLE

    /** 对端 offer 携带的热点 IP（一期占位 ""；对端侧经 ack 确认后上抛给 onTransportReady）。 */
    private var offerPeerIp: String = ""

    private val timeoutRunnable = Runnable { onStepTimeout() }

    /** 当前步骤超时时长（scheduleTimeout 记录；超时日志/失败文案用实际时长，支持 MANUAL/PEER 等 offer 120s）。 */
    private var currentTimeoutMs: Long = STEP_TIMEOUT_MS

    /** 当前状态（只读）。 */
    val currentState: NetState get() = state

    /**
     * 启动组网：IDLE→NEGOTIATING，记录仲裁结果并分流。
     *
     * - who==ME：热点方流程（逐级启动热点 → offer → OFFER_SENT）；
     * - who==PEER：等 offer（120s，与④ 手动配置对齐）；
     * - who==null（MANUAL）：触发手动④ UI，回填后本机作为热点方广播 offer。
     */
    fun start() {
        if (state != NetState.IDLE && state != NetState.TEARDOWN) {
            DiagLogger.log(tag, "start() 忽略：当前状态 $state（仅允许 IDLE/TEARDOWN 启动）")
            return
        }
        offerPeerIp = ""
        enter(NetState.NEGOTIATING)
        DiagLogger.log(
            tag,
            "仲裁结果 who=${arbiterResult.who} level=${arbiterResult.level} reason=${arbiterResult.reason}",
        )
        when (arbiterResult.who) {
            Who.ME -> {
                DiagLogger.log(tag, "本机为热点方（who=ME），启动逐级热点流程")
                startHotspotFlow()
            }

            Who.PEER -> {
                DiagLogger.log(tag, "本机为对端（who=PEER），等待 offer（120s 超时，与④ 手动配置对齐）")
                scheduleTimeout("NEGOTIATING 等待 offer", PEER_OFFER_TIMEOUT_MS)
            }

            null -> {
                // 仲裁 MANUAL（双方均无自动热点能力）：触发 ④ 手动 UI，回填后本机作热点方发 offer
                DiagLogger.log(tag, "仲裁 MANUAL（who=null）：触发手动④ UI")
                startManualFlow()
            }
        }
    }

    /**
     * 取消：发 abort（type=abort, reason=用户取消）→ TEARDOWN → [Callbacks.onAbort]。
     * 幂等：IDLE/TEARDOWN 时仅记录。
     */
    fun cancel() {
        if (state == NetState.IDLE || state == NetState.TEARDOWN) {
            DiagLogger.log(tag, "cancel() 忽略：当前状态 $state")
            return
        }
        cancelTimer()
        DiagLogger.log(tag, "cancel()：发送 abort 并进入 TEARDOWN")
        sendAbort(REASON_CANCEL)
        enter(NetState.TEARDOWN)
        callbacks.onAbort(REASON_CANCEL)
    }

    /**
     * 远端信令入口：由接线方（engine）在 [SessionManager.Callbacks.onRemoteSignal] 中转发，
     * 按 type 分发 offer/joined/ack/abort。
     */
    fun onRemoteSignal(msg: SignalMessage) {
        DiagLogger.log(tag, "远端信令分发 type=${msg.type} state=$state")
        when (msg.type) {
            SignalProtocol.TYPE_OFFER -> onOffer(msg)
            SignalProtocol.TYPE_JOINED -> onJoined(msg)
            SignalProtocol.TYPE_ACK -> onAck()
            SignalProtocol.TYPE_ABORT -> onPeerAbort(msg.payload?.optString("reason") ?: "未知原因")
            else -> DiagLogger.log(tag, "未知信令类型 ${msg.type}，忽略")
        }
    }

    /**
     * ④ 手动配网回填（由接线方在用户完成手工配网、HotspotManager 已 setPassword 登记后调用）：
     * setPassword（④ 登记后）→ 构造 offer → OFFER_SENT。
     */
    fun onManualConfigured(ssid: String, pwd: String?) {
        if (state != NetState.HOTSPOT_STARTING) {
            DiagLogger.log(tag, "onManualConfigured 忽略：非热点启动状态（state=$state）")
            return
        }
        if (ssid.isBlank()) {
            DiagLogger.log(tag, "④ 手动配网回填 SSID 为空，失败")
            fail("手动配网回填 SSID 为空")
            return
        }
        DiagLogger.log(tag, "④ 手动配网回填：ssid=$ssid pwdLen=${pwd?.length ?: 0}，setPassword 登记后构造 offer")
        onHotspotReady(HotspotStartLevel.MANUAL, ssid, pwd)
    }

    /**
     * WifiJoiner 包加入热点完成（对端流程）：发送 joined（携带本机 IPv4）→ JOINED（15s 等 ack）。
     */
    fun onWifiJoined(ip: String) {
        if (state != NetState.WAIT_JOIN) {
            DiagLogger.log(tag, "onWifiJoined 忽略：非等待加入状态（state=$state）")
            return
        }
        cancelTimer()
        DiagLogger.log(tag, "WifiJoiner 已加入热点，本机 IP=$ip，发送 joined")
        val payload = JSONObject().put("ip", ip)
        val ok = session.sendSignal(SignalMessage(SignalProtocol.TYPE_JOINED, payload))
        if (!ok) {
            fail("joined 发送失败（无会话/无通道）")
            return
        }
        enter(NetState.JOINED)
        scheduleTimeout("JOINED 等待 ack")
    }

    /**
     * WifiJoiner 包加入热点失败（对端流程）：发 abort（reason 含「加入热点失败」）→ TEARDOWN。
     */
    fun onWifiJoinFailed(reason: String) {
        if (state != NetState.WAIT_JOIN) {
            DiagLogger.log(tag, "onWifiJoinFailed 忽略：非等待加入状态（state=$state）")
            return
        }
        fail("$REASON_JOIN_FAILED：$reason")
    }

    // ---------- 热点方流程 ----------

    private fun startHotspotFlow() {
        enter(NetState.HOTSPOT_STARTING)
        scheduleTimeout("HOTSPOT_STARTING 逐级启动热点")
        DiagLogger.log(
            tag,
            "逐级启动热点：${HotspotStartLevel.L1_ROOT} → ${HotspotStartLevel.L1_PRIVATE_API} → ${HotspotStartLevel.L2_LOCAL_ONLY} → ${HotspotStartLevel.MANUAL}",
        )
        tryStartLevel(HotspotStartLevel.L1_ROOT)
    }

    /** 手动④ 专用入口：仲裁 MANUAL 时直接触发 UI 等待回填（跳过自动等级）。 */
    private fun startManualFlow() {
        enter(NetState.HOTSPOT_STARTING)
        scheduleTimeout("MANUAL 等待用户配置", MANUAL_TIMEOUT_MS)
        val result = hotspot.start(HotspotStartLevel.MANUAL) // 触发 HotspotListener.onManualRequest（UI）
        DiagLogger.log(tag, "手动④ 启动结果 success=${result.success} error=${result.error}")
        if (result.success) {
            // 极端情形：UI 同步返回成功（预配置）→ 直接发 offer
            onHotspotReady(HotspotStartLevel.MANUAL, result.ssid, result.pwd)
        } else if (result.error != AWAITING_MANUAL) {
            fail("手动④ 启动异常：${result.error}")
        }
        // error == "AwaitingManual"：等待 onManualConfigured 回填
    }

    /**
     * 逐级尝试启动热点：L1_ROOT → L1_PRIVATE_API → L2_LOCAL_ONLY → MANUAL。
     * 每级失败自动降级下一级；MANUAL 返回 "AwaitingManual" 时进入等待回填。
     *
     * L1_ROOT（Bluelink ANR 修复，异步桥）：改调 [HotspotManager.startAsync]——矩阵（su/IO，
     * [RootSoftAp] 预算 ≤10s）在 HotspotManager 后台线程执行，结果经主线程回调
     * [onL1RootAsyncResult] 收敛（成功走 offer / 失败降级 ②）；本方法 L1_ROOT 分支立即返回，
     * 不阻塞主线程。15s 步骤超时保留兜底：矩阵超预算 → onStepTimeout → abort，不卡死；
     * 回调到达时若已非 HOTSPOT_STARTING（cancel/切角色/超时 abort）则忽略（防重入/时序漂移）。
     */
    private fun tryStartLevel(level: HotspotStartLevel) {
        if (state != NetState.HOTSPOT_STARTING) return
        DiagLogger.log(tag, "尝试启动热点等级 $level")

        // ---- L1_ROOT 异步桥：矩阵后台执行，结果主线程回调（不等矩阵，主线程立即返回） ----
        if (level == HotspotStartLevel.L1_ROOT) {
            hotspot.startAsync(HotspotStartLevel.L1_ROOT) { result -> onL1RootAsyncResult(result) }
            return
        }

        val result = hotspot.start(level)
        if (result.success) {
            DiagLogger.log(tag, "热点启动成功 level=$level ssid=${result.ssid} pwdLen=${result.pwd?.length ?: 0}")
            onHotspotReady(level, result.ssid, result.pwd)
            return
        }
        DiagLogger.log(tag, "热点启动失败 level=$level error=${result.error}")
        when (level) {
            // L1_ROOT 已由上方异步桥分支处理（return），不在同步降级链内
            HotspotStartLevel.L1_PRIVATE_API -> tryStartLevel(HotspotStartLevel.L2_LOCAL_ONLY)
            HotspotStartLevel.L2_LOCAL_ONLY -> tryStartLevel(HotspotStartLevel.MANUAL)
            HotspotStartLevel.MANUAL -> {
                if (result.error == AWAITING_MANUAL) {
                    // ④ 手动配网进行中（start(MANUAL) 已触发 UI）：等待 onManualConfigured 回填
                    DiagLogger.log(
                        tag,
                        "④ 手动配网进行中：等待 onManualConfigured(ssid,pwd)（${MANUAL_TIMEOUT_MS / 1000}s 超时）",
                    )
                    scheduleTimeout("MANUAL 等待用户配置", MANUAL_TIMEOUT_MS)
                } else {
                    fail("热点全部等级均无法启动（末级 MANUAL 失败：${result.error}）")
                }
            }
        }
    }

    /**
     * L1_ROOT 异步桥结果（Bluelink ANR 修复；由 [HotspotManager.startAsync] 经主线程 Handler 回调）：
     * - 先校验当前状态仍为 [NetState.HOTSPOT_STARTING]（防重入/时序漂移：期间被 cancel/切角色/
     *   15s 步骤超时 abort 则忽略本次结果）；
     * - success → 用 result 组装 offer（ssid/pwd/ip 走原 [onHotspotReady] 成功路径）；
     * - 失败 → 降级下一级 [HotspotStartLevel.L1_PRIVATE_API]（复用现有降级链）。
     */
    private fun onL1RootAsyncResult(result: HotspotResult) {
        if (state != NetState.HOTSPOT_STARTING) {
            DiagLogger.log(tag, "L1_ROOT 异步回调忽略：当前状态 $state（防重入/时序漂移）")
            return
        }
        if (result.success) {
            DiagLogger.log(
                tag,
                "热点启动成功 level=${HotspotStartLevel.L1_ROOT} ssid=${result.ssid} " +
                    "pwdLen=${result.pwd?.length ?: 0}",
            )
            onHotspotReady(HotspotStartLevel.L1_ROOT, result.ssid, result.pwd)
            return
        }
        DiagLogger.log(
            tag,
            "热点启动失败 level=${HotspotStartLevel.L1_ROOT} error=${result.error}，降级下一级 ②",
        )
        tryStartLevel(HotspotStartLevel.L1_PRIVATE_API)
    }

    /**
     * 热点就绪：④ 登记后 setPassword → 构造 offer（SignalProtocol，ssid/pwd/ip=热点 IP 占位""/hotspotType）
     * → SessionManager.sendSignal → OFFER_SENT（15s 等 joined）。
     */
    private fun onHotspotReady(level: HotspotStartLevel, ssid: String?, pwd: String?) {
        if (state != NetState.HOTSPOT_STARTING) return
        if (ssid == null || ssid.isBlank()) {
            fail("热点启动成功但 SSID 缺失（level=$level）")
            return
        }
        // ④ 登记后 setPassword（App 不生成不指定，仅登记供 offer 携带）
        if (level == HotspotStartLevel.MANUAL && pwd != null) {
            hotspot.setPassword(pwd)
        }
        val offer = buildOffer(level, ssid, pwd)
        val ok = session.sendSignal(offer)
        if (!ok) {
            fail("offer 发送失败（无会话/无通道）")
            return
        }
        cancelTimer()
        enter(NetState.OFFER_SENT)
        scheduleTimeout("OFFER_SENT 等待 joined")
    }

    /** 构造 offer 信令：payload { ssid, pwd, ip=热点 IP 占位"", hotspotType=启动等级名 }。 */
    private fun buildOffer(level: HotspotStartLevel, ssid: String, pwd: String?): SignalMessage {
        val payload = JSONObject()
        payload.put("ssid", ssid)
        payload.put("pwd", pwd ?: "")
        payload.put("ip", "") // 当前本机热点 IP 占位""（一期不采集，二期由热点实现回填）
        payload.put("hotspotType", level.name)
        return SignalMessage(type = SignalProtocol.TYPE_OFFER, payload = payload)
    }

    // ---------- 信令处理 ----------

    /** 对端流程：收到 offer → onOfferReceived(ssid,pwd) → WAIT_JOIN（等 WifiJoiner）。 */
    private fun onOffer(msg: SignalMessage) {
        if (state != NetState.NEGOTIATING) {
            DiagLogger.log(tag, "收到 offer 但非等待状态（state=$state），忽略")
            return
        }
        val payload = msg.payload
        if (payload == null) {
            DiagLogger.log(tag, "offer 缺 payload，忽略")
            return
        }
        val ssid = payload.optString("ssid", "")
        if (ssid.isBlank()) {
            fail("offer 中 SSID 为空")
            return
        }
        val pwd = payload.optString("pwd", "").takeIf { it.isNotBlank() }
        offerPeerIp = payload.optString("ip", "").takeIf { it.isNotBlank() } ?: ""
        cancelTimer()
        enter(NetState.WAIT_JOIN)
        DiagLogger.log(tag, "收到 offer：ssid=$ssid pwdLen=${pwd?.length ?: 0} ip=$offerPeerIp（供 WifiJoiner 消费）")
        callbacks.onOfferReceived(ssid, pwd)
        scheduleTimeout("WAIT_JOIN 等待 WifiJoiner 加入")
    }

    /** 热点方流程：收到 joined → JOINED → 同网复核（占位）→ 发 ack → TRANSPORT → onTransportReady(peerIp)。 */
    private fun onJoined(msg: SignalMessage) {
        if (state != NetState.OFFER_SENT) {
            DiagLogger.log(tag, "收到 joined 但非 OFFER_SENT（state=$state），忽略")
            return
        }
        cancelTimer()
        val peerIp = msg.payload?.optString("ip", "") ?: ""
        enter(NetState.JOINED)
        DiagLogger.log(tag, "收到 joined：peerIp=$peerIp，同网复核")
        if (!verifySameLan(peerIp)) {
            fail("同网复核失败（peerIp=$peerIp）")
            return
        }
        val ack = SignalMessage(type = SignalProtocol.TYPE_ACK)
        val ok = session.sendSignal(ack)
        if (!ok) {
            fail("ack 发送失败（无会话/无通道）")
            return
        }
        enter(NetState.TRANSPORT)
        DiagLogger.log(tag, "传输就绪：对端 $peerIp 已入网，onTransportReady($peerIp)")
        callbacks.onTransportReady(peerIp)
    }

    /** 对端流程：收到 ack（热点方已确认传输就绪）→ TRANSPORT → onTransportReady(offer 中的热点 IP)。 */
    private fun onAck() {
        if (state != NetState.JOINED) {
            DiagLogger.log(tag, "收到 ack 但非 JOINED（state=$state），忽略")
            return
        }
        cancelTimer()
        enter(NetState.TRANSPORT)
        DiagLogger.log(tag, "收到 ack，传输就绪：onTransportReady($offerPeerIp)")
        callbacks.onTransportReady(offerPeerIp)
    }

    /**
     * 收到对端 abort：
     * - reason 含「[REASON_CANT_OPEN_HOTSPOT]」且本机非当前热点方 → 切角色（能力可用则 Arbiter 重算后重新开热点）；
     * - 其余 → 直接失败（发 abort 回落）→ onAbort。
     */
    private fun onPeerAbort(reason: String) {
        DiagLogger.log(tag, "收到对端 abort：reason=$reason")
        cancelTimer()
        if (reason.contains(REASON_CANT_OPEN_HOTSPOT) && arbiterResult.who != Who.ME) {
            tryRoleSwitch(reason)
        } else {
            fail("对端中止：$reason")
        }
    }

    /**
     * 切角色：对端无法开热点且本机能力可用 → Arbiter 重算（对端能力按无法开启置零）
     * → 重算 who==ME 则作为热点方重新 start（走 startHotspotFlow）。
     */
    private fun tryRoleSwitch(peerReason: String) {
        val mine = mineCapability
        if (mine == null) {
            DiagLogger.log(tag, "切角色：未注入本机能力（mineCapability=null），无法重算 Arbiter")
            fail("对端无法开启热点（$peerReason），本机缺少能力参数无法切角色")
            return
        }
        if (!localCanOpenHotspot()) {
            DiagLogger.log(tag, "切角色：本机无可用热点能力，放弃")
            fail("对端无法开启热点（$peerReason），本机亦无热点能力")
            return
        }
        // 对端已声明无法开 → 其对端能力按"全部不可用"置零后重算，避免再次指派给对端
        val peerDisabled = peerCapability?.let {
            it.copy(isRoot = false, privateApiCapable = false, localOnlyAvailable = false)
        } ?: Capability(
            isRoot = false,
            privateApiCapable = false,
            localOnlyAvailable = false,
            battery = null,
        )
        val redecided = decide(mine, peerDisabled)
        DiagLogger.log(
            tag,
            "切角色：Arbiter 重算（对端能力置零）→ who=${redecided.who} level=${redecided.level} reason=${redecided.reason}",
        )
        if (redecided.who == Who.ME) {
            DiagLogger.log(tag, "切角色成功：本机转为热点方，重新 start")
            startHotspotFlow()
        } else {
            fail("切角色重算后仍非本机开热点（${redecided.reason}）")
        }
    }

    /**
     * 同网复核（一期占位）：SameLanChecker 子网比较 + probeTcp 均为占位。
     * - 无本机网络摘要（localNetwork=null）或信息不足（UNKNOWN，如对端 IP 一期为 ""）→ 占位通过；
     * - DIFFERENT_NETWORK → 不通过（走 abort）；
     * - probeTcp 一期不实际执行（恒 false），仅记录，不参与判定。
     */
    private fun verifySameLan(peerIp: String): Boolean {
        val local = localNetwork
        if (local == null) {
            DiagLogger.log(tag, "同网复核占位：无本机网络摘要（localNetwork=null），按通过处理")
            return true
        }
        val remote = NetworkSummary(wifi = true, ip = peerIp.takeIf { it.isNotBlank() })
        val status = SameLanChecker.check(local, remote)
        DiagLogger.log(tag, "同网复核 SameLanChecker：local=${local.describe()} peerIp=$peerIp → $status")
        if (peerIp.isNotBlank()) {
            val probe = SameLanChecker.probeTcp(peerIp)
            DiagLogger.log(tag, "probeTcp($peerIp) 占位结果=$probe（一期不执行，不参与判定）")
        }
        return when (status) {
            LanStatus.SAME_LAN -> true
            LanStatus.UNKNOWN -> {
                DiagLogger.log(tag, "同网信息不足（UNKNOWN），占位按通过处理（二期由 probeTcp 兜底）")
                true
            }
            LanStatus.DIFFERENT_NETWORK -> false
        }
    }

    /** 本机是否具备任意热点能力（root / 私有 API / L2 本地热点）。 */
    private fun localCanOpenHotspot(): Boolean {
        val mine = mineCapability
        if (mine != null) {
            return mine.isRoot || mine.privateApiCapable || mine.localOnlyAvailable
        }
        // 兜底：按 HotspotManager 探测 + 一期私有 API 启发（sdkInt in 26..28，与 Arbiter 判定一致）
        val sdk = Build.VERSION.SDK_INT
        val privateApi = sdk in 26..28
        return hotspot.isRootAvailable() || privateApi || hotspot.isLevel2Available(sdk)
    }

    // ---------- 超时与失败 ----------

    private fun scheduleTimeout(stage: String, timeoutMs: Long = STEP_TIMEOUT_MS) {
        cancelTimer()
        currentTimeoutMs = timeoutMs
        DiagLogger.log(tag, "启动 $stage 超时定时（${timeoutMs / 1000}s）")
        handler.postDelayed(timeoutRunnable, timeoutMs)
    }

    private fun cancelTimer() {
        handler.removeCallbacks(timeoutRunnable)
    }

    private fun onStepTimeout() {
        if (state == NetState.IDLE || state == NetState.TRANSPORT || state == NetState.TEARDOWN) return
        val stage = when (state) {
            NetState.NEGOTIATING -> "等待对端 offer"
            NetState.HOTSPOT_STARTING -> "热点启动/手动配置"
            NetState.OFFER_SENT -> "等待 joined"
            NetState.WAIT_JOIN -> "等待 WifiJoiner 加入"
            NetState.JOINED -> "等待 ack"
            else -> state.name
        }
        DiagLogger.log(tag, "步骤超时（${currentTimeoutMs / 1000}s）：$stage（state=$state）")
        fail("${currentTimeoutMs / 1000}s 步骤超时：$stage")
    }

    /** 失败收敛：发 abort（type=abort, reason）→ TEARDOWN → onAbort(reason)（上层决定降级/切角色）。 */
    private fun fail(reason: String) {
        if (state == NetState.IDLE || state == NetState.TRANSPORT || state == NetState.TEARDOWN) {
            DiagLogger.log(tag, "fail($reason) 忽略：当前状态 $state")
            return
        }
        cancelTimer()
        DiagLogger.log(tag, "失败：$reason")
        sendAbort(reason)
        enter(NetState.TEARDOWN)
        callbacks.onAbort(reason)
    }

    private fun sendAbort(reason: String) {
        val payload = JSONObject().put("reason", reason)
        val ok = session.sendSignal(SignalMessage(type = SignalProtocol.TYPE_ABORT, payload = payload))
        DiagLogger.log(tag, "发送 abort：ok=$ok reason=$reason")
    }

    private fun enter(newState: NetState) {
        val from = state
        state = newState
        DiagLogger.log(tag, "状态转移：$from → $newState")
    }

    companion object {
        /** abort 原因标记：对端无法开启热点（对端收到后触发切角色重算）。 */
        const val REASON_CANT_OPEN_HOTSPOT = "无法开启热点"

        /** abort 原因：用户取消。 */
        const val REASON_CANCEL = "用户取消"

        /** abort 原因前缀：加入热点失败。 */
        const val REASON_JOIN_FAILED = "加入热点失败"

        /** HotspotManager 手动④ 的骨架返回标记（error 字段，等待回填）。 */
        private const val AWAITING_MANUAL = "AwaitingManual"

        /** 每步超时（默认）：15s。 */
        private const val STEP_TIMEOUT_MS: Long = 15_000L

        /** ④ 手动配网等待用户配置回填超时：120s（用户需跳系统开热点+设密码+回来确认，15s 必超时）。 */
        private const val MANUAL_TIMEOUT_MS: Long = 120_000L

        /** 对端等待 offer 超时：与④ 手动配网回填对齐为 120s（引用 [MANUAL_TIMEOUT_MS] 同一值；手动配置期间对端若 15s 先 abort 会导致 offer 发送失败）。 */
        private const val PEER_OFFER_TIMEOUT_MS: Long = MANUAL_TIMEOUT_MS
    }
}
