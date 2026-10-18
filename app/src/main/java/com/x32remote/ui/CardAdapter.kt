package com.x32remote.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.x32remote.core.ShowCard
import com.x32remote.core.ShowProgress
import com.x32remote.databinding.ItemCardBinding
import java.util.Locale

/**
 * 演出卡片列表。
 *
 * 含**防误触**：会影响全体推子的卡片（fade_all）必须按住 0.8 秒才执行，
 * 避免演出中误触把全场推子拉到底。
 */
class CardAdapter(
    private val onRun: (ShowCard) -> Unit,
    private val onPin: (ShowCard) -> Unit
) : RecyclerView.Adapter<CardAdapter.VH>() {

    class VH(val b: ItemCardBinding) : RecyclerView.ViewHolder(b.root)

    private var items: List<ShowCard> = emptyList()
    private var progress: ShowProgress = ShowProgress()
    private var downAt = 0L

    fun submit(cards: List<ShowCard>) {
        items = cards.sortedWith(compareByDescending<ShowCard> { it.pinned }.thenBy { it.name })
        notifyDataSetChanged()
    }

    fun submitProgress(p: ShowProgress) {
        progress = p
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]
        val b = h.b

        b.tvCardName.text = c.name
        b.tvCardDesc.text = c.desc
        b.tvPinBadge.visibility = if (c.pinned) View.VISIBLE else View.GONE

        val running = progress.isRunning && progress.runningId == c.id
        b.tvCardProgress.visibility = if (running) View.VISIBLE else View.GONE
        b.tvCardProgress.text = String.format(Locale.US, "%.0f%%", progress.progress * 100)

        val stepVisible = running && progress.step.isNotEmpty()
        b.tvCardStep.visibility = if (stepVisible) View.VISIBLE else View.GONE
        b.tvCardStep.text = progress.step

        val danger = c.actions.any { it.kind == "fade_all" }
        val normalAlpha = if (danger) 0.85f else 1f
        b.btnRun.alpha = normalAlpha

        b.btnRun.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = System.currentTimeMillis()
                    v.alpha = 0.55f
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.alpha = normalAlpha
                    val held = System.currentTimeMillis() - downAt
                    if (!danger || held >= 800L) {
                        onRun(c)
                    } else {
                        Toast.makeText(
                            v.context,
                            "「${c.name}」会影响全体推子，请按住 0.8 秒确认",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = normalAlpha
                    true
                }

                else -> false
            }
        }

        b.btnPin.setOnClickListener { onPin(c) }
    }
}
