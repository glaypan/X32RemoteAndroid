package com.x32remote.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 卡片动作校验与卡片管理：对应《共享协议规范.md》第 8 节。 */
class ShowEngineTest {

    private val noopSender = object : OscSender {
        override fun send(msg: OscMessage) {}
        override fun sendAll(msgs: List<OscMessage>) {}
        override fun setValue(kind: String, idx: Int, value: Float, source: String) {}
    }

    private fun engine() = ShowEngine(MixerState(), noopSender)

    // ---------------------------------------------------------- 白名单

    @Test
    fun `rejects unknown action kind`() {
        assertNull(engine().cleanAction(JSONObject().put("kind", "drop_table")))
    }

    @Test
    fun `rejects non object action`() {
        assertNull(engine().cleanAction(JSONObject()))
    }

    @Test
    fun `accepts every whitelisted kind`() {
        val e = engine()
        for (k in ShowEngine.ACTION_KINDS) {
            assertNotNull("kind=$k 应被接受", e.cleanAction(JSONObject().put("kind", k)))
        }
        assertEquals(12, ShowEngine.ACTION_KINDS.size)
    }

    // ---------------------------------------------------------- 目标列表

    @Test
    fun `mute targets are deduped and sorted`() {
        val a = engine().cleanAction(
            JSONObject().put("kind", "mute").put("chs", JSONArray(listOf(5, 2, 2, 9)))
        )!!
        assertEquals(listOf(2, 5, 9), a.chs)
    }

    @Test
    fun `mute targets are clamped to range`() {
        val a = engine().cleanAction(
            JSONObject().put("kind", "mute").put("chs", JSONArray(listOf(0, 99)))
        )!!
        assertEquals(listOf(1, 32), a.chs)
    }

    @Test
    fun `legacy single value form is accepted`() {
        val a = engine().cleanAction(JSONObject().put("kind", "mute").put("ch", 7))!!
        assertEquals(listOf(7), a.chs)
    }

    @Test
    fun `empty target list falls back to lower bound`() {
        val a = engine().cleanAction(
            JSONObject().put("kind", "mute").put("chs", JSONArray(listOf<Int>()))
        )!!
        assertEquals(listOf(1), a.chs)
    }

    @Test
    fun `bus mute clamps to sixteen`() {
        val a = engine().cleanAction(
            JSONObject().put("kind", "mute_bus").put("buses", JSONArray(listOf(3, 40)))
        )!!
        assertEquals(listOf(3, 16), a.buses)
    }

    // ---------------------------------------------------------- 参数钳制

    @Test
    fun `fade values are clamped`() {
        val a = engine().cleanAction(
            JSONObject().put("kind", "fade_ch").put("to", 5.0).put("duration", 999.0)
        )!!
        assertEquals(1f, a.to, 0.0001f)
        assertEquals(30f, a.duration, 0.0001f)
    }

    @Test
    fun `fade duration has lower bound`() {
        val a = engine().cleanAction(
            JSONObject().put("kind", "fade_all").put("duration", 0.01)
        )!!
        assertEquals(0.5f, a.duration, 0.0001f)
    }

    @Test
    fun `scene number is clamped`() {
        assertEquals(1, engine().cleanAction(JSONObject().put("kind", "scene").put("scene", -5))!!.scene)
        assertEquals(99, engine().cleanAction(JSONObject().put("kind", "scene").put("scene", 500))!!.scene)
    }

    @Test
    fun `wait duration is clamped`() {
        val a = engine().cleanAction(JSONObject().put("kind", "wait").put("duration", 600.0))!!
        assertEquals(60f, a.duration, 0.0001f)
    }

    @Test
    fun `curve falls back to linear when unknown`() {
        val a = engine().cleanAction(
            JSONObject().put("kind", "fade_all").put("curve", "wobble")
        )!!
        assertEquals("linear", a.curve)
    }

    @Test
    fun `known curves are preserved`() {
        for (c in X32.CURVES) {
            val a = engine().cleanAction(JSONObject().put("kind", "fade_all").put("curve", c))!!
            assertEquals(c, a.curve)
        }
    }

    // ---------------------------------------------------------- 时间轴偏移

    @Test
    fun `at offset is parsed when present`() {
        val a = engine().cleanAction(JSONObject().put("kind", "wait").put("at", 2.5))!!
        assertEquals(2.5f, a.at, 0.0001f)
    }

    @Test
    fun `absent at means sequential`() {
        val a = engine().cleanAction(JSONObject().put("kind", "wait"))!!
        assertTrue("未指定 at 应为负值", a.at < 0f)
    }

    @Test
    fun `at offset is clamped to ten minutes`() {
        val a = engine().cleanAction(JSONObject().put("kind", "wait").put("at", 9999.0))!!
        assertEquals(600f, a.at, 0.0001f)
    }

    // ---------------------------------------------------------- rel_db

    @Test
    fun `rel_db bus group clamps to sixteen and keeps group`() {
        val a = engine().cleanAction(
            JSONObject().put("kind", "rel_db").put("group", "bus")
                .put("buses", JSONArray(listOf(3, 20)))
        )!!
        assertEquals("bus", a.group)
        assertEquals(listOf(3, 16), a.buses)
    }

    @Test
    fun `rel_db ch group keeps channels`() {
        val a = engine().cleanAction(
            JSONObject().put("kind", "rel_db").put("group", "ch")
                .put("chs", JSONArray(listOf(1, 2)))
        )!!
        assertEquals("ch", a.group)
        assertEquals(listOf(1, 2), a.chs)
    }

    @Test
    fun `rel_db dca group clamps to eight`() {
        val a = engine().cleanAction(
            JSONObject().put("kind", "rel_db").put("group", "dca")
                .put("dcas", JSONArray(listOf(2, 99)))
        )!!
        assertEquals("dca", a.group)
        assertEquals(listOf(2, 8), a.dcas)
    }

    @Test
    fun `rel_db unknown group falls back to ch`() {
        val a = engine().cleanAction(
            JSONObject().put("kind", "rel_db").put("group", "matrix")
        )!!
        assertEquals("ch", a.group)
    }

    @Test
    fun `rel_db delta is clamped to plus minus thirty`() {
        assertEquals(
            30f,
            engine().cleanAction(JSONObject().put("kind", "rel_db").put("delta_db", 500.0))!!.deltaDb,
            0.0001f
        )
        assertEquals(
            -30f,
            engine().cleanAction(JSONObject().put("kind", "rel_db").put("delta_db", -500.0))!!.deltaDb,
            0.0001f
        )
    }

    // ---------------------------------------------------------- 卡片管理

    @Test
    fun `factory cards are present`() {
        val ids = engine().cards.map { it.id }
        assertTrue(ids.contains("dinner"))
        assertTrue(ids.contains("speech"))
        assertTrue(ids.contains("leave"))
        assertTrue(ids.contains("sync_down"))
        assertTrue(ids.contains("odd_even"))
    }

    @Test
    fun `pin toggle round trip`() {
        val e = engine()
        assertFalse(e.byId("dinner")!!.pinned)
        e.togglePin("dinner")
        assertTrue(e.byId("dinner")!!.pinned)
    }

    @Test
    fun `export then import round trips all cards`() {
        val src = engine()
        val json = src.cardsToJson()

        val dst = engine()
        val imported = dst.importCards(json)

        assertEquals(src.cards.size, imported)
        assertEquals(src.cards.size * 2, dst.cards.size) // 出厂卡片 + 导入
    }

    @Test
    fun `exported json keeps action kinds`() {
        val src = engine()
        val json = src.cardsToJson()
        var total = 0
        for (i in 0 until json.length()) {
            val o = json.getJSONObject(i)
            total += o.getJSONArray("actions").length()
        }
        // 出厂卡片共 8 张、16 条动作
        assertEquals(8, json.length())
        assertEquals(16, total)
    }

    @Test
    fun `conflict check is inert without a running card`() {
        // 没有卡片在驱动时，手动干预不应产生任何中断标记
        val e = engine()
        val state = MixerState()
        e.checkManualTakeover("ch_fader", 1)
        assertFalse(e.isDriven("ch_fader", 1))
        assertEquals(0.75f, state.fader("ch_fader", 1), 0.0001f)
    }

    // ---------------------------------------------------------- 执行语义（对齐服务端）

    /** 记录所有写入的 sender，用于断言"写了什么" */
    private class RecSender : OscSender {
        data class W(val kind: String, val idx: Int, val value: Float, val source: String)
        val writes = java.util.Collections.synchronizedList(ArrayList<W>())
        val raw = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun send(msg: OscMessage) { raw.add(msg.address) }
        override fun sendAll(msgs: List<OscMessage>) { msgs.forEach { raw.add(it.address) } }
        override fun setValue(kind: String, idx: Int, value: Float, source: String) {
            writes.add(W(kind, idx, value, source))
        }
    }

    /** 把一组动作包成单张 `t` 卡片（走 importCards，因为 cards 是 private set） */
    private fun engineWith(s: OscSender, vararg actions: CardAction): Pair<ShowEngine, MixerState> {
        val state = MixerState()
        // 单测下必须注入纯 JVM 时钟：android.os.SystemClock 在 JVM 单测里是抛异常的空桩
        val e = ShowEngine(state, s) { System.nanoTime() / 1_000_000 }
        val arr = JSONArray().put(JSONObject().apply {
            put("id", "t")
            put("name", "测试")
            put("actions", JSONArray().apply {
                actions.forEach { a -> put(actionToJson(a)) }
            })
        })
        e.importCards(arr)
        return e to state
    }

    /** 建一个用了 JVM 时钟的引擎（不预置卡片） */
    private fun jvmEngine(s: OscSender, state: MixerState = MixerState()) =
        ShowEngine(state, s) { System.nanoTime() / 1_000_000 }

    /** 把 CardAction 序列化成服务端 JSON 形态（用引擎自己的编码器，避免手抄字段名） */
    private fun actionToJson(a: CardAction): JSONObject {
        // ShowEngine 内部有 action→JSON 的编码；这里直接复用同款字段名
        val o = JSONObject().apply {
            put("kind", a.kind)
            put("duration", a.duration.toDouble())
            put("curve", a.curve)
            put("to", a.to.toDouble())
        }
        if (a.chs.isNotEmpty()) o.put("chs", JSONArray(a.chs))
        if (a.buses.isNotEmpty()) o.put("buses", JSONArray(a.buses))
        if (a.kind == "fade_from") {
            o.put("ch", a.singleCh); o.put("frm", a.frm.toDouble())
        }
        if (a.kind == "fade_from_bus") {
            o.put("bus", a.singleBus); o.put("frm", a.frm.toDouble())
        }
        if (a.at >= 0f) o.put("at", a.at.toDouble())
        return o
    }

    /** 轮询等待条件成立，避免固定 sleep 造成的 flaky */
    private fun waitUntil(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    @Test
    fun `wait writes nothing`() {
        val s = RecSender()
        val (e, _) = engineWith(s, CardAction("wait", duration = 0.5f))
        e.run("t")
        // wait 只占时间轴，不产生任何 OSC
        Thread.sleep(900)
        e.stopAll()
        assertTrue("wait 不应写任何值，实际 ${s.writes}", s.writes.isEmpty())
        assertTrue("wait 不应发任何消息，实际 ${s.raw}", s.raw.isEmpty())
    }

    @Test
    fun `wait does not block parallel actions`() {
        // wait(2s) 与 at=0 的 mute 并行：mute 必须立刻发出，而不是被 sleep 卡住
        val s = RecSender()
        val (e, _) = engineWith(s,
            CardAction("wait", duration = 2.0f),
            CardAction("mute", chs = listOf(3), at = 0f)
        )
        e.run("t")
        val ok = waitUntil(1500) { s.writes.any { it.kind == "ch_on" && it.idx == 3 } }
        e.stopAll()
        assertTrue("wait 期间并行 mute 应在 1.5s 内发出（双重计时/阻塞会失败）", ok)
    }

    @Test
    fun `wait advances cursor so following action is delayed`() {
        val s = RecSender()
        // wait 1s 之后的 mute 必须在 ~1s 之后才发
        val (e, _) = engineWith(s,
            CardAction("wait", duration = 1.0f),
            CardAction("mute", chs = listOf(5))
        )
        e.run("t")
        Thread.sleep(600)
        val early = s.writes.any { it.kind == "ch_on" && it.idx == 5 }
        val ok = waitUntil(2000) { s.writes.any { it.kind == "ch_on" && it.idx == 5 } }
        e.stopAll()
        assertFalse("0.6s 时后置 mute 不该已发出（说明 cursor 没推进）", early)
        assertTrue("1s 后后置 mute 应已发出", ok)
    }

    @Test
    fun `unmute_all touches every group`() {
        val s = RecSender()
        val (e, _) = engineWith(s, CardAction("unmute_all"))
        e.run("t")
        waitUntil(2000) { s.writes.count { it.value == 1f } >= 79 }
        e.stopAll()

        val on = s.writes.filter { it.kind.endsWith("_on") && it.value == 1f }
        val byKind = on.groupBy { it.kind }
        assertEquals("ch_on 应 32 路", 32, byKind["ch_on"]?.size ?: 0)
        assertEquals("bus_on 应 16 路", 16, byKind["bus_on"]?.size ?: 0)
        assertEquals("dca_on 应 8 路", 8, byKind["dca_on"]?.size ?: 0)
        assertEquals("auxin_on 应 6 路", 6, byKind["auxin_on"]?.size ?: 0)
        assertEquals("usb_on 应 2 路", 2, byKind["usb_on"]?.size ?: 0)
        assertEquals("fxret_on 应 8 路", 8, byKind["fxret_on"]?.size ?: 0)
        assertEquals("mtx_on 应 6 路", 6, byKind["mtx_on"]?.size ?: 0)
        assertEquals("main_on 应 1 路", 1, byKind["main_on"]?.size ?: 0)
        assertEquals("共 79 路", 79, on.size)
        assertEquals("main 的 idx 应为 0", 0, byKind["main_on"]!!.first().idx)
        assertTrue("全部应为 source=action", on.all { it.source == "action" })
    }

    @Test
    fun `fade_from presets fader to frm before fading`() {
        val s = RecSender()
        val state = MixerState()
        state.setFader("ch_fader", 7, 0.75f)   // 当前值刻意不等于 frm
        val e = jvmEngine(s, state)
        e.importCards(JSONArray().put(JSONObject().apply {
            put("id", "t")
            put("name", "测试")
            put("actions", JSONArray().put(actionToJson(
                CardAction("fade_from", singleCh = 7, frm = 0.1f, to = 0.9f, duration = 1.0f)
            )))
        }))
        e.run("t")
        val got = waitUntil(1500) { s.writes.any { it.kind == "ch_fader" && it.idx == 7 } }
        val v0 = s.writes.first { it.kind == "ch_fader" && it.idx == 7 }.value
        Thread.sleep(1200)
        e.stopAll()
        assertTrue("fade_from 应先写 frm", got)
        assertEquals("首笔必须置到 frm=0.1（否则 frm 形同虚设）", 0.1f, v0, 0.02f)
    }

    @Test
    fun `fade_from_bus presets fader to frm`() {
        val s = RecSender()
        val state = MixerState()
        state.setFader("bus_fader", 4, 0.8f)
        val e = jvmEngine(s, state)
        e.importCards(JSONArray().put(JSONObject().apply {
            put("id", "t")
            put("name", "测试")
            put("actions", JSONArray().put(actionToJson(
                CardAction("fade_from_bus", singleBus = 4, frm = 0.2f, to = 0.95f, duration = 1.0f)
            )))
        }))
        e.run("t")
        waitUntil(1500) { s.writes.any { it.kind == "bus_fader" && it.idx == 4 } }
        val v0 = s.writes.first { it.kind == "bus_fader" && it.idx == 4 }.value
        Thread.sleep(1200)
        e.stopAll()
        assertEquals("首笔必须置到 frm=0.2", 0.2f, v0, 0.02f)
    }

    @Test
    fun `chain continues to next card`() {
        val s = RecSender()
        val state = MixerState()
        val e = jvmEngine(s, state)
        e.importCards(JSONArray().apply {
            put(JSONObject().apply {
                put("id", "a"); put("name", "第一张"); put("next", "b")
                put("actions", JSONArray().put(actionToJson(CardAction("mute", chs = listOf(1)))))
            })
            put(JSONObject().apply {
                put("id", "b"); put("name", "第二张")
                put("actions", JSONArray().put(actionToJson(CardAction("mute", chs = listOf(2)))))
            })
        })
        e.run("a")
        // 等第二张卡片产生写入
        val ok = waitUntil(3000) { s.writes.any { it.idx == 2 } }
        e.stopAll()
        assertTrue("卡片链应跑到第二张（旧实现里 finishChain 调 run() 会 stopAll 掉自己）", ok)
    }
}
