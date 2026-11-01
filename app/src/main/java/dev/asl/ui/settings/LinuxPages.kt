package dev.asl.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.R as MaterialR
import dev.asl.R
import dev.asl.core.Asl
import dev.asl.core.CatalogEntry
import dev.asl.core.DISTRO_NAME
import dev.asl.core.Distro
import dev.asl.core.DOCUMENTS_AUTHORITY
import dev.asl.core.UsbDeviceInfo
import dev.asl.databinding.ItemSettingsDistroBinding
import dev.asl.databinding.ItemSettingsNoteBinding
import dev.asl.databinding.ItemSettingsUsbBinding
import dev.asl.databinding.SettingsDistrosHeaderBinding
import dev.asl.databinding.SettingsListBinding
import dev.asl.databinding.SettingsUsbHeaderBinding
import dev.asl.ui.MainViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private val TarballTypes = arrayOf("application/x-tar", "application/gzip", "application/x-gzip", "application/x-xz", "application/zstd", "application/octet-stream")

private data class DistroRow(val distro: Distro, val running: Boolean)

/** `wsl --list --verbose` with the rest of wsl.exe's distribution verbs. */
class DistrosFragment : Fragment(R.layout.settings_list) {
    private val vm by activityViewModels<MainViewModel>()
    private var exporting: Distro? = null
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-tar")) { uri ->
        val distro = exporting
        exporting = null
        if (uri != null && distro != null) {
            vm.runDistroTask(getString(R.string.settings_task_export, distro.name)) { Asl.distros.export(distro.id, uri, it) }
        }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        askName(getString(R.string.settings_import_title), uri.lastPathSegment.orEmpty().substringAfterLast('/').substringBefore('.'), getString(R.string.settings_import)) { name ->
            vm.runDistroTask(getString(R.string.settings_task_import, name)) { Asl.distros.import(name, uri, it) }
        }
    }

    private fun askName(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit) = inputDialog(
        title, getString(R.string.settings_distro_name), initial, confirm,
        validate = { name ->
            when {
                !DISTRO_NAME.matches(name) -> getString(R.string.settings_distro_name_invalid)
                Asl.distros.distros.value.any { it.name.equals(name, ignoreCase = true) } -> getString(R.string.settings_distro_exists)
                else -> null
            }
        },
        onConfirm = onConfirm,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        val list = SettingsListBinding.bind(view).list
        val header = SettingsDistrosHeaderBinding.inflate(layoutInflater, list, false)
        header.install.setOnClickListener {
            val loading = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_install_title)
                .setView(CircularProgressIndicator(context).apply { isIndeterminate = true; setPadding(0, 48, 0, 48) })
                .setNegativeButton(R.string.settings_close, null)
                .show()
            viewLifecycleOwner.lifecycleScope.launch {
                val catalog = runCatching { Asl.distros.catalog() }
                if (!loading.isShowing) return@launch
                loading.dismiss()
                val entries = catalog.getOrElse {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.settings_install_title)
                        .setMessage(it.message ?: getString(R.string.settings_catalog_failed))
                        .setPositiveButton(R.string.settings_close, null)
                        .show()
                    return@launch
                }
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.settings_install_title)
                    .setItems(entries.map { "${it.friendlyName}\n${it.id}" }.toTypedArray()) { _, i ->
                        val entry: CatalogEntry = entries[i]
                        askName(getString(R.string.settings_install_named, entry.friendlyName), entry.id, getString(R.string.settings_install)) { name ->
                            vm.runDistroTask(getString(R.string.settings_task_install, name)) { Asl.distros.install(entry, name, it) }
                        }
                    }
                    .setNegativeButton(R.string.settings_close, null)
                    .show()
            }
        }
        header.importTarball.setOnClickListener { importLauncher.launch(TarballTypes) }
        header.dismiss.setOnClickListener { vm.task.value = null }

        val items = ItemAdapter(ItemSettingsDistroBinding::inflate, { row: DistroRow -> row.distro.id }) { (distro, running) ->
            name.text = if (distro.isDefault) getString(R.string.settings_distro_default, distro.name) else distro.name
            details.text = listOfNotNull(
                getString(if (running) R.string.settings_running else R.string.settings_stopped),
                getString(R.string.settings_distro_user, distro.defaultUser),
                distro.source,
            ).joinToString(" · ")
            root.setOnClickListener { vm.openTab(vm.profileFor(distro)) }
            more.setOnClickListener { anchor ->
                val popup = PopupMenu(context, anchor)
                popup.inflate(R.menu.settings_distro)
                popup.menu.findItem(R.id.terminate).isEnabled = running
                popup.menu.findItem(R.id.set_default).isEnabled = !distro.isDefault
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.open -> vm.openTab(vm.profileFor(distro))
                        R.id.set_default -> vm.launch { Asl.distros.setDefault(distro.id) }
                        R.id.terminate -> vm.launch { Asl.vm.terminate(distro.id) }
                        R.id.set_user -> inputDialog(
                            getString(R.string.settings_default_user_title, distro.name), getString(R.string.settings_unix_user),
                            distro.defaultUser, getString(R.string.settings_save),
                        ) { user -> vm.launch { Asl.vm.setDefaultUser(distro.id, user.trim()) } }
                        R.id.open_files -> {
                            val root = DocumentsContract.buildRootUri(DOCUMENTS_AUTHORITY, distro.id)
                            val opened = listOf(
                                Intent(Intent.ACTION_VIEW).setDataAndType(root, DocumentsContract.Root.MIME_TYPE_ITEM),
                                Intent(Intent.ACTION_VIEW, root),
                            ).any { intent ->
                                try {
                                    startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    true
                                } catch (_: ActivityNotFoundException) {
                                    false
                                }
                            }
                            if (!opened) Toast.makeText(context, getString(R.string.settings_no_file_manager, distro.name), Toast.LENGTH_SHORT).show()
                        }
                        R.id.export -> {
                            exporting = distro
                            exportLauncher.launch("${distro.name}.tar")
                        }
                        R.id.unregister -> confirmDialog(
                            getString(R.string.settings_unregister_title, distro.name), getString(R.string.settings_unregister_message), getString(R.string.settings_unregister),
                        ) { vm.launch { Asl.distros.unregister(distro.id) } }
                    }
                    true
                }
                popup.show()
            }
        }
        list.itemAnimator = null
        list.adapter = ConcatAdapter(ViewAdapter(header.root), items)

        collectWhileStarted(combine(Asl.distros.distros, Asl.vm.runningDistros) { distros, running -> distros.map { DistroRow(it, it.id in running) } }) { rows ->
            header.empty.isVisible = rows.isEmpty()
            items.submitList(rows)
        }
        collectWhileStarted(
            vm.task.flatMapLatest { task ->
                if (task == null) flowOf(null) else combine(task.progress, task.error) { p, e -> Triple(task, p, e) }
            },
        ) { state ->
            header.task.isVisible = state != null
            val (task, p, error) = state ?: return@collectWhileStarted
            header.taskLabel.text = task.label
            header.taskDetail.text = error ?: p?.let { progress ->
                listOfNotNull(
                    progress.stage,
                    Formatter.formatShortFileSize(context, progress.bytes).takeIf { progress.bytes > 0 },
                    getString(R.string.settings_of_size, Formatter.formatShortFileSize(context, progress.total)).takeIf { progress.total > 0 },
                ).joinToString(" ")
            } ?: getString(R.string.settings_preparing)
            header.taskDetail.setTextColor(
                MaterialColors.getColor(header.taskDetail, if (error != null) androidx.appcompat.R.attr.colorError else MaterialR.attr.colorOnSurfaceVariant),
            )
            header.dismiss.isVisible = error != null
            header.progress.isVisible = error == null
            val determinate = p != null && p.total > 0
            if (header.progress.isIndeterminate == determinate) {
                header.progress.isVisible = false
                header.progress.isIndeterminate = !determinate
                header.progress.isVisible = error == null
            }
            if (p != null && determinate) header.progress.setProgressCompat((p.bytes * 1000 / p.total).toInt(), true)
        }
    }
}

/** usbipd-style device list. */
class UsbFragment : Fragment(R.layout.settings_list) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = SettingsListBinding.bind(view).list
        val header = SettingsUsbHeaderBinding.inflate(layoutInflater, list, false)
        val footer = ItemSettingsNoteBinding.inflate(layoutInflater, list, false)
        footer.root.setText(R.string.settings_usb_note)
        header.refresh.setOnClickListener { Asl.usb.refresh() }
        val items = ItemAdapter(ItemSettingsUsbBinding::inflate, UsbDeviceInfo::key) { d ->
            name.text = d.name.ifBlank { root.context.getString(R.string.settings_usb_unknown) }
            details.text = listOfNotNull(
                "%04x:%04x".format(d.vendorId, d.productId),
                root.context.getString(R.string.settings_usb_bus, d.busId),
                root.context.getString(
                    when {
                        d.attached -> R.string.settings_usb_attached
                        d.hasPermission -> R.string.settings_usb_not_attached
                        else -> R.string.settings_usb_permission
                    },
                ),
                d.error,
            ).joinToString(" · ")
            attach.isVisible = !d.attached
            detach.isVisible = d.attached
            attach.setOnClickListener { Asl.usb.attach(d.key) }
            detach.setOnClickListener { Asl.usb.detach(d.key) }
        }
        list.itemAnimator = null
        list.adapter = ConcatAdapter(ViewAdapter(header.root), items, ViewAdapter(footer.root))
        Asl.usb.refresh()
        collectWhileStarted(Asl.usb.devices) { devices ->
            header.empty.isVisible = devices.isEmpty()
            items.submitList(devices)
        }
    }
}
