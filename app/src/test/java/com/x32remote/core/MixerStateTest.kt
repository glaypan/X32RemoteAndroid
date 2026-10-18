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
}
