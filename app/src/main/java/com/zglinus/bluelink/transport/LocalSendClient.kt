package com.zglinus.bluelink.transport

import android.os.Build
import com.zglinus.bluelink.diag.DiagLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * LocalSend v2 协议 HTTP 发送端（C 包传输：发送侧，T2）。
 *
 * 对目标 `http://<peerIp>:<port>`（默认 LocalSend 标准端口 53317，与
 * [com.zglinus.bluelink.ble.Constants.DEFAULT_TCP_PROBE_PORT] 一致）执行 v2 发送流：
 *   1. `POST /api/v2/prepare-upload`：JSON 声明本机 info
 *      `{"info":{"alias","version":"2.0.0","deviceModel","fingerprint":"bluelink"},
 *      "files":[{"id","fileName","size","mimeType"}],"sessionId":"<uuid>"}`，
 *      服务端回 `sessionId`（响应缺省时回退用本机生成值）；
 *   2. 逐文件 `POST /api/v2/upload?sessionId=..&fileId=..`：multipart/form-data（随机 boundary，
 *      part 名 `file`，`Content-Type: application/octet-stream`），文件体经 [SendFile.input]
 *      懒打开流直通 [HttpURLConnection.outputStream] 分块流式写出（64KB/块，不整块加载进内存），
 *      每写一块触发一次 [onProgress]；
 *   3. 全部完成 → 每文件 [onFileDone]、全量 [onAllDone]；任一步异常 → [onError] +
 *      尽力 `POST /api/v2/cancel`；用户 [cancel] → [onCancelled] + 尽力 cancel。
 *
 * 纯 Kotlin + java.net（HttpURLConnection），无第三方 HTTP/JSON 依赖
 * （JSON 用 Android 内置 org.json，与工程「禁第三方 JSON 库」约定一致）。
 *
 * 超时：connect/read 各 15s（[CONNECT_TIMEOUT_MS]/[READ_TIMEOUT_MS]）。read 超时仅作用于
 * 服务端响应解析阶段；大文件请求体用 [HttpURLConnection.setFixedLengthStreamingMode] 定长
 * 流式写出——整 multipart 体长度（头 + 文件 + 尾）一次性声明，逐块写出不缓冲，
 * 文件体写出过程不走读、不受 read 超时误杀。
 *
 * 线程模型：send() 为阻塞调用，应在后台线程执行（调用方负责，如引擎/协程/线程池）；
 * 回调均在 send() 所在线程同步触发。cancel() 可随时从任意线程调用（置标志 + 断开进行中
 * 连接以中断阻塞中的写/读）。日志只记文件名与元数据，文件内容不回显。
 */
data class SendFile(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val input: () -> InputStream,
)

class LocalSendClient(
    private val peerIp: String,
    private val port: Int = 53317,
    val alias: String,
) {

    private companion object {
        const val TAG = "LocalSendClient"

        /** 连接建立超时（ms）。 */
        const val CONNECT_TIMEOUT_MS = 15_000

        /** 响应解析超时（ms）；仅作用于读服务端响应阶段，不影响文件体写出。 */
        const val READ_TIMEOUT_MS = 15_000

        /** 流式写出分块：每写一块（≤64KB）触发一次进度回调。 */
        const val CHUNK_SIZE = 64 * 1024

        /** prepare-upload info.version（LocalSend v2 协议版本）。 */
        const val PROTOCOL_VERSION = "2.0.0"

        /** prepare-upload info.fingerprint（本 App 固定指纹标识）。 */
        const val FINGERPRINT = "bluelink"
    }

    // ============ 回调接口 ============

    /** 进度回调：每写完一个 64KB 分块触发（sent 为当前文件已写出字节数）。 */
    var onProgress: ((fileIndex: Int, name: String, sent: Long, total: Long) -> Unit)? = null

    /** 单文件上传完成回调（服务端 2xx 确认后触发）。 */
    var onFileDone: ((fileIndex: Int, name: String) -> Unit)? = null

    /** 全部文件上传完成回调（totalBytes 为文件清单总大小）。 */
    var onAllDone: ((totalBytes: Long) -> Unit)? = null

    /** 异常回调（用户取消除外；message 含异常类名 + 详情）。 */
    var onError: ((name: String, message: String) -> Unit)? = null

    /** 用户取消回调（send 返回前触发）。 */
    var onCancelled: (() -> Unit)? = null

    // ============ 内部状态 ============

    /** 取消标志（cancel() 置位；send() 启动时重置）。 */
    @Volatile
    private var cancelled = false

    /** 进行中请求连接（cancel() 断开它以中断阻塞写/读）。 */
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /** 当前出错文件（异常上报用；prepare 阶段为空串）。 */
    private var currentName: String = ""

    /**
     * 执行 LocalSend v2 发送流（阻塞，需后台线程调用）：
     * prepare-upload → 逐文件 multipart upload → onFileDone/onAllDone。
     * 任一步失败 → 尽力发 cancel API + [onError]；用户 [cancel] → 尽力发 cancel API + [onCancelled]。
     */
    fun send(files: List<SendFile>) {
        cancelled = false // 新一轮发送重置取消标志（send 之前调 cancel 视为 no-op）
        currentName = ""
        DiagLogger.log(TAG, "send 开始：peer=$peerIp:$port files=${files.size} alias=$alias")
        if (files.isEmpty()) {
            DiagLogger.log(TAG, "send 空文件清单：直接完成（onAllDone(0)）")
            onAllDone?.invoke(0L)
            return
        }
        val localSessionId = UUID.randomUUID().toString()
        try {
            // 1. prepare-upload：协商 sessionId（服务端回执优先，缺省回退本机值）
            val sessionId = prepareUpload(files, localSessionId)
            // 2. 逐文件 upload
            for ((index, file) in files.withIndex()) {
                checkCancelled()
                currentName = file.name
                uploadFile(sessionId, index, file)
                DiagLogger.log(TAG, "文件完成：file[$index] name=${file.name}")
                onFileDone?.invoke(index, file.name)
            }
            // 3. 全部完成
            val total = files.sumOf { it.size }
            DiagLogger.log(TAG, "send 全部完成：files=${files.size} total=${total}B")
            onAllDone?.invoke(total)
        } catch (e: InterruptedIOException) {
            handleAbort(localSessionId, isCancel = true, e)
        } catch (e: Exception) {
            handleAbort(localSessionId, isCancel = cancelled, e)
        }
    }

    /** 用户取消：置标志 + 断开进行中连接（中断阻塞写/读），后续由 send 收尾（尽力发 cancel API）。 */
    fun cancel() {
        cancelled = true
        DiagLogger.log(TAG, "cancel：置取消标志 + 断开进行中连接（中断阻塞写/读）")
        activeConnection?.disconnect()
    }

    // ============ 步骤 1：prepare-upload ============

    private fun prepareUpload(files: List<SendFile>, localSessionId: String): String {
        DiagLogger.log(TAG, "prepare-upload：POST /api/v2/prepare-upload files=${files.size} sessionId=$localSessionId")
        val info = JSONObject()
            .put("alias", alias)
            .put("version", PROTOCOL_VERSION)
            .put("deviceModel", Build.MODEL)
            .put("fingerprint", FINGERPRINT)
        val filesArr = JSONArray()
        for (f in files) {
            filesArr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("fileName", f.name)
                    .put("size", f.size)
                    .put("mimeType", f.mimeType),
            )
        }
        val body = JSONObject()
            .put("info", info)
            .put("files", filesArr)
            .put("sessionId", localSessionId)
            .toString()
        // 仅文件名/元数据，无文件内容
        DiagLogger.log(TAG, "prepare-upload 请求体（截断）：${body.take(400)}")
        val conn = openConnection("/api/v2/prepare-upload")
        activeConnection = conn
        try {
            conn.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size.toLong())
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val resp = readResponseBody(conn, code)
            if (code !in 200..299) {
                throw IOException("prepare-upload 服务端错误 HTTP $code：${resp.take(200)}")
            }
            val returned = JSONObject(resp).optString("sessionId", "").ifBlank { localSessionId }
            DiagLogger.log(TAG, "prepare-upload 成功 HTTP $code：sessionId=$returned")
            return returned
        } finally {
            activeConnection = null
            conn.disconnect()
        }
    }

    // ============ 步骤 2：multipart upload ============

    private fun uploadFile(sessionId: String, fileIndex: Int, file: SendFile) {
        val boundary = "----BluelinkBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val safeName = sanitizeHeaderValue(file.name)
        // multipart 构造：随机 boundary；part 名 "file"；Content-Type: application/octet-stream
        val preamble = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "\r\n"
        val trailer = "\r\n--$boundary--\r\n"
        val preambleBytes = preamble.toByteArray(Charsets.UTF_8)
        val trailerBytes = trailer.toByteArray(Charsets.UTF_8)
        val totalLength = preambleBytes.size + file.size + trailerBytes.size

        DiagLogger.log(
            TAG,
            "upload 开始：file[$fileIndex] name=${file.name} size=${file.size}B " +
                "boundary=${boundary.takeLast(8)} multipart 定长=${totalLength}B",
        )
        val query = "sessionId=${urlEncode(sessionId)}&fileId=${urlEncode(file.id)}"
        val conn = openConnection("/api/v2/upload?$query")
        activeConnection = conn
        try {
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // 定长流式模式：Content-Length=整 multipart 体（头+文件+尾），请求体逐块写出不缓冲；
            // 大文件不因 read 超时误杀——read 超时仅作用于服务端响应解析，文件体写出不走读。
            conn.setFixedLengthStreamingMode(totalLength)
            conn.outputStream.use { out ->
                out.write(preambleBytes)
                val buf = ByteArray(CHUNK_SIZE)
                var sent = 0L
                file.input().use { input -> // 懒打开流：直到本文件写出前才打开
                    while (true) {
                        checkCancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            out.write(buf, 0, n)
                            sent += n
                            onProgress?.invoke(fileIndex, file.name, sent, file.size)
                        }
                    }
                }
                if (sent != file.size) {
                    throw IOException("文件长度不一致：声明 ${file.size}B 实际读 $sent B（name=${file.name}）")
                }
                out.write(trailerBytes)
                out.flush()
            }
            val code = conn.responseCode
            val resp = readResponseBody(conn, code)
            if (code !in 200..299) {
                throw IOException("upload 服务端错误 HTTP $code：${resp.take(200)}")
            }
            DiagLogger.log(TAG, "upload 完成：file[$fileIndex] name=${file.name} HTTP $code")
        } finally {
            activeConnection = null
            conn.disconnect()
        }
    }

    // ============ 收尾 / 取消 / 工具 ============

    /** 中止统一收尾：尽力发 cancel API；取消 → onCancelled，否则 → onError。 */
    private fun handleAbort(sessionId: String, isCancel: Boolean, e: Exception) {
        val stage = currentName.ifBlank { "<prepare-upload>" }
        DiagLogger.log(
            TAG,
            "send 中止：cancelled=$isCancel stage=$stage err=${e.javaClass.simpleName}: ${e.message}",
        )
        sendCancel(sessionId) // 尽力
        if (isCancel) {
            onCancelled?.invoke()
        } else {
            onError?.invoke(stage, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 尽力 `POST /api/v2/cancel`（失败仅记日志，不抛）。 */
    private fun sendCancel(sessionId: String) {
        DiagLogger.log(TAG, "cancel API：POST /api/v2/cancel（尽力）sessionId=$sessionId")
        try {
            val body = JSONObject().put("sessionId", sessionId).toString()
            val conn = openConnection("/api/v2/cancel")
            activeConnection = conn
            try {
                conn.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size.toLong())
                conn.outputStream.use { it.write(bytes) }
                DiagLogger.log(TAG, "cancel API 已发送 HTTP ${conn.responseCode}")
            } finally {
                activeConnection = null
                conn.disconnect()
            }
        } catch (e: Exception) {
            DiagLogger.log(TAG, "cancel API 尽力发送失败（忽略）: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 取消检查：标志置位即抛 [InterruptedIOException]（中断当前写）。 */
    private fun checkCancelled() {
        if (cancelled) {
            throw InterruptedIOException("LocalSend 传输已取消")
        }
    }

    private fun openConnection(path: String): HttpURLConnection {
        val conn = URL("http://$peerIp:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.doInput = true
        conn.useCaches = false
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        return conn
    }

    /** 读取响应体（2xx 走 inputStream，否则走 errorStream；仅响应解析阶段，受 read 超时约束）。 */
    private fun readResponseBody(conn: HttpURLConnection, code: Int): String {
        val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** multipart 头防注入：文件名中的 CR/LF/引号替换为下划线。 */
    private fun sanitizeHeaderValue(s: String): String =
        s.replace("\r", "_").replace("\n", "_").replace("\"", "_")

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
