package com.x32remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FX 相关 OSC 地址。
 *
 * 两类地址容易搞混，历史上三端各自写错过：
 *  - FX **返回**（推子/静音/名）：真台拼写 `fxrtn`，槽号两位补零
 *  - FX **机架**（算法/开关/混合/参数）：`fx`，槽号**不补零**，参数号补零
 *
 * 早年本端与 iOS 都用过臆造的 /rtn/fx/，对真台是哑地址（发得出去、台子不理）。
 */
class FxAddressTest {

    // ---------------------------------------------------------- FX 返回

    @Test
    fun `fx return uses real console spelling fxrtn`() {
        assertEquals("/fxrtn/01/mix/fader", X32.fxrtnFader(1))
        assertEquals("/fxrtn/08/mix/fader", X32.fxrtnFader(8))
        assertEquals("/fxrtn/03/mix/on", X32.fxrtnOn(3))
        assertEquals("/fxrtn/02/config/name", X32.fxrtnName(2))
    }

    @Test
    fun `fx return slot number is zero padded`() {
        // 不补零会与状态键不一致：/fxrtn/8 与 /fxrtn/08 会被当成两个槽
        for (i in 1..8) {
            assertTrue("槽 $i 应两位补零", X32.fxrtnFader(i).contains(String.format("%02d", i)))
        }
    }

    @Test
    fun `kind mapping writes fxrtn while kind name stays fxret`() {
        // kind 名不能改: 卡片配置里存的是 kind, 改名会让老存档写不出地址
        assertEquals("/fxrtn/05/mix/fader", X32.faderAddress("fxret_fader", 5))
        assertEquals("/fxrtn/05/mix/on", X32.onAddress("fxret_on", 5))
        assertEquals("/fxrtn/05/config/name", X32.nameAddress("fxret", 5))
    }

    @Test
    fun `fx return spelling normalizes to fxret group`() {
        assertEquals("fxret", X32.fxReturnGroupOf("fxrtn"))
        assertEquals("fxret", X32.fxReturnGroupOf("fxret"))
        assertNull(X32.fxReturnGroupOf("rtn"))       // 臆造地址不该被认
        assertNull(X32.fxReturnGroupOf("ch"))
        assertNull(X32.fxReturnGroupOf(""))
    }

    // ---------------------------------------------------------- FX 机架

    @Test
    fun `rack slot number is NOT zero padded`() {
        // 与 ch/bus 相反: 真台 FX 槽号不补零, 补了台子不认
        assertEquals("/fx/1/type", X32.fxType(1))
        assertEquals("/fx/8/type", X32.fxType(8))
        assertEquals("/fx/3/on", X32.fxOn(3))
        assertEquals("/fx/4/mix", X32.fxMix(4))
        assertEquals("/fx/2/config/name", X32.fxName(2))
    }

    @Test
    fun `param number is zero padded`() {
        assertEquals("/fx/1/par/01", X32.fxParam(1, 1))
        assertEquals("/fx/3/par/12", X32.fxParam(3, 12))
        assertEquals("/fx/8/par/64", X32.fxParam(8, 64))
    }

    @Test
    fun `fx rack is not confused with fx return`() {
        // "/fx/".hasPrefix 不会误中 "/fxrtn/", 但反过来要小心
        assertEquals("/fx/1/type", X32.fxType(1))
        assertEquals("/fxrtn/01/mix/fader", X32.fxrtnFader(1))
        assertNull(X32.fxReturnGroupOf("fx"))
    }
}
