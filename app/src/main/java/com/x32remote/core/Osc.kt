package com.x32remote.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 一条 OSC 消息。
 *
 * args 元素只能是：Int / Float / String / ByteArray / Boolean
 * —— 与 X32 用到的类型标签 i f s b T F 一一对应。
 */
data class OscMessage(
    val address: String,
    val args: List<Any> = emptyList()
)

/**
 * OSC 1.0 编解码。全部大端序。
 *
 * 规范见《共享协议规范.md》第 2 节。两个必须守住的不变量：
 *  1. Int 必须编码为 int32（标签 i），Float 必须编码为 float32（标签 f）。
 *  2. Bundle 的元素长度从 offset = 16 开始读（8 字节 "#bundle\0" + 8 字节 timetag）。
 */
object OscCodec {

    /** 4 字节对齐：1->4, 4->4, 5->8 */
    fun pad4(n: Int): Int = n + ((4 - n % 4) % 4)

    // ------------------------------------------------------------ 编码

    fun encodeMessage(msg: OscMessage): ByteArray {
        val out = ByteArrayOutputStream(64)

        // 地址：UTF-8 + \0，再补齐
        out.write(nulPadded(msg.address.toByteArray(Charsets.UTF_8)))

        val tags = StringBuilder(",")
        val payload = ByteArrayOutputStream(32)

        for (a in msg.args) {
            when (a) {
                is Boolean -> tags.append(if (a) 'T' else 'F')
                is Int -> { tags.append('i'); payload.write(int32(a)) }
                is Float -> { tags.append('f'); payload.write(float32(a)) }
                is String -> { tags.append('s'); payload.write(nulPadded(a.toByteArray(Charsets.UTF_8))) }
                is ByteArray -> {
                    tags.append('b')
                    payload.write(int32(a.size))
                    payload.write(padded(a))
                }
                else -> throw IllegalArgumentException("不支持的 OSC 参数类型: ${a::class.java}")
            }
        }

        out.write(nulPadded(tags.toString().toByteArray(Charsets.US_ASCII)))
        out.write(payload.toByteArray())
        return out.toByteArray()
    }

    /** 立即执行的 Bundle（timetag 全 0）。批量查询/批量写入用，减少包数量。 */
    fun encodeBundle(messages: List<OscMessage>): ByteArray {
        val out = ByteArrayOutputStream(256)
        out.write("#bundle\u0000".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(8)) // timetag = 0 → 立即
        for (m in messages) {
            val body = encodeMessage(m)
            out.write(int32(body.size))
            out.write(body)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ 解码

    /** 解码收到的 UDP 包，自动展开 Bundle。 */
    fun decode(data: ByteArray): List<OscMessage> {
        if (data.size >= 8 && matches(data, 0, "#bundle")) {
            val out = ArrayList<OscMessage>(8)
            var offset = 16 // 8 字节 "#bundle\0" + 8 字节 timetag
            while (offset + 4 <= data.size) {
                val size = readInt32(data, offset)
                offset += 4
                if (size <= 0 || offset + size > data.size) break
                out.addAll(decode(data.copyOfRange(offset, offset + size)))
                offset += size
            }
            return out
        }

        val z = indexOfZero(data, 0)
        if (z < 0) return emptyList()
        val address = String(data, 0, z, Charsets.UTF_8)

        var offset = pad4(z + 1)
        if (offset >= data.size || data[offset] != ','.code.toByte()) {
            return listOf(OscMessage(address))
        }

        val zend = indexOfZero(data, offset)
        if (zend < 0) return listOf(OscMessage(address))
        val tags = String(data, offset + 1, zend - offset - 1, Charsets.US_ASCII)
        offset = pad4(zend + 1)

        val args = ArrayList<Any>(tags.length)
        for (t in tags) {
            when (t) {
                'i' -> {
                    if (offset + 4 > data.size) break
                    args.add(readInt32(data, offset)); offset += 4
                }
                'f' -> {
                    if (offset + 4 > data.size) break
                    args.add(readFloat32(data, offset)); offset += 4
                }
                's' -> {
                    val end = indexOfZero(data, offset)
                    if (end < 0) break
                    args.add(String(data, offset, end - offset, Charsets.UTF_8))
                    offset = pad4(end + 1)
                }
                'b' -> {
                    if (offset + 4 > data.size) break
                    val n = readInt32(data, offset); offset += 4
                    if (n < 0 || offset + n > data.size) break
                    args.add(data.copyOfRange(offset, offset + n))
                    offset = pad4(offset + n)
                }
                'T' -> args.add(true)
                'F' -> args.add(false)
            }
        }
        return listOf(OscMessage(address, args))
    }

    // ------------------------------------------------------------ 工具

    /** 数值参数读取：真台可能用 i 回 fader，统一取 Float。 */
    fun argAsFloat(v: Any?): Float? = when (v) {
        is Float -> v
        is Int -> v.toFloat()
        is Double -> v.toDouble().toFloat()
        else -> null
    }

    /** 静音参数读取：真台可能用 f 回 on。 */
    fun argAsInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Float -> v.toInt()
        is Boolean -> if (v) 1 else 0
        else -> null
    }

    fun argAsString(v: Any?): String? = v as? String

    // ------------------------------------------------------------ 私有

    private fun matches(d: ByteArray, off: Int, s: String): Boolean {
        val b = s.toByteArray(Charsets.US_ASCII)
        if (off + b.size > d.size) return false
        for (i in b.indices) if (d[off + i] != b[i]) return false
        return true
    }

    /** 内容 + 结尾 \0，再补齐到 4 字节 */
    private fun nulPadded(content: ByteArray): ByteArray =
        padded(content.copyOf(content.size + 1))

    private fun padded(content: ByteArray): ByteArray {
        val target = pad4(content.size)
        return if (target == content.size) content else content.copyOf(target)
    }

    private fun indexOfZero(d: ByteArray, from: Int): Int {
        var i = from
        while (i < d.size) {
            if (d[i] == 0.toByte()) return i
            i++
        }
        return -1
    }

    private fun int32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()

    private fun float32(v: Float): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(v).array()

    private fun readInt32(d: ByteArray, off: Int): Int =
        ByteBuffer.wrap(d, off, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun readFloat32(d: ByteArray, off: Int): Float =
        ByteBuffer.wrap(d, off, 4).order(ByteOrder.BIG_ENDIAN).float
}
