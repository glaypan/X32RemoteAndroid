package com.x32remote.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.x32remote.core.FxGallery
import com.x32remote.databinding.ItemFxGalleryBinding

/** 图鉴网格：一格一个效果器，图来自 assets/fx（真机界面截图）。 */
class FxGalleryAdapter : RecyclerView.Adapter<FxGalleryAdapter.VH>() {

    class VH(val b: ItemFxGalleryBinding) : RecyclerView.ViewHolder(b.root)

    private var items: List<FxGallery.Item> = emptyList()

    fun submit(list: List<FxGallery.Item>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFxGalleryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        val b = holder.b
        b.tvName.text = it.title
        b.tvSub.text = it.sub.orEmpty()
        b.tvSub.visibility = if (it.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
        b.tvTag.text = it.tag.orEmpty()
        b.tvTag.visibility = if (it.tag.isNullOrEmpty()) View.GONE else View.VISIBLE
        b.tvTag.setTextColor(if (it.heavy) 0xFFFFB74D.toInt() else 0xFF8A94A6.toInt())

        val bmp = FxGallery.bitmap(b.root.context, it.img)
        if (bmp != null) {
            b.ivShot.setImageBitmap(bmp)
        } else {
            b.ivShot.setImageDrawable(null)
        }
    }
}
