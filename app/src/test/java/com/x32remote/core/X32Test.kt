package com.x32remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 地址表 / dB 曲线 / 电平解析：对应《共享协议规范.md》第 3–6 节。 */
class X32Test {

    // ---------------------------------------------------------- 地址

    @Test
    fun `dca address must be zero padded`() {
        // 若写成 /dca/1/fader 会与状态键不一致，产生重复条目
        assertEquals("/dca/01/fader", X32.dcaFader(1))
        assertEquals("/dca/08/on", X32.dcaOn(8))
    }

    @Test
    fun `channel and bus addresses`() {
        assertEquals("/ch/01/mix/fader", X32.channelFader(1))
        assertEquals("/ch/32/mix/on", X32.channelOn(32))
        assertEquals("/bus/16/mix/fader", X32.busFader(16))
        assertEquals("/ch/05/mix/dca", X32.channelDcaMask(5))
    }

    @Test
    fun `main address has no placeholder`() {
        // 历史 bug：对 main 地址做 % 格式化会抛异常并导致后台线程静默死亡
        assertEquals("/main/st/mix/fader", X32.faderAddress("main_fader", 0))
        assertEquals("/main/st/mix/on", X32.onAddress("main_on", 0))
        assertEquals("/main/st/mix/fader", X32.addressOf("main_fader", 0))
        assertEquals("/main/st/mix/on", X32.addressOf("main_on", 0))
    }

    @Test
    fun `scene address is not padded`() {
        assertEquals("/scene/2/load", X32.sceneLoad(2))
    }

    @Test
    fun `name addresses per group`() {
        assertEquals("/ch/03/config/name", X32.nameAddress("ch", 3))
        assertEquals("/dca/01/config/name", X32.nameAddress("dca", 1))
        assertEquals("/main/st/config/name", X32.nameAddress("main", 0))
    }

    @Test
    fun `kind group parsing`() {
        assertEquals("ch", X32.kindGroup("ch_fader"))
        assertEquals("main", X32.kindGroup("main_on"))
        assertEquals("fxret", X32.kindGroup("fxret_fader"))
    }

    @Test
    fun `group counts match protocol`() {
        assertEquals(32, X32.GROUP_COUNTS["ch"])
        assertEquals(16, X32.GROUP_COUNTS["bus"])
        assertEquals(8, X32.GROUP_COUNTS["dca"])
        assertEquals(6, X32.GROUP_COUNTS["auxin"])
        assertEquals(2, X32.GROUP_COUNTS["usb"])
        assertEquals(8, X32.GROUP_COUNTS["fxret"])
        assertEquals(6, X32.GROUP_COUNTS["mtx"])
    }

    // ---------------------------------------------------------- dB 曲线

    @Test
    fun `unity gain is at 0_75 travel`() {
        assertEquals(0f, X32.faderToDb(0.75f), 0.001f)
        assertEquals(0.75f, X32.dbToFader(0f), 0.0001f)
    }

    @Test
    fun `fader extremes`() {
        assertEquals(-90f, X32.faderToDb(0f), 0.001f)
        assertEquals(10f, X32.faderToDb(1f), 0.001f)
        assertEquals(0f, X32.dbToFader(-95f), 0.0001f)
        assertEquals(1f, X32.dbToFader(20f), 0.0001f)
    }

    @Test
    fun `db curve round trips`() {
        for (db in listOf(-60f, -30f, -10f, -3f, 0f, 3f, 6f, 10f)) {
            val f = X32.dbToFader(db)
            assertEquals(db, X32.faderToDb(f), 0.01f)
        }
    }

    @Test
    fun `format db renders infinity and sign`() {
        assertEquals("-∞", X32.formatDb(0f))
        assertEquals("+0.0", X32.formatDb(0.75f))
        assertTrue(X32.formatDb(0.5f).startsWith("-"))
    }

    // ---------------------------------------------------------- 电平

    @Test
    fun `meter floats skip leading count field`() {
        // 历史 bug：把首部 Int32 数量当成第一个电平值
        val buf = ByteBuffer.allocate(4 + 12).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(3)
        buf.putFloat(-60f)
        buf.putFloat(-6f)
        buf.putFloat(0f)

        val arr = X32.meterFloats(buf.array())
        assertEquals(3, arr.size)
        assertEquals(-60f, arr[0], 0.001f)
        assertEquals(-6f, arr[1], 0.001f)
        assertEquals(0f, arr[2], 0.001f)
    }

    @Test
    fun `meter floats tolerates truncated blob`() {
        val buf = ByteBuffer.allocate(4 + 4).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(99) // 声称有 99 个，实际只有 1 个
        buf.putFloat(-12f)

        val arr = X32.meterFloats(buf.array())
        assertEquals(1, arr.size)
        assertEquals(-12f, arr[0], 0.001f)
    }

    @Test
    fun `meter level mapping clamps`() {
        assertEquals(0f, X32.meterToLevel(-32768f), 0.0001f)
        assertEquals(0f, X32.meterToLevel(-60f), 0.0001f)
        assertEquals(0.5f, X32.meterToLevel(-30f), 0.0001f)
        assertEquals(1f, X32.meterToLevel(0f), 0.0001f)
        assertEquals(1f, X32.meterToLevel(6f), 0.0001f)
    }

    // ---------------------------------------------------------- 曲线

    @Test
    fun `curves hit both endpoints`() {
        for (c in X32.CURVES) {
            assertEquals(0f, X32.applyCurve(0f, c), 0.0001f)
            assertEquals(1f, X32.applyCurve(1f, c), 0.0001f)
        }
    }

    @Test
    fun `curves have expected shape`() {
        assertTrue("ease_out 前半应快于线性", X32.applyCurve(0.5f, "ease_out") > 0.5f)
        assertTrue("ease_in 前半应慢于线性", X32.applyCurve(0.5f, "ease_in") < 0.5f)
        assertEquals(0.5f, X32.applyCurve(0.5f, "smooth"), 0.0001f)
        assertEquals(0.3f, X32.applyCurve(0.3f, "linear"), 0.0001f)
    }

    @Test
    fun `unknown curve falls back to linear`() {
        assertEquals(0.3f, X32.applyCurve(0.3f, "bogus"), 0.0001f)
    }
}
