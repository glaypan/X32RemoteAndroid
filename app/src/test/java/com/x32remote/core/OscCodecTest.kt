package com.x32remote.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** OSC 编解码：与《共享协议规范.md》第 2 节逐条对应。 */
class OscCodecTest {

    @Test
    fun `encoded message length is multiple of 4`() {
        val bytes = OscCodec.encodeMessage(OscMessage("/xremote"))
        assertEquals(0, bytes.size % 4)
        assertEquals(16, bytes.size) // 8 地址 +1 空 +3 补齐 + 2 tag +2 补齐
    }

    @Test
    fun `padding helper aligns correctly`() {
        assertEquals(4, OscCodec.pad4(1))
        assertEquals(4, OscCodec.pad4(4))
        assertEquals(8, OscCodec.pad4(5))
        assertEquals(12, OscCodec.pad4(9))
    }

    @Test
    fun `float fader round trips`() {
        val bytes = OscCodec.encodeMessage(OscMessage("/ch/05/mix/fader", listOf(0.75f)))
        val back = OscCodec.decode(bytes)
        assertEquals(1, back.size)
        assertEquals("/ch/05/mix/fader", back[0].address)
        assertEquals(0.75f, back[0].args[0] as Float, 0.0001f)
    }

    @Test
    fun `int and float use distinct type tags`() {
        // 必须发 i 给静音、f 给推子；发错真台会静默忽略
        val asInt = OscCodec.encodeMessage(OscMessage("/t", listOf(1)))
        val asFloat = OscCodec.encodeMessage(OscMessage("/t", listOf(1f)))

        // "/t" -> pad4(3) = 4，故 type tag 从 offset 4 开始
        assertEquals(',', asInt[4].toInt().toChar())
        assertEquals('i', asInt[5].toInt().toChar())
        assertEquals(',', asFloat[4].toInt().toChar())
        assertEquals('f', asFloat[5].toInt().toChar())
    }

    @Test
    fun `string argument round trips`() {
        val bytes = OscCodec.encodeMessage(OscMessage("/ch/01/config/name", listOf("音乐")))
        val back = OscCodec.decode(bytes)
        assertEquals("音乐", back[0].args[0] as String)
    }

    @Test
    fun `blob round trips with padding`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5) // 5 字节，需要补齐到 8
        val bytes = OscCodec.encodeMessage(OscMessage("/meters/1", listOf(payload)))
        val back = OscCodec.decode(bytes)
        val got = back[0].args[0] as ByteArray
        assertArrayEquals(payload, got)
    }

    @Test
    fun `bundle expands to contained messages`() {
        val bundle = OscCodec.encodeBundle(
            listOf(
                OscMessage("/ch/01/mix/fader"),
                OscMessage("/ch/02/mix/on"),
                OscMessage("/main/st/mix/fader")
            )
        )
        val back = OscCodec.decode(bundle)
        assertEquals(3, back.size)
        assertEquals("/ch/01/mix/fader", back[0].address)
        assertEquals("/main/st/mix/fader", back[2].address)
    }

    @Test
    fun `bundle element length starts after timetag`() {
        // 历史 bug：从 offset=8 读长度，把 timetag 前 4 字节误当长度 → Bundle 完全不可用
        val bundle = OscCodec.encodeBundle(listOf(OscMessage("/xinfo")))
        assertTrue(bundle.size > 16)
        // offset 16 起的 4 字节应当是一条合法消息的长度（> 0 且小于剩余空间）
        val size = ((bundle[16].toInt() and 0xFF) shl 24) or
            ((bundle[17].toInt() and 0xFF) shl 16) or
            ((bundle[18].toInt() and 0xFF) shl 8) or
            (bundle[19].toInt() and 0xFF)
        assertEquals(bundle.size - 20, size)
    }

    @Test
    fun `message without type tag decodes as bare address`() {
        // 手工构造一个只有地址、没有 type tag 的包（部分真台查询回包如此）
        val raw = ByteArrayOutputStream().apply {
            write("/scene/1".toByteArray())
            write(ByteArray(OscCodec.pad4(9) - 9))
        }.toByteArray()
        val back = OscCodec.decode(raw)
        assertEquals(1, back.size)
        assertEquals("/scene/1", back[0].address)
        assertTrue(back[0].args.isEmpty())
    }

    @Test
    fun `boolean arguments use T and F`() {
        val bytes = OscCodec.encodeMessage(OscMessage("/t", listOf(true, false)))
        val back = OscCodec.decode(bytes)
        assertEquals(true, back[0].args[0])
        assertEquals(false, back[0].args[1])
    }

    @Test
    fun `arg coercion accepts int where float expected`() {
        // 真台有时用 i 回推子值
        assertEquals(1.0f, OscCodec.argAsFloat(1)!!, 0.0001f)
        assertEquals(1, OscCodec.argAsInt(1.0f))
        assertEquals(0, OscCodec.argAsInt(false))
        assertEquals(1, OscCodec.argAsInt(true))
    }
}
