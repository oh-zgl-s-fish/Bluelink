package com.zglinus.bluelink.transport

import android.content.Context
import android.os.Build
import com.zglinus.bluelink.diag.DiagLogger
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * LocalSend v2 HTTP 文件传输服务端（纯 JDK 手写，无第三方库；JSON 用 Android 内置 org.json）。
 *
 * 一期目标：Bluelink 两端自通（与官方 LocalSend 互通留待二期 HTTPS）。
 *
 * ## 协议形状（LocalSend v2）
 * - `GET  /api/v2/info`            → 200 `{"alias","version":"2.0.0","deviceModel","fingerprint":"bluelink"}`
 * - `POST /api/v2/prepare-upload`  → body JSON `{"info","files":[{"id","fileName","size","mimeType"}],"sessionId"}` → 200 `{"sessionId"}`
 * - `POST /api/v2/upload?sessionId=..&fileId=..`  body multipart（part 名 `file`）→ 流式写盘 → 200 `{"size":n}`
 * - `GET  /api/v2/upload?...`      resume 形状：一期不支持断点，直接 404（注释见 [handleResumeNotSupported]）
 * - `POST /api/v2/cancel`          body JSON 含 sessionId → 删除会话目录 → 200；其它路径 404
 *
 * ## 线程模型
 * - 后台 accept 线程：`ServerSocket(53317)` 绑定 0.0.0.0，accept 循环；
 * - 每个连接一个处理任务，提交给固定线程池：**上限 4 线程**，超出排队（有界队列 16），队列满直接关闭连接；
 * - `stop()` 关闭监听 + shutdownNow + 关闭所有活动 socket。
 *
 * ## 写盘策略
 * - multipart 体流式解析：`PushbackInputStream` + 分块边界扫描，**不整文件缓冲到内存**；
 * - `FileOutputStream` 直接写 `context.filesDir/localsend/<sessionId>/<fileName>`；
 * - 严格 ≤ 声明 size 写入：超出部分丢弃（drain），不足视为失败并删除残件。
 *
 * ## 安全
 * - 文件名拒绝空、`..`、`/`、`\`、NUL、超长；落盘前再 canonical 校验父目录仍等于会话目录；
 * - sessionId 仅允许 `[A-Za-z0-9_-]`（目录名防穿越）；请求行/头/JSON 体均有字节上限防内存滥用；
 * - 所有服务器异常 catch → 500 + [DiagLogger]；日志不回显密码（协议无密码字段）与完整路径
 *   （启动时仅打印收件目录，供排障）。
 */
class LocalSendServer(
    context: Context,
    private val alias: String,
) {
    private val appContext = context.applicationContext

    /** 收件根目录：`context.filesDir/localsend/`（会话子目录：`localsend/<sessionId>/`）。 */
    private val rootDir = File(appContext.filesDir, "localsend")

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** 当前活动连接（stop 时统一关闭，解除 worker 阻塞）。 */
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    /** worker 线程序号（共享计数器，避免每次新建导致序号恒为 1）。 */
    private val threadSeq = AtomicInteger()

    /** 固定 4 线程 + 有界队列：连接超限排队，队列满拒绝。stop() 后 shutdown，重启时重建（见 [newWorkerExecutor]）。 */
    @Volatile
    private var executor: ThreadPoolExecutor = newWorkerExecutor()

    /** 新建 worker 线程池（stop() shutdown 后 start() 重启时重建，保证服务可随组网多次启停——T3 生命周期接线需要）。 */
    private fun newWorkerExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
        MAX_CONNECTIONS, MAX_CONNECTIONS,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(QUEUE_CAPACITY),
        ThreadFactory { r ->
            Thread(r, "localsend-worker-${threadSeq.incrementAndGet()}").apply { isDaemon = true }
        },
    )

    /** 进行中的会话：sessionId → 会话状态（文件元信息 + 实时接收进度）。 */
    private val activeSessions = ConcurrentHashMap<String, SessionState>()

    /**
     * 文件接收完成回调（T3 只读扩展字段，不改变 T1 构造/方法形状）：单文件完整落盘校验通过后触发
     * （sessionId=会话 ID，fileName=安全文件名，path=落盘绝对路径）。
     * 触发线程：worker 线程（非主线程）——调用方需自行切回主线程再更新 UI。
     */
    var onFileReceived: ((sessionId: String, fileName: String, path: String) -> Unit)? = null

    /** 单文件进度视图（供 UI/日志展示）。多文件会话聚合：size/received 为总和，fileName 拼接。 */
    data class SessionProgress(val fileName: String, val size: Long, val received: Long)

    private class FileMeta(
        val id: String,
        val fileName: String,
        val size: Long,
        @Suppress("unused") val mimeType: String,
    ) {
        @Volatile
        var received: Long = 0L

        @Volatile
        var inProgress: Boolean = false
    }

    private class SessionState(val sessionId: String, val dir: File) {
        val files = ConcurrentHashMap<String, FileMeta>()
    }

    private class ProgressState {
        var lastPct = -1
        var lastLogged = 0L
    }

    private class CopyResult(val bytes: Long, val found: Boolean)

    /** 业务/客户端错误 → 对应 HTTP 状态（区别于服务器异常 500）。 */
    private class HttpException(val status: Int, message: String) : Exception(message)

    private class Response(val status: Int, val contentType: String, val body: ByteArray) {
        companion object {
            fun json(status: Int, body: String) =
                Response(status, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))
        }
    }

    /** 启动：绑定 0.0.0.0:53317 并开始 accept 循环。失败返回 false（已记录 DiagLogger）。 */
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        running = true
        return try {
            // T3：stop() 已 shutdown 旧 worker 线程池 → 重启时重建（服务可随组网多次启停）
            if (executor.isShutdown) {
                executor = newWorkerExecutor()
                DiagLogger.log(TAG, "LocalSendServer 重启：已重建 worker 线程池")
            }
            // LocalSend 标准端口（与 ble/Constants.DEFAULT_TCP_PROBE_PORT 对齐，字面量便于 grep 与排查）
            serverSocket = ServerSocket(53317, 16, InetAddress.getByName("0.0.0.0"))
            DiagLogger.log(TAG, "LocalSendServer 已启动，监听 0.0.0.0:53317（收件目录: ${rootDir.absolutePath}）")
            acceptThread = Thread({ acceptLoop() }, "localsend-accept").apply { isDaemon = true }
            acceptThread?.start()
            true
        } catch (e: Exception) {
            running = false
            DiagLogger.log(TAG, "启动失败: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /** 停止：关监听 → 中断线程池 → 关闭活动连接。已收文件保留在磁盘，不清除。 */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        executor.shutdownNow()
        for (s in activeSockets) {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
        activeSockets.clear()
        DiagLogger.log(TAG, "LocalSendServer 已停止")
    }

    val isRunning: Boolean
        get() = running && serverSocket?.isClosed == false

    /** 进行中会话快照（sessionId → 文件名/总大小/已接收），供 UI/日志。 */
    fun getActiveSessions(): Map<String, SessionProgress> {
        val out = LinkedHashMap<String, SessionProgress>()
        for ((id, s) in activeSessions) {
            val files = s.files.values.toList()
            val size = files.sumOf { it.size }
            val received = files.sumOf { it.received }
            val name = when {
                files.isEmpty() -> ""
                files.size == 1 -> files[0].fileName
                else -> "(${files.size} 个文件) " + files.joinToString(", ") { it.fileName }
            }
            out[id] = SessionProgress(name, size, received)
        }
        return out
    }

    // ------------------------------------------------------------------ accept 循环

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket?.accept()
            } catch (e: IOException) {
                if (running) DiagLogger.log(TAG, "accept 异常: ${e.message}")
                break
            } ?: break
            activeSockets.add(socket)
            try {
                executor.execute { handleConnection(socket) }
            } catch (e: RejectedExecutionException) {
                activeSockets.remove(socket)
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                DiagLogger.log(TAG, "连接队列已满，拒绝连接（max=$MAX_CONNECTIONS queue=$QUEUE_CAPACITY）")
            }
        }
    }

    // ------------------------------------------------------------------ 连接处理

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 8 * 1024)

            // 请求首行："METHOD SP 目标 SP HTTP/x.y"（行上限防内存滥用）
            val requestLine = readLine(input, MAX_REQUEST_LINE) ?: return
            val parts = requestLine.split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/")) {
                writeResponse(output, Response.json(400, jsonError("bad request line")))
                return
            }
            val headers = readHeaders(input, MAX_HEADER_BYTES)
                ?: run {
                    writeResponse(output, Response.json(400, jsonError("bad headers")))
                    return
                }

            val resp = try {
                dispatch(parts[0].uppercase(Locale.US), parts[1], headers, input)
            } catch (e: HttpException) {
                DiagLogger.log(TAG, "请求被拒 ${e.status}: ${e.message}")
                Response.json(e.status, jsonError(e.message ?: "error"))
            } catch (e: Exception) {
                // 服务器异常统一 500 + DiagLogger
                DiagLogger.log(TAG, "请求处理异常: ${e.javaClass.simpleName} ${e.message}")
                Response.json(500, jsonError("internal error"))
            }
            writeResponse(output, resp)
            output.flush()
        } catch (e: Exception) {
            DiagLogger.log(TAG, "连接处理异常: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            activeSockets.remove(socket)
        }
    }

    /** 路由：未知路径 404；路径存在但方法不对 405。 */
    private fun dispatch(
        method: String,
        target: String,
        headers: Map<String, String>,
        input: InputStream,
    ): Response {
        val path = target.substringBefore('?')
        val query = parseQuery(target)
        return when {
            method == "GET" && path == "/api/v2/info" -> handleInfo()
            method == "POST" && path == "/api/v2/prepare-upload" -> handlePrepareUpload(readJsonBody(input, headers))
            method == "POST" && path == "/api/v2/upload" -> handleUpload(input, headers, query)
            method == "GET" && path == "/api/v2/upload" -> handleResumeNotSupported()
            method == "POST" && path == "/api/v2/cancel" -> handleCancel(readJsonBody(input, headers))
            path == "/api/v2/info" || path == "/api/v2/prepare-upload" ||
                path == "/api/v2/upload" || path == "/api/v2/cancel" ->
                Response.json(405, jsonError("method not allowed"))
            else -> Response.json(404, jsonError("not found"))
        }
    }

    // ------------------------------------------------------------------ 端点

    /** `GET /api/v2/info`：设备信息（alias 由引擎/应用经构造函数传入）。 */
    private fun handleInfo(): Response {
        val o = JSONObject()
        o.put("alias", alias.ifBlank { Build.MODEL })
        o.put("version", "2.0.0")
        o.put("deviceModel", Build.MODEL)
        o.put("fingerprint", "bluelink")
        return Response.json(200, o.toString())
    }

    /**
     * `POST /api/v2/prepare-upload`：校验（size>0 / 文件名安全 / sessionId 安全）→
     * 建会话目录 `filesDir/localsend/<sessionId>/` → 200 `{"sessionId":"..."}`。
     */
    private fun handlePrepareUpload(body: String): Response {
        val o = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw HttpException(400, "invalid json")
        }
        val sessionId = o.optString("sessionId", "").trim()
        if (!isSafeSessionId(sessionId)) throw HttpException(400, "invalid sessionId")

        val filesArr = o.optJSONArray("files") ?: throw HttpException(400, "missing files")
        if (filesArr.length() == 0) throw HttpException(400, "empty files")

        val metas = ArrayList<FileMeta>(filesArr.length())
        for (i in 0 until filesArr.length()) {
            val f = filesArr.optJSONObject(i) ?: throw HttpException(400, "bad file entry")
            val id = f.optString("id", "").trim()
            val name = f.optString("fileName", "").trim()
            val size = f.optLong("size", 0L)
            val mime = f.optString("mimeType", "application/octet-stream")
            if (id.isEmpty() || !isSafeFileName(name)) throw HttpException(400, "invalid file entry")
            if (size <= 0L) throw HttpException(400, "file size must be > 0")
            metas.add(FileMeta(id, name, size, mime))
        }

        val dir = File(rootDir, sessionId)
        if (dir.exists()) dir.deleteRecursively()
        if (!dir.mkdirs()) throw HttpException(500, "cannot create session dir")

        val session = SessionState(sessionId, dir)
        for (m in metas) session.files[m.id] = m
        activeSessions[sessionId] = session
        DiagLogger.log(TAG, "新建会话 $sessionId 文件数=${metas.size}")

        val resp = JSONObject()
        resp.put("sessionId", sessionId)
        return Response.json(200, resp.toString())
    }

    /**
     * `POST /api/v2/upload?sessionId=..&fileId=..`：multipart（part 名 `file`）流式写盘。
     * 已存在同名 → FileOutputStream 截断覆盖并返回 200。
     */
    private fun handleUpload(
        input: InputStream,
        headers: Map<String, String>,
        query: Map<String, String>,
    ): Response {
        val sessionId = query["sessionId"] ?: throw HttpException(404, "session not found")
        val fileId = query["fileId"] ?: throw HttpException(404, "file not found")
        val session = activeSessions[sessionId] ?: throw HttpException(404, "session not found")
        val meta = session.files[fileId] ?: throw HttpException(404, "file not found")
        if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
            throw HttpException(400, "chunked not supported")
        }
        val boundary = extractBoundary(headers["content-type"] ?: "")
            ?: throw HttpException(400, "missing boundary")

        val target = File(session.dir, meta.fileName)
        // 防御：落盘前 canonical 校验父目录 == 会话目录（防穿越/符号链接逃逸）
        try {
            val canonicalParent = target.canonicalFile.parentFile?.canonicalFile
            if (canonicalParent != session.dir.canonicalFile) {
                throw HttpException(400, "unsafe file path")
            }
        } catch (e: HttpException) {
            throw e
        } catch (e: Exception) {
            throw HttpException(500, "path check failed")
        }

        // 同一文件并发上传保护：inProgress 标记 → 409
        synchronized(meta) {
            if (meta.inProgress) throw HttpException(409, "file already uploading")
            meta.inProgress = true
        }

        try {
            DiagLogger.log(TAG, "文件开始 会话=$sessionId 文件=${meta.fileName} 大小=${meta.size}")
            val written = receiveMultipartFile(input, boundary, sessionId, meta, target)
            if (written != meta.size) {
                DiagLogger.log(TAG, "文件不完整 会话=$sessionId 文件=${meta.fileName} 收到=$written 期望=${meta.size}")
                target.delete()
                throw HttpException(500, "file incomplete")
            }
            meta.received = written
            DiagLogger.log(TAG, "文件完成 会话=$sessionId 文件=${meta.fileName} 大小=$written")
            // T3：文件接收完成 → 通知上层（Engine 切主线程更新接收列表）；回调异常不影响传输结果
            try {
                onFileReceived?.invoke(sessionId, meta.fileName, target.absolutePath)
            } catch (e: Exception) {
                DiagLogger.log(TAG, "onFileReceived 回调异常（忽略，不影响传输）: ${e.javaClass.simpleName} ${e.message}")
            }
            maybeCompleteSession(session)
            val resp = JSONObject()
            resp.put("size", written)
            return Response.json(200, resp.toString())
        } catch (e: HttpException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            target.delete()
            DiagLogger.log(TAG, "上传失败 会话=$sessionId 文件=${meta.fileName}: ${e.javaClass.simpleName} ${e.message}")
            throw HttpException(500, "upload failed")
        } finally {
            synchronized(meta) { meta.inProgress = false }
        }
    }

    /**
     * `GET /api/v2/upload?sessionId=..&fileId=..`（resume 形状）：
     * 一期不支持断点续传，直接 404 简单化——官方 LocalSend 此处返回 `{"missingParts":[...]}`，
     * Bluelink v1 无断点需求，客户端误用即 404 提示，二期再做断点。
     */
    private fun handleResumeNotSupported(): Response =
        Response.json(404, jsonError("resume not supported in v1"))

    /** `POST /api/v2/cancel`：body 含 sessionId → 删除会话目录 → 200。 */
    private fun handleCancel(body: String): Response {
        val o = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw HttpException(400, "invalid json")
        }
        val sessionId = o.optString("sessionId", "").trim()
        if (sessionId.isEmpty()) throw HttpException(400, "missing sessionId")
        val session = activeSessions.remove(sessionId) ?: throw HttpException(404, "session not found")
        session.dir.deleteRecursively()
        DiagLogger.log(TAG, "会话已取消并清理: $sessionId")
        val resp = JSONObject()
        resp.put("ok", true)
        return Response.json(200, resp.toString())
    }

    // ------------------------------------------------------------------ multipart 流式解析

    /**
     * 解析 multipart 请求体，把 part 名 `file` 的文件体**流式**写入 [target]。
     *
     * 结构：`--<boundary>\r\n` 头部 `\r\n\r\n` 文件体 `\r\n--<boundary>`（`--` 结束 / `\r\n` 下一 part）。
     * 用 [PushbackInputStream] 分块扫描边界：读一块 → 找分隔符 → 写前缀 → 把尾部回推，
     * 全程只缓冲 ≤64KB 分块，不整文件进内存。
     *
     * 返回实际写盘字节数；EOF 未遇分隔符抛 [IOException]。
     */
    private fun receiveMultipartFile(
        input: InputStream,
        boundaryValue: String,
        sessionId: String,
        meta: FileMeta,
        target: File,
    ): Long {
        val firstDelim = ("--$boundaryValue").toByteArray(Charsets.UTF_8)
        val partDelim = ("\r\n--$boundaryValue").toByteArray(Charsets.UTF_8)
        val body = PushbackInputStream(input, PUSHBACK_BUFFER)

        // 1) 跳过 preamble，定位首个 boundary
        val first = copyUntilDelimiter(body, firstDelim, null, Long.MAX_VALUE) { }
        if (!first.found) throw IOException("multipart: missing first boundary")
        if (!expectCrlf(body)) throw IOException("multipart: bad first boundary line")

        var fileWritten = 0L
        var filePartSeen = false
        val prog = ProgressState()

        // 2) 逐 part：文件 part 写盘，其余 part 丢弃
        while (true) {
            val partHeaders = readHeaders(body, MAX_PART_HEADER_BYTES)
                ?: throw IOException("multipart: bad part headers")
            val disp = partHeaders["content-disposition"] ?: ""
            val isFilePart = dispositionParam(disp, "name") == "file"

            val res: CopyResult
            if (isFilePart) {
                filePartSeen = true
                // 流式写盘：FileOutputStream 直写，无整文件缓冲；按 meta.size 上限截写
                FileOutputStream(target).use { out ->
                    res = copyUntilDelimiter(body, partDelim, out, meta.size) { w ->
                        meta.received = w
                        val pct = if (meta.size > 0) ((w.toDouble() / meta.size) * 100).toInt() else 100
                        // 每 10% 或 ≥5MB 记一次进度
                        if (pct - prog.lastPct >= PROGRESS_STEP_PCT || w - prog.lastLogged >= PROGRESS_STEP_BYTES) {
                            prog.lastPct = pct
                            prog.lastLogged = w
                            DiagLogger.log(TAG, "上传进度 会话=$sessionId 文件=${meta.fileName} $w/${meta.size} ($pct%)")
                        }
                    }
                }
                fileWritten = res.bytes
            } else {
                res = copyUntilDelimiter(body, partDelim, null, Long.MAX_VALUE) { }
            }
            if (!res.found) throw IOException("multipart: EOF before boundary")

            // 3) 分隔符之后：`--` 结束（含可选尾 \r\n），`\r\n` 进入下一 part
            val b1 = body.read()
            val b2 = body.read()
            when {
                b1 == '-'.code && (b2 == '-'.code || b2 == -1) -> {
                    consumeOptionalCrlf(body)
                    break
                }
                b1 == '\r'.code && b2 == '\n'.code -> Unit
                else -> throw IOException("multipart: bad boundary terminator")
            }
        }

        if (!filePartSeen) throw IOException("multipart: no file part")
        return fileWritten
    }

    /**
     * 从 [body] 流式读到分隔符 [delim] 为止：命中则把分隔符之后的字节回推并返回
     * `CopyResult(bytes=已写字节, found=true)`；EOF 未命中返回 `found=false`。
     *
     * 每块最多写 [max] 字节（超出丢弃即 drain，保证“不超 size 写”）；[out] 为 null 时纯丢弃（跳过 part）。
     */
    private fun copyUntilDelimiter(
        body: PushbackInputStream,
        delim: ByteArray,
        out: OutputStream?,
        max: Long,
        onProgress: (Long) -> Unit,
    ): CopyResult {
        val buf = ByteArray(IO_BUFFER)
        var written = 0L
        while (true) {
            val n = body.read(buf)
            if (n < 0) return CopyResult(written, false)

            val idx = indexOf(buf, 0, n, delim)
            if (idx >= 0) {
                written = writeCapped(out, buf, 0, idx, max, written)
                val after = n - (idx + delim.size)
                if (after > 0) body.unread(buf, idx + delim.size, after)
                onProgress(written)
                return CopyResult(written, true)
            }

            // 未命中：可安全落盘除最后 (delim.size-1) 字节外的全部，尾部回推以跨块匹配
            val keep = (delim.size - 1).coerceAtMost(n)
            val flush = n - keep
            if (flush > 0) {
                written = writeCapped(out, buf, 0, flush, max, written)
                if (keep > 0) body.unread(buf, flush, keep)
            } else {
                body.unread(buf, 0, n)
            }
            onProgress(written)
        }
    }

    /** 写出但不越过 [max]：剩余配额为 0 后不再写（继续扫描 drain）。返回累计已写字节。 */
    private fun writeCapped(out: OutputStream?, buf: ByteArray, off: Int, len: Int, max: Long, written: Long): Long {
        if (out == null || len <= 0) return written
        val remaining = max - written
        if (remaining <= 0) return written
        val take = len.coerceAtMost(if (remaining > Int.MAX_VALUE) Int.MAX_VALUE else remaining.toInt())
        out.write(buf, off, take)
        return written + take
    }

    /** 朴素字节 indexOf（分隔符长而随机，实际近线性）。 */
    private fun indexOf(data: ByteArray, from: Int, to: Int, needle: ByteArray): Int {
        if (needle.isEmpty()) return from
        val lastStart = to - needle.size
        if (lastStart < from) return -1
        for (i in from..lastStart) {
            var j = 0
            while (j < needle.size && data[i + j] == needle[j]) j++
            if (j == needle.size) return i
        }
        return -1
    }

    private fun expectCrlf(body: PushbackInputStream): Boolean {
        val b1 = body.read()
        val b2 = body.read()
        return b1 == '\r'.code && b2 == '\n'.code
    }

    private fun consumeOptionalCrlf(body: PushbackInputStream) {
        val b1 = body.read()
        val b2 = body.read()
        if (b1 == '\r'.code && b2 == '\n'.code) return
        if (b2 != -1) body.unread(b2)
        if (b1 != -1) body.unread(b1)
    }

    /** 从 Content-Disposition 取参数（name= / filename=，支持引号与裸值）。 */
    private fun dispositionParam(disposition: String, key: String): String? {
        val quoted = Regex("""(?:^|;)\s*$key="([^"]*)"""")
        quoted.find(disposition)?.let { return it.groupValues[1] }
        val bare = Regex("""(?:^|;)\s*$key=([^\s;]+)""")
        return bare.find(disposition)?.groupValues?.get(1)
    }

    // ------------------------------------------------------------------ 基础 IO

    /** 读一行（去 \r\n）；行首 EOF 返回 null；超 [max] 抛 400。 */
    private fun readLine(input: InputStream, max: Int): String? {
        val sb = StringBuilder(128)
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (sb.isEmpty()) return null
                break
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > max) throw HttpException(400, "line too long")
        }
        return sb.toString()
    }

    /** 读 HTTP/part 头（直到空行），小写键名；总字节超 [maxBytes] 抛 400。 */
    private fun readHeaders(input: InputStream, maxBytes: Int): Map<String, String>? {
        val map = LinkedHashMap<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input, maxBytes) ?: return if (map.isEmpty()) null else map
            if (line.isEmpty()) break
            total += line.length + 2
            if (total > maxBytes) throw HttpException(400, "headers too large")
            val colon = line.indexOf(':')
            if (colon > 0) {
                map[line.substring(0, colon).trim().lowercase(Locale.US)] =
                    line.substring(colon + 1).trim()
            }
        }
        return map
    }

    /** 读 JSON 请求体（Content-Length 优先，否则读至 EOF）；超 [MAX_JSON_BODY] → 413。 */
    private fun readJsonBody(input: InputStream, headers: Map<String, String>): String {
        if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
            throw HttpException(400, "chunked not supported")
        }
        val len = headers["content-length"]?.toLongOrNull()
        val bytes = if (len != null) {
            if (len < 0 || len > MAX_JSON_BODY) throw HttpException(413, "json body too large")
            readExactly(input, len.toInt())
        } else {
            readUntilEof(input, MAX_JSON_BODY) ?: throw HttpException(413, "json body too large")
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw HttpException(400, "body truncated")
            off += r
        }
        return out
    }

    /** 读至 EOF，超过 [cap] 返回 null。 */
    private fun readUntilEof(input: InputStream, cap: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            if (out.size() + r > cap) return null
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private fun parseQuery(target: String): Map<String, String> {
        val q = target.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) pair.substring(0, idx) else pair
            val v = if (idx >= 0) pair.substring(idx + 1) else ""
            map[decode(k)] = decode(v)
        }
        return map
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    private fun extractBoundary(contentType: String): String? {
        val idx = contentType.indexOf("boundary=", ignoreCase = true)
        if (idx < 0) return null
        var v = contentType.substring(idx + "boundary=".length).trim()
        v = if (v.startsWith("\"")) v.removePrefix("\"").substringBefore("\"")
        else v.substringBefore(';').substringBefore(' ').trim()
        return v.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ 响应

    private fun writeResponse(out: OutputStream, resp: Response) {
        val reason = REASONS[resp.status] ?: "Unknown"
        val head = "HTTP/1.1 ${resp.status} $reason\r\n" +
            "Content-Type: ${resp.contentType}\r\n" +
            "Content-Length: ${resp.body.size}\r\n" +
            "Connection: close\r\n" +
            "Server: bluelink\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(resp.body)
    }

    private fun jsonError(msg: String): String = JSONObject().put("error", msg).toString()

    /** 会话内全部文件接收完毕 → 从活动会话移除（磁盘文件保留）。 */
    private fun maybeCompleteSession(session: SessionState) {
        if (session.files.values.all { it.received >= it.size }) {
            activeSessions.remove(session.sessionId)
            DiagLogger.log(TAG, "会话全部完成并移除: ${session.sessionId}")
        }
    }

    // ------------------------------------------------------------------ 安全校验

    /** sessionId 仅允许 URL 安全字符集（将作为目录名，防路径穿越）。 */
    private fun isSafeSessionId(id: String): Boolean =
        id.isNotBlank() && id.length <= 64 && SAFE_SESSION_ID.matches(id) && !id.contains("..")

    /** 文件名校验：拒空、`.`/`..`、含 `/` `\` NUL、任何 `..`、超长。 */
    private fun isSafeFileName(name: String): Boolean {
        if (name.isBlank() || name.length > 255) return false
        if (name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\') || name.contains('\u0000')) return false
        if (name.contains("..")) return false
        return true
    }

    private companion object {
        const val TAG = "LocalSendServer"

        /** LocalSend 标准端口（与 ble/Constants.DEFAULT_TCP_PROBE_PORT 一致）。 */
        const val PORT = 53317

        /** 连接处理线程上限（超出排队）。 */
        const val MAX_CONNECTIONS = 4

        /** 等待队列容量（满则拒绝连接）。 */
        const val QUEUE_CAPACITY = 16

        /** 单连接读超时：上传大文件时按块持续读，不影响；仅防挂死连接。 */
        const val SOCKET_TIMEOUT_MS = 300_000

        const val MAX_REQUEST_LINE = 8 * 1024
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_PART_HEADER_BYTES = 8 * 1024

        /** prepare-upload / cancel JSON 体上限。 */
        const val MAX_JSON_BODY = 2 * 1024 * 1024

        /** multipart 流式解析分块大小。 */
        const val IO_BUFFER = 64 * 1024

        /** PushbackInputStream 回推缓冲（≥ 分块大小 + 余量）。 */
        const val PUSHBACK_BUFFER = 128 * 1024

        /** 进度日志节流：每 10% 或每 ≥5MB。 */
        const val PROGRESS_STEP_PCT = 10
        const val PROGRESS_STEP_BYTES = 5L * 1024 * 1024

        val SAFE_SESSION_ID = Regex("[A-Za-z0-9_-]+")

        val REASONS = mapOf(
            200 to "OK",
            400 to "Bad Request",
            404 to "Not Found",
            405 to "Method Not Allowed",
            409 to "Conflict",
            413 to "Payload Too Large",
            500 to "Internal Server Error",
        )
    }
}
