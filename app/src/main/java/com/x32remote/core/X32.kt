package com.x32remote.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * X32/M32 地址表、数值语义与 dB 曲线。
 *
 * 严格对齐《共享协议规范.md》第 3、4、5、6 节。
 *
 * 三个必须记住的坑：
 *  1. DCA 地址必须两位补零（/dca/01/fader）
 *  2. Main 地址无 %02d 占位符，绝不能做格式化替换
 *  3. 场景地址不补零（/scene/2/load）
 */
object X32 {

    const val DEFAULT_PORT = 10023

    /** 保活：每 5 秒一次，服务端 30 秒无续期则清理 */
    const val KEEPALIVE = "/xremote"

    /** 台面变更推送订阅：args = [1] 开, [0] 关 */
    const val SUBSCRIBE = "/subscribe"

    /** 局域网发现 */
    const val XINFO = "/xinfo"

    /** 电平表订阅（返回 blob） */
    const val METERS = "/meters/1"

    const val MAIN_FADER = "/main/st/mix/fader"
    const val MAIN_ON = "/main/st/mix/on"
    const val MAIN_NAME = "/main/st/config/name"

    /** 各组通道数量（main 的 idx 固定为 0，单独处理） */
    val GROUP_COUNTS: Map<String, Int> = linkedMapOf(
        "ch" to 32,
        "bus" to 16,
        "dca" to 8,
        "auxin" to 6,
        "usb" to 2,
        "fxret" to 8,
        "mtx" to 6
    )

    val GROUP_LABEL: Map<String, String> = mapOf(
        "ch" to "通道",
        "bus" to "Bus",
        "dca" to "DCA",
        "auxin" to "AuxIn",
        "usb" to "USB",
        "fxret" to "FX",
        "mtx" to "Matrix",
        "main" to "Main"
    )

    /** "ch_fader" -> "ch" */
    fun kindGroup(kind: String): String = kind.substringBeforeLast('_')

    private fun f(fmt: String, vararg args: Any): String =
        String.format(Locale.US, fmt, *args)

    // ------------------------------------------------------------ 地址生成

    fun channelFader(i: Int) = f("/ch/%02d/mix/fader", i)
    fun channelOn(i: Int) = f("/ch/%02d/mix/on", i)
    fun busFader(i: Int) = f("/bus/%02d/mix/fader", i)
    fun busOn(i: Int) = f("/bus/%02d/mix/on", i)

    /** ⚠️ DCA 两位补零 */
    fun dcaFader(i: Int) = f("/dca/%02d/fader", i)
    fun dcaOn(i: Int) = f("/dca/%02d/on", i)

    fun channelDcaMask(i: Int) = f("/ch/%02d/mix/dca", i)

    /** ⚠️ 场景不补零 */
    fun sceneLoad(scene: Int) = "/scene/$scene/load"

    fun nameAddress(group: String, idx: Int): String = when (group) {
        "ch" -> f("/ch/%02d/config/name", idx)
        "bus" -> f("/bus/%02d/config/name", idx)
        "dca" -> f("/dca/%02d/config/name", idx)
        "auxin" -> f("/auxin/%02d/config/name", idx)
        "usb" -> f("/usb/%02d/config/name", idx)
        "fxret" -> f("/fxret/%02d/config/name", idx)
        "mtx" -> f("/mtx/%02d/config/name", idx)
        "main" -> MAIN_NAME
        else -> throw IllegalArgumentException("未知分组: $group")
    }

    fun faderAddress(kind: String, idx: Int): String = when (kind) {
        "ch_fader" -> channelFader(idx)
        "bus_fader" -> busFader(idx)
        "dca_fader" -> dcaFader(idx)
        "auxin_fader" -> f("/auxin/%02d/mix/fader", idx)
        "usb_fader" -> f("/usb/%02d/mix/fader", idx)
        "fxret_fader" -> f("/fxret/%02d/mix/fader", idx)
        "mtx_fader" -> f("/mtx/%02d/mix/fader", idx)
        "main_fader" -> MAIN_FADER // 无占位符
        else -> throw IllegalArgumentException("未知推子 kind: $kind")
    }

    fun onAddress(kind: String, idx: Int): String = when (kind) {
        "ch_on" -> channelOn(idx)
        "bus_on" -> busOn(idx)
        "dca_on" -> dcaOn(idx)
        "auxin_on" -> f("/auxin/%02d/mix/on", idx)
        "usb_on" -> f("/usb/%02d/mix/on", idx)
        "fxret_on" -> f("/fxret/%02d/mix/on", idx)
        "mtx_on" -> f("/mtx/%02d/mix/on", idx)
        "main_on" -> MAIN_ON // 无占位符
        else -> throw IllegalArgumentException("未知静音 kind: $kind")
    }

    /** 统一入口：内部已处理 Main 无占位符的特例 */
    fun addressOf(kind: String, idx: Int): String =
        if (kind.endsWith("_fader")) faderAddress(kind, idx) else onAddress(kind, idx)

    // ------------------------------------------------------------ dB 曲线

    /** 推子行程(0..1) → dB。0.75 = 0 dB, 1.0 = +10 dB, 0.0 = -∞ */
    fun faderToDb(f: Float): Float {
        if (f <= 0f) return -90f
        if (f >= 0.75f) return (f - 0.75f) / 0.25f * 10f
        // 历史 bug：此处曾有 `if (f <= 0.001f) return -90f`，把 -57.5dB 以下的整段
        // 塌陷成 -∞，导致 -60dB 无法往返。改为对下限做 clamp，保住低段分辨率。
        return maxOf(-90f, 20f * log10(f / 0.75f))
    }

    /** dB → 推子行程(0..1)，faderToDb 的逆运算 */
    fun dbToFader(dbIn: Float): Float {
        val db = dbIn.coerceIn(-90f, 10f)
        if (db <= -89.5f) return 0f
        if (db >= 10f) return 1f
        if (db >= 0f) return 0.75f + db / 10f * 0.25f
        return 0.75f * 10f.pow(db / 20f)
    }

    /** 推子 → 显示用 dB 字符串 */
    fun formatDb(f: Float): String {
        val db = faderToDb(f)
        if (db <= -89.5f) return "-∞"
        return if (db >= 0f) String.format(Locale.US, "+%.1f", db)
        else String.format(Locale.US, "%.1f", db)
    }

    // ------------------------------------------------------------ 电平表

    /**
     * 解析 /meters blob：前 4 字节是大端 Int32 数量，其后才是 Float 电平值。
     * ⚠️ 必须跳过数量字段，否则第一个电平值会错位。
     */
    fun meterFloats(blob: ByteArray): FloatArray {
        if (blob.size < 8) return FloatArray(0)
        val count = ByteBuffer.wrap(blob, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        if (count <= 0) return FloatArray(0)
        val available = (blob.size - 4) / 4
        val n = minOf(count, available)
        if (n <= 0) return FloatArray(0)
        val out = FloatArray(n)
        val buf = ByteBuffer.wrap(blob, 4, n * 4).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until n) out[i] = buf.float
        return out
    }

    /** 电平 dB → UI 0..1。-32768 表示 -∞，-60dB→0, 0dB→1 */
    fun meterToLevel(db: Float): Float {
        if (db <= -32700f) return 0f
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------ 曲线

    val CURVES = listOf("linear", "ease_out", "ease_in", "smooth")

    /** t 为 0→1 归一化进度 */
    fun applyCurve(t: Float, curve: String): Float = when (curve) {
        "ease_out" -> 1f - (1f - t).pow(2)      // 先快后慢
        "ease_in" -> t * t                       // 先慢后快
        "smooth" -> t * t * (3f - 2f * t)        // S 形缓入缓出
        else -> t                                // linear
    }
}
