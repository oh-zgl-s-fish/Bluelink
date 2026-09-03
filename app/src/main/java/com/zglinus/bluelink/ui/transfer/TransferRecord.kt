package com.zglinus.bluelink.ui.transfer

import android.content.Context
import android.content.SharedPreferences
import com.zglinus.bluelink.diag.DiagLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.5.14d 文件传输记录（docs/ui-design.md §4.7「默认摘要 · 可展开」；替代 LOG 页旧的全屏事件时间流）。
 *
 * 模型/采集说明：
 * - **一条记录 = 一次传输会话**（LocalSend 协议一次 prepare-upload 会话；多文件会话合并为一条：
 *   [fileCount]/[totalBytes] 为汇总，文件级明细见 [files]，文件数上限不做硬限制）；
 * - 发送侧（[com.zglinus.bluelink.ui.BluelinkEngine.confirmSend]）会话：开始 = 发送线程启动，
 *   定稿 = LocalSendClient onAllDone（OK）/ onError（FAILED）/ onCancelled（CANCELLED）；
 * - 接收侧（LocalSendServer sessionId）会话：开始 = 首个文件完整落盘（onFileReceived），
 *   定稿 = 引擎轮询检测到会话从服务器活动表消失（全文件收完=OK / 对端取消=暂存目录被删=CANCELLED）
 *   或停滞超时（FAILED）或本地停服（CANCELLED）——见 BluelinkEngine v0.5.14d 传输记录区；
 * - [peerSpeedBps] 为会话平均字节/秒（仅 OK 记录填；峰值速度需传输层逐块回调，本期不改传输层不采集）；
 * - [dirPath]：发送侧 null；接收侧 = 自定义接收目录显示名（未自定义则暂存目录绝对路径，选定目录后转存）。
 */

/** 传输会话结果状态（OK=成功完成 / FAILED=失败 / CANCELLED=取消——发送方主动取消或接收会话中止）。 */
enum class TransferStatus { OK, FAILED, CANCELLED }

/** 会话内单文件明细（ok=false：发送=未完成（失败/取消中断于其前或其身）；接收=不可能出现——仅完整落盘文件入明细）。 */
data class TransferFileInfo(
    val name: String,
    val bytes: Long,
    val ok: Boolean,
)

/** 一次文件传输会话记录（不可变；持久化见 [TransferRecordStore]）。 */
data class TransferRecord(
    val id: String,
    val peerAlias: String, // 对端别名（记录时刻会话对端；无则「未知设备」）
    val isReceive: Boolean, // true=本机接收 / false=本机发送
    val fileCount: Int, // 会话文件数（与 files.size 一致）
    val totalBytes: Long, // 会话总字节
    val status: TransferStatus,
    val startedAt: Long, // epoch ms（发送=发送线程启动；接收=首文件完整落盘）
    val endedAt: Long, // epoch ms（会话定稿）
    val durationMs: Long?, // endedAt-startedAt
    val peerSpeedBps: Long?, // 平均字节/秒（仅 OK）
    val dirPath: String?, // 落盘位置展示串（发送=null；接收=接收目录名/暂存目录）
    val files: List<TransferFileInfo>, // 文件级明细
    val failReason: String?, // 失败/取消原因（红字展示；OK=null）
)

/**
 * v0.5.14d 传输记录持久化（SharedPreferences JSON 数组，工程已有 org.json；无新依赖）。
 *
 * - prefs：`bluelink_transfer_records/records`（JSON 数组，元素顺序 = 倒序，最新在前）；
 * - 上限 [MAX_RECORDS]（50）条，超限丢最旧（数组尾部）；
 * - 内存缓存 [records] 直读（倒序），[add] 写缓存 + 持久化；方法 @Synchronized（单例由
 *   BluelinkEngine 持有；主线程调用，亦容忍后台线程）；单测友好（records/add 无 Android 依赖面，
 *   仅构造需要 Context——单测可注入 MockContext 或经引擎路径验证）。
 */
class TransferRecordStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "bluelink_transfer_records"
        private const val KEY_RECORDS = "records"
        private const val TAG = "TransferRecordStore"

        /** 记录保留上限（超出丢最旧）。 */
        const val MAX_RECORDS = 50
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 内存缓存（倒序=最新在前；与持久化 JSON 顺序一致）。 */
    private val cache: MutableList<TransferRecord> = ArrayList()

    init {
        load()
    }

    /** 全部记录（倒序=最新在前；返回副本，不暴露内部可变列表）。 */
    @Synchronized
    fun records(): List<TransferRecord> = ArrayList(cache)

    /** 新增一条记录（置顶；超出 [MAX_RECORDS] 丢最旧；写 prefs）。 */
    @Synchronized
    fun add(record: TransferRecord) {
        cache.add(0, record)
        while (cache.size > MAX_RECORDS) cache.removeAt(cache.size - 1)
        persist()
        DiagLogger.log(
            TAG,
            "新增传输记录：${record.status} ${record.fileCount} 文件 ${record.totalBytes}B（共 ${cache.size} 条，上限 $MAX_RECORDS）",
        )
    }

    /** 从 prefs 加载（损坏/缺键 → 空列表；单条损坏跳过不影响整体）。 */
    private fun load() {
        cache.clear()
        val raw = prefs.getString(KEY_RECORDS, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                fromJson(obj)?.let { cache.add(it) }
            }
            if (cache.size > MAX_RECORDS) {
                while (cache.size > MAX_RECORDS) cache.removeAt(cache.size - 1)
            }
            DiagLogger.log(TAG, "加载传输记录：${cache.size} 条（持久化 JSON）")
        } catch (e: Exception) {
            // 存储损坏：清空重来（不崩溃；后续 add 重建）
            DiagLogger.log(TAG, "传输记录 JSON 解析失败，重置为空: ${e.javaClass.simpleName} ${e.message}")
            prefs.edit().remove(KEY_RECORDS).apply()
        }
    }

    /** 全量持久化（缓存即倒序；直接整体写回，避免逐条增量与乱序）。 */
    private fun persist() {
        val arr = JSONArray()
        for (r in cache) arr.put(toJson(r))
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    // ---------- JSON 序列化 ----------

    private fun toJson(r: TransferRecord): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("peerAlias", r.peerAlias)
        put("isReceive", r.isReceive)
        put("fileCount", r.fileCount)
        put("totalBytes", r.totalBytes)
        put("status", r.status.name)
        put("startedAt", r.startedAt)
        put("endedAt", r.endedAt)
        r.durationMs?.let { put("durationMs", it) }
        r.peerSpeedBps?.let { put("peerSpeedBps", it) }
        r.dirPath?.let { put("dirPath", it) }
        r.failReason?.let { put("failReason", it) }
        val filesArr = JSONArray()
        for (f in r.files) {
            filesArr.put(
                JSONObject().apply {
                    put("name", f.name)
                    put("bytes", f.bytes)
                    put("ok", f.ok)
                },
            )
        }
        put("files", filesArr)
    }

    /** 反序列化（关键字段缺失/类型异常返回 null，调用方跳过该条）。 */
    private fun fromJson(o: JSONObject): TransferRecord? = try {
        val id = o.getString("id").ifBlank { return null }
        val files = ArrayList<TransferFileInfo>()
        val filesArr = o.optJSONArray("files")
        if (filesArr != null) {
            for (i in 0 until filesArr.length()) {
                val f = filesArr.optJSONObject(i) ?: continue
                files.add(
                    TransferFileInfo(
                        name = f.optString("name", ""),
                        bytes = f.optLong("bytes", 0L),
                        ok = f.optBoolean("ok", false),
                    ),
                )
            }
        }
        val statusName = o.optString("status", "")
        val status = try {
            TransferStatus.valueOf(statusName)
        } catch (e: Exception) {
            TransferStatus.OK // 未知状态回落成功（容错；不丢整条）
        }
        TransferRecord(
            id = id,
            peerAlias = o.optString("peerAlias", ""),
            isReceive = o.optBoolean("isReceive", false),
            fileCount = o.optInt("fileCount", files.size),
            totalBytes = o.optLong("totalBytes", 0L),
            status = status,
            startedAt = o.optLong("startedAt", 0L),
            endedAt = o.optLong("endedAt", 0L),
            durationMs = if (o.has("durationMs")) o.optLong("durationMs", 0L) else null,
            peerSpeedBps = if (o.has("peerSpeedBps")) o.optLong("peerSpeedBps", 0L) else null,
            dirPath = if (o.has("dirPath")) o.optString("dirPath", null) else null,
            files = files,
            failReason = if (o.has("failReason")) o.optString("failReason", null) else null,
        )
    } catch (e: Exception) {
        DiagLogger.log(TAG, "单条传输记录反序列化失败（跳过）: ${e.javaClass.simpleName} ${e.message}")
        null
    }
}
