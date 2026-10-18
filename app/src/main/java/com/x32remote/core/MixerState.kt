package com.x32remote.core

/** 单路状态：推子行程 0..1（0.75 = 0 dB），静音 0/1 */
data class ChannelState(val fader: Float = 0.75f, val on: Int = 1)

/**
 * 台面状态模型（线程安全）。
 *
 * 分组：ch(1-32) bus(1-16) dca(1-8) auxin(1-6) usb(1-2) fxret(1-8) mtx(1-6)，main 固定 idx=0。
 * kind 为 "ch_fader" / "ch_on" 形式，分组由 kindGroup() 推导。
 */
class MixerState {

    private val lock = Any()

    private val groups = HashMap<String, HashMap<Int, ChannelState>>()
    private var mainState = ChannelState()
    private var sceneNo = 1

    /** "group:idx" -> 名称（最长 12 字符） */
    private val names = HashMap<String, String>()

    private val dcaMembers = HashMap<Int, MutableList<Int>>()

    init {
        for ((g, n) in X32.GROUP_COUNTS) {
            val m = HashMap<Int, ChannelState>(n * 2)
            for (i in 1..n) m[i] = ChannelState()
            groups[g] = m
        }
        for (d in 1..8) dcaMembers[d] = ArrayList()
        // 默认成员分配：每 8 路一组（DCA1→ch1-8, DCA2→ch9-16, ...）
        for (i in 1..32) dcaMembers[(i - 1) / 8 + 1]!!.add(i)
    }

    private fun nk(group: String, idx: Int) = "$group:$idx"

    /** 仅在持有 lock 时调用 */
    private fun cell(kind: String, idx: Int): ChannelState {
        val g = X32.kindGroup(kind)
        if (g == "main") return mainState
        return groups[g]?.get(idx) ?: ChannelState()
    }

    /** 仅在持有 lock 时调用 */
    private fun putCell(kind: String, idx: Int, v: ChannelState) {
        val g = X32.kindGroup(kind)
        if (g == "main") {
            mainState = v
        } else {
            groups[g]?.put(idx, v)
        }
    }

    // ------------------------------------------------------------ 读

    fun fader(kind: String, idx: Int): Float = synchronized(lock) { cell(kind, idx).fader }

    fun on(kind: String, idx: Int): Int = synchronized(lock) { cell(kind, idx).on }

    fun isFaderKind(kind: String) = kind.endsWith("_fader")

    /** 统一读取：推子返回 Float，静音返回 Int。找不到返回 null。 */
    fun value(kind: String, idx: Int): Any? = synchronized(lock) {
        val c = cell(kind, idx)
        if (kind.endsWith("_fader")) c.fader else c.on
    }

    fun snapshotGroup(group: String): Map<Int, ChannelState> = synchronized(lock) {
        if (group == "main") return mapOf(0 to mainState)
        groups[group]?.toMap() ?: emptyMap()
    }

    // ------------------------------------------------------------ 写

    /** 返回 true 表示值确实变了（用于抑制无意义刷新） */
    fun setFader(kind: String, idx: Int, v: Float): Boolean = synchronized(lock) {
        val c = cell(kind, idx)
        val nv = v.coerceIn(0f, 1f)
        if (c.fader == nv) return false
        putCell(kind, idx, c.copy(fader = nv))
        true
    }

    fun setOn(kind: String, idx: Int, on: Int): Boolean = synchronized(lock) {
        val c = cell(kind, idx)
        val nv = if (on != 0) 1 else 0
        if (c.on == nv) return false
        putCell(kind, idx, c.copy(on = nv))
        true
    }

    // ------------------------------------------------------------ 名称

    fun name(group: String, idx: Int): String = synchronized(lock) { names[nk(group, idx)] ?: "" }

    fun setName(group: String, idx: Int, n: String) {
        val trimmed = n.trim().take(12)
        synchronized(lock) {
            if (trimmed.isEmpty()) names.remove(nk(group, idx))
            else names[nk(group, idx)] = trimmed
        }
    }

    fun allNames(): Map<String, String> = synchronized(lock) { names.toMap() }

    // ------------------------------------------------------------ DCA

    fun dcaMask(ch: Int): Int = synchronized(lock) {
        var m = 0
        for (d in 1..8) if (dcaMembers[d]?.contains(ch) == true) m = m or (1 shl (d - 1))
        m
    }

    fun setDcaMask(ch: Int, mask: Int) {
        synchronized(lock) {
            for (d in 1..8) {
                val list = dcaMembers[d] ?: continue
                val want = (mask and (1 shl (d - 1))) != 0
                val has = list.contains(ch)
                if (want && !has) {
                    list.add(ch)
                    list.sort()
                } else if (!want && has) {
                    list.remove(ch)
                }
            }
        }
    }

    fun dcaMembers(d: Int): List<Int> = synchronized(lock) { dcaMembers[d]?.toList() ?: emptyList() }

    // ------------------------------------------------------------ 场景

    fun scene(): Int = synchronized(lock) { sceneNo }

    fun setScene(s: Int) {
        synchronized(lock) { sceneNo = s }
    }
}
