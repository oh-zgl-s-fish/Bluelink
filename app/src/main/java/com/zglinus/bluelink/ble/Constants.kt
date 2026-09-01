package com.zglinus.bluelink.ble

import android.os.ParcelUuid
import java.util.UUID

/**
 * Bluelink BLE 一期常量。
 *
 * - 广播载荷携带自定义 GATT Service UUID；扫描按同 UUID 过滤（ScanFilter）；
 * - GATT 服务含 1 个 WRITE 特征（收消息）+ 1 个 NOTIFY 特征（发消息），无需配对；
 * - 握手消息单包上限 150 字节（一期不分包，超限截断）。
 */
object Constants {

    /** 自定义 GATT Service UUID（128 位，广播载荷与 ScanFilter 共用）。 */
    val SERVICE_UUID: UUID = UUID.fromString("a0b1c2d3-e4f5-4a6b-8c9d-0e1f2a3b4c5d")

    /** GATT WRITE 特征：远端写入 → 本机接收握手消息。 */
    val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("a0b1c2d3-e4f5-4a6b-8c9d-0e1f2a3b4c5e")

    /** GATT NOTIFY 特征：本机通知 → 远端接收握手消息。 */
    val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("a0b1c2d3-e4f5-4a6b-8c9d-0e1f2a3b4c5f")

    /** 标准 Client Characteristic Configuration 描述符（通知开关）。 */
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** GATT 握手超时：连接后 10s 内未完成握手即自动断开。 */
    const val HANDSHAKE_TIMEOUT_MS: Long = 10_000L

    /** 握手 JSON 单包上限（一期不分包，超限打 Log 警告并截断）。 */
    const val MAX_HANDSHAKE_BYTES: Int = 150

    /** 同网 TCP 探测默认端口（LocalSend 标准端口；一期仅预留接口，不实际执行）。 */
    const val DEFAULT_TCP_PROBE_PORT: Int = 53317

    /** 广播/过滤用 ParcelUuid。 */
    fun serviceParcelUuid(): ParcelUuid = ParcelUuid(SERVICE_UUID)
}
