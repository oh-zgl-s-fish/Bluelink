package com.zglinus.bluelink.diag

import android.util.Log
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 内置诊断日志：App 内环形内存缓冲（上限 [CAPACITY] 条），BLE 关键事件双写
 * （内存缓冲 + logcat `Log.i("Diag[tag]", ...)`），运行时可通过 UI 查看/复制/导出，
 * 不再依赖 adb logcat。
 *
 * 线程安全：缓冲用 [ConcurrentLinkedQueue]；时间戳用 String.format 的日期格式
 * （每次调用自建 Formatter，无共享可变 SimpleDateFormat），可安全地从 Binder 线程
 * 与主线程并发调用。
 *
 * v0.5.10 关于页「收集日志」两段式：起点经 [entryCount]（单调总条数，含已滚出缓冲的条目，
 * 不受环形滚动丢帧影响），停止时经 [entriesSince] 取起点后新增条目文本。
 */
object DiagLogger {

    /** 环形缓冲容量：超出后丢弃最旧条目。 */
    private const val CAPACITY = 512

    private val buffer = ConcurrentLinkedQueue<String>()

    /**
     * 已记总条数（单调递增，含被环形滚动挤出的条目）。[clear] 不清零——保证两段式
     * 起点偏移语义（起点总是 ≤ 停止时的总条数）。
     */
    private val totalLogged = AtomicLong(0)

    /**
     * 追加一条诊断日志：`[HH:mm:ss.SSS] [tag] msg`，同时写 logcat（`Log.i("Diag[tag]", ...)`）。
     */
    fun log(tag: String, msg: String) {
        val ts = String.format(Locale.US, "%1\$tH:%1\$tM:%1\$tS.%1\$tL", Date())
        val line = "[$ts] [$tag] $msg"
        buffer.add(line)
        while (buffer.size > CAPACITY) {
            buffer.poll()
        }
        totalLogged.incrementAndGet()
        Log.i("Diag[$tag]", msg)
    }

    /** 按时间顺序拼接全部条目为文本（每行 `[HH:mm:ss.SSS] [tag] msg`），末尾带换行。 */
    fun dump(): String {
        val sb = StringBuilder(buffer.size * 80)
        for (line in buffer) {
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    /** 清空缓冲。 */
    fun clear() {
        buffer.clear()
    }

    /**
     * 已记总条数（单调递增，含已滚出缓冲的条目）——「收集日志」两段式起点采样（v0.5.10）。
     * 用总条数而非 [buffer.size]：缓冲环形滚动丢帧不影响起点语义。
     */
    fun entryCount(): Long = totalLogged.get()

    /**
     * 返回自 [startCount]（[entryCount] 采样）之后新增的条目文本（每行 `[HH:mm:ss.SSS] [tag] msg`，
     * 末尾带换行）；早于缓冲窗口被环形滚动挤出的条目不返回（保守——宁少不漏旧）；起点晚于
     * 当前缓冲内容（期间清过缓冲）时返回空。
     */
    fun entriesSince(startCount: Long): String {
        val atStop = totalLogged.get()
        val keep = (atStop - startCount).coerceIn(0L, buffer.size.toLong()).toInt()
        if (keep <= 0) return ""
        val lines = buffer.toList()
        val sb = StringBuilder(keep * 80)
        for (i in lines.size - keep until lines.size) {
            sb.append(lines[i]).append('\n')
        }
        return sb.toString()
    }
}
