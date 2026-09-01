package com.zglinus.bluelink.diag

import android.util.Log
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 内置诊断日志：App 内环形内存缓冲（上限 [CAPACITY] 条），BLE 关键事件双写
 * （内存缓冲 + logcat `Log.i("Diag[tag]", ...)`），运行时可通过 UI 查看/复制/导出，
 * 不再依赖 adb logcat。
 *
 * 线程安全：缓冲用 [ConcurrentLinkedQueue]；时间戳用 String.format 的日期格式
 * （每次调用自建 Formatter，无共享可变 SimpleDateFormat），可安全地从 Binder 线程
 * 与主线程并发调用。
 */
object DiagLogger {

    /** 环形缓冲容量：超出后丢弃最旧条目。 */
    private const val CAPACITY = 512

    private val buffer = ConcurrentLinkedQueue<String>()

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
}
