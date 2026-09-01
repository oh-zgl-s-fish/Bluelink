package com.zglinus.bluelink.networking

/**
 * 组网角色仲裁器（A3a，单文件、纯 Kotlin、无 Android 框架依赖）。
 *
 * 对应设计文档 docs/networking.md §2「热点角色仲裁」：
 * - §2.1 L1 自动热点：`isRoot || privateApiCapable` 视为 L1 可用；
 * - §2.2 L2 本地热点：`localOnlyAvailable`（Local-only hotspot，无密码局域网）；
 * - §2.3 全不可 → 走手动④（用户手工配网）。
 *
 * 仲裁输入为双方能力（[Capability]），输出为决策（[Decision]）：
 * 决策的 `who` 指向承担热点的一方，`level` 为热点等级；
 * `who == null` 仅当 `level == MANUAL`（无自动热点方，需人工介入）。
 *
 * 本文件不引用 android.*，可独立于设备环境做单元测试。
 */

/** 单端能力描述（组网握手后由双方各自上报）。 */
data class Capability(
    /** 是否具备 root 权限（root 通道可走 L1 自动热点）。 */
    val isRoot: Boolean,
    /** 是否具备私有 API 能力（一期按 sdkInt in 26..28 启发，B 包按机型实测替换）。 */
    val privateApiCapable: Boolean,
    /** 是否具备 Local-only 热点能力（Android 8-9 或 13+，10-12 盲区禁用）。 */
    val localOnlyAvailable: Boolean,
    /** 当前电量百分比（0..100），未知为 null；电量仲裁中 null 按相等处理。 */
    val battery: Int?,
)

/** 热点等级：L1 自动热点 / L2 本地热点 / 手动。 */
enum class HotspotLevel { L1_AUTO, L2_LOCAL_ONLY, MANUAL }

/** 角色：ME 本机 / PEER 对端。 */
enum class Who { ME, PEER }

/**
 * 仲裁结果。
 *
 * @param who 承担热点的一方；**仅当 level == MANUAL 时为 null**（双方均无自动热点能力）。
 * @param level 热点等级（§2.1 → L1_AUTO，§2.2 → L2_LOCAL_ONLY，§2.3 → MANUAL）。
 * @param reason 决策原因（对应用户可见文案 / 日志）。
 */
data class Decision(
    val who: Who?,
    val level: HotspotLevel,
    val reason: String,
)

/**
 * 角色仲裁主入口（docs/networking.md §2）。
 *
 * 规则：
 * 1. 任一方 L1 可用（`isRoot || privateApiCapable`）：
 *    - 双方皆 L1 可用 → 电量高者开热点（电量 null 按相等处理 → 默认 ME）；
 *    - 仅一方 L1 可用 → 该方开热点；
 *    - level = L1_AUTO；
 * 2. 无 L1 但任一方 `localOnlyAvailable` → 该方当热点方（双方皆可 → 电量高者，null 按相等 → 默认 ME）；
 *    level = L2_LOCAL_ONLY；
 * 3. 全不可 → `Decision(null, MANUAL, ...)`，走手动④。
 */
fun decide(mine: Capability, peer: Capability): Decision {
    // §2.1 L1 可用性判定：root 或私有 API 任一满足即具备 L1 自动热点能力
    val mineL1 = mine.isRoot || mine.privateApiCapable
    val peerL1 = peer.isRoot || peer.privateApiCapable

    // §2.1 分支：任一方具备 L1 能力 → 整条链走 L1_AUTO（优先级高于 L2）
    if (mineL1 || peerL1) {
        return when {
            // §2.1a 双方皆 L1 可用：电量高者开热点；电量相等或未知（null 按相等处理）→ 默认 ME
            mineL1 && peerL1 -> {
                val winner = if (batteryWins(mine.battery, peer.battery)) Who.ME else Who.PEER
                Decision(
                    who = winner,
                    level = HotspotLevel.L1_AUTO,
                    reason = if (winner == Who.ME) {
                        "双方均具备L1自动热点能力(§2.1a)，本机电量不低于对端，由本机开L1热点"
                    } else {
                        "双方均具备L1自动热点能力(§2.1a)，对端电量更高，由对端开L1热点"
                    },
                )
            }
            // §2.1b 仅本机 L1 可用：本机开热点
            mineL1 -> Decision(
                who = Who.ME,
                level = HotspotLevel.L1_AUTO,
                reason = "仅本机具备L1自动热点能力(§2.1b)，由本机开L1热点",
            )
            // §2.1c 仅对端 L1 可用：对端开热点
            else -> Decision(
                who = Who.PEER,
                level = HotspotLevel.L1_AUTO,
                reason = "仅对端具备L1自动热点能力(§2.1c)，由对端开L1热点",
            )
        }
    }

    // §2.2 分支：无 L1，退而求其次走 L2 本地热点（Local-only，无密码局域网）
    if (mine.localOnlyAvailable || peer.localOnlyAvailable) {
        return when {
            // §2.2a 双方皆具备 L2 本地热点能力：电量高者当热点方；相等或未知（null 按相等处理）→ 默认 ME
            mine.localOnlyAvailable && peer.localOnlyAvailable -> {
                val winner = if (batteryWins(mine.battery, peer.battery)) Who.ME else Who.PEER
                Decision(
                    who = winner,
                    level = HotspotLevel.L2_LOCAL_ONLY,
                    reason = if (winner == Who.ME) {
                        "双方均具备L2本地热点能力(§2.2a)，本机电量不低于对端，由本机开L2热点"
                    } else {
                        "双方均具备L2本地热点能力(§2.2a)，对端电量更高，由对端开L2热点"
                    },
                )
            }
            // §2.2b 仅本机具备 L2 本地热点能力：本机当热点方
            mine.localOnlyAvailable -> Decision(
                who = Who.ME,
                level = HotspotLevel.L2_LOCAL_ONLY,
                reason = "仅本机具备L2本地热点能力(§2.2b)，由本机开L2热点",
            )
            // §2.2c 仅对端具备 L2 本地热点能力：对端当热点方
            else -> Decision(
                who = Who.PEER,
                level = HotspotLevel.L2_LOCAL_ONLY,
                reason = "仅对端具备L2本地热点能力(§2.2c)，由对端开L2热点",
            )
        }
    }

    // §2.3 分支：双方均无自动热点能力 → who 为 null、level=MANUAL，走手动④
    return Decision(
        who = null,
        level = HotspotLevel.MANUAL,
        reason = "双方均无自动热点能力，走手动④",
    )
}

/**
 * 电量仲裁比较（§2.1a / §2.2a 共用）：
 * 双方电量均非 null 时取高者；任一为 null 或相等 → 视为平局，返回 true（默认 ME）。
 */
private fun batteryWins(mine: Int?, peer: Int?): Boolean =
    mine == null || peer == null || mine >= peer

/**
 * 依据本机运行时状态构建本端能力（docs/networking.md §2 能力采集辅助）。
 *
 * - `localOnlyAvailable`：`sdkInt in 26..28 || sdkInt >= 33`（Android 8-9 或 13+；
 *   10-12 为 Local-only 热点盲区，禁用）；
 * - `privateApiCapable`：一期按 `sdkInt in 26..28` 启发（B 包按机型实测替换，见设计文档）；
 * - `isRoot`、`battery` 由调用方采集后透传。
 */
fun buildLocalCapability(isRoot: Boolean, battery: Int?, sdkInt: Int): Capability {
    // 8-9（26..28）或 13+（>=33）可用；10-12 盲区禁用
    val localOnlyAvailable = sdkInt in 26..28 || sdkInt >= 33
    // 一期私有 API 启发：仅 8-9；B 包按机型实测替换
    val privateApiCapable = sdkInt in 26..28
    return Capability(
        isRoot = isRoot,
        privateApiCapable = privateApiCapable,
        localOnlyAvailable = localOnlyAvailable,
        battery = battery,
    )
}
