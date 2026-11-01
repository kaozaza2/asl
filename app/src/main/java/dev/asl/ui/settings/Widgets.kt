package dev.asl.ui.settings

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.asl.databinding.SettingsDialogInputBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

const val ARG = "arg"

fun <T> Fragment.collectWhileStarted(flow: Flow<T>, action: suspend (T) -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collect(action) }
    }
}

class ItemAdapter<T : Any, B : ViewBinding>(
    private val inflate: (LayoutInflater, ViewGroup, Boolean) -> B,
    key: (T) -> Any,
    private val bind: B.(T) -> Unit,
) : ListAdapter<T, ItemAdapter.Holder<B>>(object : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(oldItem: T, newItem: T) = key(oldItem) == key(newItem)

    @SuppressLint("DiffUtilEquals")
    override fun areContentsTheSame(oldItem: T, newItem: T) = oldItem == newItem
}) {
    class Holder<B : ViewBinding>(val binding: B) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder<B>, position: Int) = holder.binding.bind(getItem(position))
}

/** A single fixed view, for headers and footers around an [ItemAdapter] in a ConcatAdapter. */
class ViewAdapter(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(view) {}
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
}

fun Fragment.inputDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    validate: (String) -> String? = { null },
    onConfirm: (String) -> Unit,
) {
    val b = SettingsDialogInputBinding.inflate(layoutInflater)
    b.layout.hint = label
    b.edit.setText(initial)
    val dialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setView(b.root)
        .setPositiveButton(confirm) { _, _ -> onConfirm(b.edit.text.toString().trim()) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    val check = {
        val text = b.edit.text.toString().trim()
        val error = validate(text)
        b.layout.error = error
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = error == null && text.isNotEmpty()
    }
    check()
    b.edit.doAfterTextChanged { check() }
}

fun Fragment.confirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit) {
    val dialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(confirm) { _, _ -> onConfirm() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).let {
        it.setTextColor(MaterialColors.getColor(it, androidx.appcompat.R.attr.colorError))
    }
}
