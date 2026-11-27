package com.x32remote.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/api/fxcaps` 的解析与"可写性"语义。
 *
 * 口径与服务端 `ConsoleProfile.fx_param_caps()` + 模拟器 `test_wing_params.py`
 * 的 F 层一致：`all=true` 全可写；`all=false` 时按槽查清单，**空清单 = 一项都不能写**；
 * 槽号缺失（服务端本该列全）按放行处理，避免把功能误关。iOS 侧
 * `FxParamCapsTests.swift` 断言同一组口径。
 */
class FxParamCapsTest {

    private fun parse(json: String) = FxParamCaps.parse(JSONObject(json))

    @Test
    fun `X32 没有未标定概念`() {
        val c = parse("""{"console":"x32","all":true,"slots":{}}""")
        assertTrue(c.all)
        assertEquals("x32", c.console)
        assertTrue(c.allows(1, 99))
        assertEquals(0, c.blockedCount(1, listOf(1, 2, 3)))
    }

    @Test
    fun `WING 按槽给清单 缺的 par 置灰`() {
        // 真实形状: WING 的 ROOM 槽实测可写 [1,2,3,4,5,7..16]（缺 par06 = level）
        val c = parse(
            """
            {"console":"wing","all":false,"slots":{
              "1":[1,2,3,4,5,7,8,9,10,11,12,13,14,15,16],
              "2":[],
              "3":[1]
            }}
            """.trimIndent()
        )
        assertFalse(c.all)
        assertEquals(15, c.slots[1]?.size)
        assertTrue(c.allows(1, 1))
        assertFalse(c.allows(1, 6))
        assertEquals(1, c.blockedCount(1, (1..16).toList()))

        // 空清单 = 这个槽一个参数都不能写
        assertTrue(c.slots[2]?.isEmpty() == true)
        assertFalse(c.allows(2, 1))
        assertEquals(2, c.blockedCount(2, listOf(1, 2)))
    }

    @Test
    fun `槽号缺失按放行处理`() {
        val c = parse("""{"console":"wing","all":false,"slots":{"1":[1]}}""")
        assertTrue(c.allows(9, 42))
        assertEquals(0, c.blockedCount(9, listOf(1, 2, 3)))
    }

    @Test
    fun `缺 all 字段退化成全可写`() {
        // 老服务端没有这个接口 / 字段 → 不能误灰
        val c = parse("""{"slots":{"1":[]}}""")
        assertTrue(c.all)
        assertTrue(c.allows(1, 1))
    }

    @Test
    fun `控台名缺失时提示文案兜底`() {
        assertEquals("当前控台", parse("""{"all":true}""").consoleLabel)
        assertEquals("WING", parse("""{"console":"wing","all":false}""").consoleLabel)
    }

    @Test
    fun `地址拼接`() {
        assertEquals("http://192.168.1.9:8080", FxParamCaps.baseUrl("192.168.1.9", 8080))
    }
}
