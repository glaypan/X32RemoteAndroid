package com.x32remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态模型：默认值、钳制、名称、DCA 归属。 */
class MixerStateTest {

    @Test
    fun `defaults are unity gain and unmuted`() {
        val s = MixerState()
        assertEquals(0.75f, s.fader("ch_fader", 1), 0.0001f)
        assertEquals(1, s.on("ch_on", 1))
        assertEquals(0.75f, s.fader("main_fader", 0), 0.0001f)
        assertEquals(0.75f, s.fader("bus_fader", 16), 0.0001f)
    }

    @Test
    fun `fader is clamped to travel range`() {
        val s = MixerState()
        s.setFader("ch_fader", 1, 2f)
        assertEquals(1f, s.fader("ch_fader", 1), 0.0001f)
        s.setFader("ch_fader", 1, -1f)
        assertEquals(0f, s.fader("ch_fader", 1), 0.0001f)
    }

    @Test
    fun `set fader reports whether value changed`() {
        val s = MixerState()
        assertTrue(s.setFader("ch_fader", 1, 0.5f))
        assertFalse(s.setFader("ch_fader", 1, 0.5f))
    }

    @Test
    fun `main uses index zero`() {
        val s = MixerState()
        s.setFader("main_fader", 0, 0.9f)
        assertEquals(0.9f, s.fader("main_fader", 0), 0.0001f)
        assertEquals(1, s.snapshotGroup("main").size)
    }

    @Test
    fun `default dca membership covers every eight channels`() {
        val s = MixerState()
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), s.dcaMembers(1))
        assertEquals(listOf(9, 10, 11, 12, 13, 14, 15, 16), s.dcaMembers(2))
        assertEquals(listOf(17, 18, 19, 20, 21, 22, 23, 24), s.dcaMembers(3))
        assertEquals(listOf(25, 26, 27, 28, 29, 30, 31, 32), s.dcaMembers(4))
        assertTrue(s.dcaMembers(5).isEmpty())
    }

    @Test
    fun `dca mask read and write`() {
        val s = MixerState()
        // ch5 默认属于 DCA1 → bit0
        assertEquals(0b0000_0001, s.dcaMask(5))

        s.setDcaMask(5, 0b0000_0010) // 改属 DCA2
        assertEquals(0b0000_0010, s.dcaMask(5))
        assertTrue(s.dcaMembers(2).contains(5))
        assertFalse(s.dcaMembers(1).contains(5))
    }

    @Test
    fun `dca mask can hold multiple memberships`() {
        val s = MixerState()
        s.setDcaMask(5, 0b0000_0101) // DCA1 + DCA3
        assertEquals(0b0000_0101, s.dcaMask(5))
        assertTrue(s.dcaMembers(1).contains(5))
        assertTrue(s.dcaMembers(3).contains(5))
    }

    @Test
    fun `name get set and clear`() {
        val s = MixerState()
        s.setName("ch", 1, "主唱")
        assertEquals("主唱", s.name("ch", 1))
        s.setName("ch", 1, "")
        assertEquals("", s.name("ch", 1))
    }

    @Test
    fun `name is trimmed to twelve chars`() {
        val s = MixerState()
        s.setName("ch", 2, "一二三四五六七八九十十一十二十三")
        assertEquals(12, s.name("ch", 2).length)
    }

    @Test
    fun `scene round trip`() {
        val s = MixerState()
        assertEquals(1, s.scene())
        s.setScene(7)
        assertEquals(7, s.scene())
    }

    @Test
    fun `snapshot group sizes match protocol`() {
        val s = MixerState()
        assertEquals(32, s.snapshotGroup("ch").size)
        assertEquals(16, s.snapshotGroup("bus").size)
        assertEquals(8, s.snapshotGroup("dca").size)
        assertEquals(6, s.snapshotGroup("auxin").size)
        assertEquals(2, s.snapshotGroup("usb").size)
        assertEquals(8, s.snapshotGroup("fxret").size)
        assertEquals(6, s.snapshotGroup("mtx").size)
    }

    // ---------------------------------------------------------- FX 机架 (v1.6)

    @Test
    fun `fx slots start empty and unmuted`() {
        val s = MixerState()
        assertEquals(8, s.fxSlots().size)
        for (st in s.fxSlots()) {
            assertEquals("", st.algoType)      // 空槽: 真台没装效果
            assertEquals(1, st.fxOn)
            assertEquals(1, st.on)             // FX 返回未静音
            assertEquals(0.75f, st.fader, 0.0001f)
            assertTrue(st.params.isEmpty())
        }
    }

    @Test
    fun `out of range fx slot returns blank state instead of throwing`() {
        val s = MixerState()
        assertEquals("", s.fxSlot(0).algoType)
        assertEquals("", s.fxSlot(99).algoType)
        assertFalse(s.setFxAlgo(0, "HALL"))    // 越界写不进
    }

    @Test
    fun `switching algo drops stale params`() {
        // 换算法后旧参数号指的是完全不同的东西, 留着会把界面骗成假值
        val s = MixerState()
        s.setFxParam(1, "01", 3.5)
        s.setFxParam(1, "02", 1.25)
        assertEquals(2, s.fxSlot(1).params.size)

        assertTrue(s.setFxAlgo(1, "HALL"))
        assertEquals("HALL", s.fxSlot(1).algoType)
        assertTrue("换算法必须清空旧参数", s.fxSlot(1).params.isEmpty())
    }

    @Test
    fun `fx param keys are the raw two digit osc segment`() {
        // X32Client 收到 /fx/1/par/01 时按原样存键, 界面取值也按 "%02d" 拼
        val s = MixerState()
        s.setFxParam(3, "01", 0.5)
        s.setFxParam(3, "12", 0.25)
        assertEquals(0.5, s.fxSlot(3).params["01"]!!, 1e-9)
        assertEquals(0.25, s.fxSlot(3).params["12"]!!, 1e-9)
        assertFalse(s.fxSlot(3).params.containsKey("1"))   // 不能用非补零键
    }

    @Test
    fun `fx values are clamped to their own range`() {
        val s = MixerState()
        s.setFxMix(1, 2.0)
        assertEquals(1.0, s.fxSlot(1).mix, 1e-9)
        s.setFxMix(1, -1.0)
        assertEquals(0.0, s.fxSlot(1).mix, 1e-9)

        s.setFxFader(1, 3f)
        assertEquals(1f, s.fxSlot(1).fader, 0.0001f)
        s.setFxFader(1, -2f)
        assertEquals(0f, s.fxSlot(1).fader, 0.0001f)
    }

    @Test
    fun `fx enabled and return on are stored as 0 or 1`() {
        val s = MixerState()
        s.setFxEnabled(2, 0)
        assertEquals(0, s.fxSlot(2).fxOn)
        s.setFxEnabled(2, 7)             // 真台只认 0/1, 非 0 一律当 1
        assertEquals(1, s.fxSlot(2).fxOn)

        s.setFxReturnOn(2, 0)
        assertEquals(0, s.fxSlot(2).on)
        s.setFxReturnOn(2, 5)
        assertEquals(1, s.fxSlot(2).on)
    }
}
