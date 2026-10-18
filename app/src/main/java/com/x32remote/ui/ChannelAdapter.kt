package com.x32remote.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.x32remote.core.ChannelState
import com.x32remote.core.X32
import com.x32remote.core.X32Client

/**
 * 通道列表。每行一个 [ChannelStripView]。
 *
 * 刷新策略：状态变化时调用 [refresh]，通过 notifyItemRangeChanged 只重绑**可见项**，
 * 避免 48 项全量重建。
 */
class ChannelAdapter(
    private val client: X32Client,
    private val onNameClick: (kind: String, idx: Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    class VH(val strip: ChannelStripView) : RecyclerView.ViewHolder(strip)

    /** 当前分组：ch / bus / dca / auxin / usb / fxret / mtx / main */
    var group: String = "ch"
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    private var kinds: List<Pair<String, Int>> = emptyList()

    init {
        rebuild()
    }

    private fun rebuild() {
        kinds = buildList {
            if (group == "main") {
                add("main_fader" to 0)
            } else {
                val n = X32.GROUP_COUNTS[group] ?: 0
                for (i in 1..n) add("${group}_fader" to i)
            }
        }
        notifyDataSetChanged()
    }

    /** 状态有变更时调用（已由客户端 100ms 节流） */
    fun refresh() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE)
    }

    override fun getItemCount(): Int = kinds.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val strip = ChannelStripView(parent.context)
        val h = (58f * parent.resources.displayMetrics.density).toInt()
        strip.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT, h
        )
        strip.callback = object : ChannelStripView.Callback {
            override fun onFader(kind: String, idx: Int, v: Float) {
                client.setValue(kind, idx, v, "manual")
            }

            override fun onMute(kind: String, idx: Int) {
                val group = X32.kindGroup(kind)
                val onKind = "${group}_on"
                val cur = client.state.on(onKind, idx)
                client.setValue(onKind, idx, if (cur != 0) 0f else 1f, "manual")
            }

            override fun onName(kind: String, idx: Int) {
                onNameClick(kind, idx)
            }
        }
        return VH(strip)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (kind, idx) = kinds[position]
        val g = X32.kindGroup(kind)

        val st: ChannelState = client.state.snapshotGroup(g)[idx] ?: ChannelState()
        val name = client.state.name(g, idx)

        holder.strip.bind(
            kind = kind,
            idx = idx,
            name = name,
            fader = st.fader,
            muted = st.on == 0,
            level = meterFor(g, idx),
            driven = client.show.isDriven(kind, idx),
            rowIndex = position
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        // 轻量刷新：只同步数值，不重建文字
        val (kind, idx) = kinds[position]
        val g = X32.kindGroup(kind)
        val st = client.state.snapshotGroup(g)[idx] ?: ChannelState()
        if (!holder.strip.matches(kind, idx)) {
            onBindViewHolder(holder, position)
            return
        }
        holder.strip.updateLevel(meterFor(g, idx))
        holder.strip.syncFader(st.fader)
        holder.strip.syncMute(st.on == 0)
        holder.strip.syncName(client.state.name(g, idx))
        holder.strip.syncDriven(client.show.isDriven(kind, idx))
    }

    /** 电平数组布局：前 32 个是 ch1-32，其后 16 个是 bus1-16 */
    private fun meterFor(group: String, idx: Int): Float {
        val m = client.meters
        val raw = when (group) {
            "ch" -> m.getOrNull(idx - 1)
            "bus" -> m.getOrNull(32 + idx - 1)
            else -> null
        } ?: return 0f
        return X32.meterToLevel(raw)
    }

    companion object {
        private const val PAYLOAD_STATE = "state"
    }
}
