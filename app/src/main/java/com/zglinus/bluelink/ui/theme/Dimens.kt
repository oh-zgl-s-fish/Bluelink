package com.zglinus.bluelink.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 尺度 token：间距 / 圆角 / 动效（docs/md3-audit.md §3 P0-3 尺度表落地）。
 * - 间距：4dp 节奏；离群值仅按审计表归一（2→4 日志行距 S5、6→8 S1、14→16 S1、3 stroke 保留）；
 * - 圆角：沿用 M3 默认 scale（xs4/sm8/md12/lg16/xl28）显式成 token，不引入新圆角差异（S7）；
 * - 动效：值 = v0.5.1a 实机档（650 布局/450+400 流程区/1300+1000 脉冲），只收归不改值（M2/M3/M5）；
 * - 例外度量（58/360/24/30/8dp 等非 4dp 节奏项）见 [MetricTokens]，注明非间距 token。
 */

/** 间距 token（audit P0-3 命名：SpaceXs/SpaceSm/SpaceMd/SpaceLg/SpaceXl/SpaceXxl）。 */
object SpacingTokens {
    /** xs=4dp —— 归并原 2dp 日志行距/行内距（audit S5） */
    val SpaceXs: Dp = 4.dp

    /** sm=8dp —— 归并原 6dp 列表/行距/按钮行距（audit S1） */
    val SpaceSm: Dp = 8.dp

    /** md=12dp —— 卡内/区块留白（v0.5.1a-5 实机档，audit S2 保留） */
    val SpaceMd: Dp = 12.dp

    /** lg=16dp —— 页面/弹层水平留白；归并原 14dp 流程区水平 padding（audit S1） */
    val SpaceLg: Dp = 16.dp

    /** xl=24dp */
    val SpaceXl: Dp = 24.dp

    /** xxl=32dp（audit 备用档） */
    val SpaceXxl: Dp = 32.dp
}

/** 圆角 token：显式接 Shapes（audit S7/P0-3）。
 *
 * v0.5.4a 定稿两档：浮层 10dp（[Modal]）/ 小件 8dp（[Small]）。
 * v0.5.4b 块级内容容器（surfaceContainer 系列分层块，见 MainScreen.kt）同样取 10dp 档：
 * theme 中 M3 large 槽位（＝导航抽屉容器）与 extraLarge 槽位（＝AlertDialog/ModalBottomSheet）一并接 [Modal]，
 * 代码侧块级容器直接用 MaterialTheme.shapes.large（＝10，接线见 BluelinkTheme.kt）——块级统一 10、小件统一 8。
 * 原 Large(16)/ExtraLarge(28) 常量随 v0.5.4a 移除（无消费点）。
 */
object ShapeTokens {
    /** xs=4dp */
    val ExtraSmall: Dp = 4.dp

    /** sm=8dp —— badge / 徽章 / 按钮 / 输入框（小件档，v0.5.4a 保持 8） */
    val Small: Dp = 8.dp

    /** md=12dp —— M3 medium 槽位（Card 已去容器化，无当前视觉消费点，保留槽位接线） */
    val Medium: Dp = 12.dp

    /** modal=10dp（v0.5.4a 新增）—— 浮层（sheet/dialog/抽屉容器）与 v0.5.4b 块级内容容器共用圆角档 */
    val Modal: Dp = 10.dp
}

/** 动效 token：时长(ms)/缓动。值 = v0.5.1a 实机档，仅收归 token（audit P0-3/M5）。 */
object MotionTokens {
    /** 200ms：utility 快速（模板 durationShort；audit quick） */
    const val DurationShort = 200

    /** 350ms：通用中档（模板 durationMedium；预留档位，当前代码未直接引用） */
    const val DurationMedium = 350

    /** 650ms：两栏宽度切换舒缓档（模板 durationLong；audit layout，v0.5.1a-3 实机档） */
    const val DurationLong = 650

    /** 400ms：流程区收起 fade 副档（v0.5.1a-3） */
    const val DurationFast = 400

    /** 450ms：流程区展开/收起主档（v0.5.1a-3；audit gentle） */
    const val DurationGentle = 450

    /** 1300ms：脉冲环 alpha 周期（原 800ms → 1300ms） */
    const val DurationPulse = 1300

    /** 1000ms：呼吸环尺寸变换（原 600ms → 1000ms） */
    const val DurationRing = 1000

    /** 1000ms：呼吸环相位延迟（原 600ms → 1000ms；Long：供 delay() 直接使用） */
    const val DelayPulse = 1000L

    /** 布局切换缓动（宽度切换等，即 v0.5.1a FastOutSlowIn） */
    val EasingLayout: Easing = FastOutSlowInEasing

    // ---- P2-1 减动效档（audit M1/A6/P2-1：Settings.Global.ANIMATOR_DURATION_SCALE==0）----

    /** 减动效档动画时长：0ms 直切（tween(0)）；无脉冲/呼吸等无限重复动画（PulseRing 已随 v0.5.4a 移除）。 */
    const val DurationReduced = 0

    /**
     * 布局切换（两栏 width weight 动画）spec：reduced=true → tween(0) 直切；
     * 否则 650ms 品牌档（v0.5.1a-3 实机档，audit「保留但进 token」）。
     */
    fun layoutSpec(reduced: Boolean): TweenSpec<Float> =
        if (reduced) tween(DurationReduced) else tween(DurationLong, easing = EasingLayout)

    /**
     * 面板切换（Crossfade）spec：reduced=true → tween(0) 直切；否则 200ms 快速档（[DurationShort]）。
     */
    fun crossfadeSpec(reduced: Boolean): FiniteAnimationSpec<Float> =
        if (reduced) tween<Float>(DurationReduced) else tween<Float>(DurationShort)
}

/**
 * 系统减动效检测（audit P2-1/A6/M1）：`Settings.Global.ANIMATOR_DURATION_SCALE == 0` 即视为开启。
 * 主题级（app 启动）读取一次——由 [com.zglinus.bluelink.ui.BluelinkEngine] init 写入
 * `ui.reduceMotion`；MainScreen 动画 spec 消费点据此走 [MotionTokens.layoutSpec]/
 * [MotionTokens.crossfadeSpec] 最短档（0ms 直切）。读取失败/异常回退 false（不猜减动效）。
 */
fun isReduceMotionEnabled(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (e: Exception) {
    false
}

/** 内容型度量 token（非 4dp 节奏间距项；audit P0-3 例外清单，避免误并入间距 scale）。 */
object MetricTokens {
    /** 时间流时间戳列宽（内容度量，非间距 token；58dp 原值保留，P2 文本缩放保护待做——audit S4） */
    val TimeColumnWidth: Dp = 58.dp

    /** 诊断日志弹层最大高度（heightIn 上限 360dp → token，audit P0-3） */
    val DiagLogMaxHeight: Dp = 360.dp

    /** 呼吸环基态直径 */
    val PulseRingBase: Dp = 24.dp

    /** 呼吸环扩张态直径（原 30dp 离群值 → token，audit P0-3 ringLg） */
    val PulseRingLarge: Dp = 30.dp

    /** 环心脉冲点直径（8dp；语义图形 ≥8dp，audit S6 保持） */
    val PulseRingDot: Dp = 8.dp

    /** 时间流事件色点直径（原 6dp → 8dp；语义图形 ≥8dp，audit S6/S1） */
    val EventDot: Dp = 8.dp

    // ============ v0.5.6b 顶栏广播呼吸圆钮（广播 Switch → 呼吸按钮） ============

    /** 广播圆钮视觉直径（30dp；任务档 28-32dp 取中值，v0.5.6b） */
    val AdvertiseKnob: Dp = 30.dp

    /** 广播圆钮触达命中区边长（48dp ≥48×48 audit；视觉 30dp 圆钮居中于命中区内，v0.5.6b） */
    val AdvertiseKnobTouch: Dp = 48.dp

    /** 广播圆钮中心点直径（8dp；语义图形 ≥8dp，audit S6，v0.5.6b） */
    val AdvertiseKnobDot: Dp = 8.dp
}
