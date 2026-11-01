package dev.asl.ui.main

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import dev.asl.R
import dev.asl.core.Profile
import dev.asl.databinding.ItemTabBinding

/** A null [glyph] marks the Settings tab, drawn with the gear icon. */
data class TabEntry(val id: Long, val title: String, val glyph: String?, val selected: Boolean)

val Profile.glyph: String
    get() = icon?.takeIf { it.isNotBlank() } ?: if (distroId != null) "🐧" else name.take(1).uppercase()

class TabAdapter(private val onSelect: (Long) -> Unit, private val onClose: (Long) -> Unit) :
    ListAdapter<TabEntry, TabAdapter.Holder>(object : DiffUtil.ItemCallback<TabEntry>() {
        override fun areItemsTheSame(old: TabEntry, new: TabEntry) = old.id == new.id
        override fun areContentsTheSame(old: TabEntry, new: TabEntry) = old == new
    }) {

    class Holder(val binding: ItemTabBinding) : RecyclerView.ViewHolder(binding.root) {
        lateinit var entry: TabEntry
        val selectedBackground = MaterialShapeDrawable(
            ShapeAppearanceModel.builder()
                .setTopLeftCorner(CornerFamily.ROUNDED, 8 * binding.root.resources.displayMetrics.density)
                .setTopRightCorner(CornerFamily.ROUNDED, 8 * binding.root.resources.displayMetrics.density)
                .build(),
        ).apply { fillColor = ColorStateList.valueOf(binding.root.context.getColor(R.color.tab_active)) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)).apply {
            binding.root.setOnClickListener { onSelect(entry.id) }
            binding.tabClose.setOnClickListener { onClose(entry.id) }
        }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position).also { holder.entry = it }
        with(holder.binding) {
            tabGlyph.text = entry.glyph
            tabGlyph.isVisible = entry.glyph != null
            tabIcon.isVisible = entry.glyph == null
            tabTitle.text = entry.title
            tabTitle.setTextColor(root.context.getColor(if (entry.selected) R.color.text_primary else R.color.text_secondary))
            root.background = if (entry.selected) holder.selectedBackground else null
        }
    }
}
