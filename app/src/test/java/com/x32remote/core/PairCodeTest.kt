package com.x32remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对码解析：与服务端 `x32sim/pair.py`、iOS `PairCode.swift` 同一套规则。
 * 三端各自实现，所以这里逐条对齐"坏值必须拒绝"——否则同一个码在手机上
 * 能连、在另一台设备上连不上，现场最难查。
 */
class PairCodeTest {

    @Test
    fun parsePairCode() {
        val c = PairCode.parse("x32remote://pair?v=1&host=192.168.50.57&osc=10025&web=8090")
        assertEquals(PairCode.Code("192.168.50.57", 10025, 8090), c)
    }

    @Test
    fun parsePairCodeWithoutWebPort() {
        val c = PairCode.parse("x32remote://pair?v=1&host=10.0.0.9&osc=10023")
        assertEquals("10.0.0.9", c?.host)
        assertEquals(10023, c?.osc)
        assertNull(c?.web)
    }

    /** 现场最常见的动作：拿 App 去扫电脑端那张浏览器二维码 */
    @Test
    fun parseBrowserUrl() {
        assertEquals(
            PairCode.Code("192.168.50.57", 10023, 8080),
            PairCode.parse("http://192.168.50.57:8080/")
        )
    }

    @Test
    fun parseBrowserUrlWithoutPort() {
        val c = PairCode.parse("https://mixer.local/")
        assertEquals("mixer.local", c?.host)
        assertEquals(10023, c?.osc)
        assertNull(c?.web)
    }

    @Test
    fun parseHostPortAndBareHost() {
        assertEquals(PairCode.Code("192.168.1.9", 10025, null), PairCode.parse("192.168.1.9:10025"))
        assertEquals(PairCode.Code("192.168.1.9", 10023, null), PairCode.parse(" 192.168.1.9 "))
        assertEquals(PairCode.Code("192.168.1.9", 10023, null), PairCode.parse("192.168.1.9/"))
    }

    @Test
    fun rejectsLoopback() {
        assertNull(PairCode.parse("127.0.0.1:10023"))
        assertNull(PairCode.parse("localhost:10023"))
        assertNull(PairCode.parse("x32remote://pair?v=1&host=127.0.0.1&osc=10023"))
        assertNull(PairCode.parse("http://127.0.0.1:8080/"))
    }

    @Test
    fun rejectsBadValues() {
        assertNull(PairCode.parse(""))
        assertNull(PairCode.parse(null))
        assertNull(PairCode.parse("javascript:alert(1)"))
        assertNull(PairCode.parse("ftp://192.168.1.5/"))
        assertNull(PairCode.parse("192.168.1.5:70000"))
        assertNull(PairCode.parse("256.1.1.1"))
        assertNull(PairCode.parse("192.168.1.5:0"))
        assertNull(PairCode.parse("x32remote://pair?v=1&host=192.168.1.5&osc=abc"))
        assertNull(PairCode.parse("http://192.168.1.5:99999/"))
        assertNull(PairCode.parse("a".repeat(220)))
    }

    @Test
    fun toleratesWhitespace() {
        assertEquals(
            PairCode.Code("192.168.50.57", 10023, 8080),
            PairCode.parse("  http://192.168.50.57:8080/  ")
        )
    }

    @Test
    fun portRange() {
        assertTrue(PairCode.validPort(1))
        assertTrue(PairCode.validPort(65535))
        assertFalse(PairCode.validPort(0))
        assertFalse(PairCode.validPort(65536))
    }

    @Test
    fun summaryIsReadable() {
        assertEquals("192.168.1.5:10023（网页 8080）",
            PairCode.Code("192.168.1.5", 10023, 8080).summary)
        assertEquals("192.168.1.5:10025", PairCode.Code("192.168.1.5", 10025, null).summary)
    }
}
