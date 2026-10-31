package com.x32remote.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.x32remote.core.AppClient
import com.x32remote.core.FxCalibration
import com.x32remote.databinding.ItemFxSlotBinding

/**
 * 效果器机架列表：8 个 FX 槽。
 *
 * 每行显示算法中文名 / 分类 / 参数个数 / 开关态；点击进详情页。
 * 算法名来自本地标定表（OSC 只回枚举名如 "HALL"，中文名得自己带）。
 */
class FxRackAdapter(
    private val onOpen: (slot: Int) -> Unit
) : RecyclerView.Adapter<FxRackAdapter.VH>() {

    class VH(val b: ItemFxSlotBinding) : RecyclerView.ViewHolder(b.root)

    private val client = AppClient.client

    /** 状态有变更时调用（已由客户端节流） */
    fun refresh() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    override fun getItemCount(): Int = 8

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFxSlotBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val slot = position + 1
        val b = holder.b
        val st = client.state.fxSlot(slot)
        val lib = FxCalibration.lib

        b.tvSlot.text = "FX $slot"

        val algo = lib?.algo(st.algoType)
        val name = client.state.name("fxret", slot).ifBlank { st.name }

        if (st.algoType.isEmpty()) {
            b.tvAlgo.text = if (name.isBlank()) "空槽" else name
            b.tvSub.text = "点右侧选择算法"
            b.tvAlgo.setTextColor(0xFF8A94A6.toInt())
        } else {
            b.tvAlgo.text = if (name.isBlank()) {
                algo?.label ?: st.algoType
            } else {
                "${algo?.label ?: st.algoType} · $name"
            }
            b.tvAlgo.setTextColor(0xFFE8EDF5.toInt())
            val cat = algo?.let { lib?.catLabel?.get(it.cat) ?: it.cat } ?: ""
            b.tvSub.text = buildString {
                append(cat)
                append(" · ")
                append(st.params.size)
                append("/")
                append(algo?.n ?: st.params.size)
                append(" 参数")
                if (algo?.heavy == true) append(" · 仅 FX1-4")
            }
        }

        val on = st.fxOn != 0
        b.tvState.text = if (on) "ON" else "OFF"
        b.tvState.setTextColor(
            if (on) 0xFF22D3A8.toInt() else 0xFF8A94A6.toInt()
        )

        b.root.setOnClickListener { onOpen(slot) }
    }
}
