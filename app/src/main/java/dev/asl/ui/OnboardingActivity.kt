package dev.asl.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.color.MaterialColors
import dev.asl.R
import dev.asl.databinding.ItemOnboardingStepBinding
import dev.asl.databinding.OnboardingActivityBinding
import dev.asl.vm.allFilesAccessIntent
import dev.asl.vm.batteryExemptionIntent
import dev.asl.vm.ignoresBatteryOptimizations
import dev.asl.vm.missingVmPermissions
import dev.asl.vm.notificationPermissionGranted
import dev.asl.vm.notificationSettingsIntent
import dev.asl.vm.notificationsEnabled
import dev.asl.vm.openSettingsScreen
import dev.asl.vm.vmGrantCommands

/**
 * First-run setup: walks through the permissions the VM needs to start and to keep running in the
 * background. Each step shows whether it is already done and refreshes when the user comes back
 * from system settings.
 */
class OnboardingActivity : AppCompatActivity() {
    private class Step(
        val binding: ItemOnboardingStepBinding,
        val importance: Int,
        val done: () -> Boolean,
    )

    private lateinit var binding: OnboardingActivityBinding
    private val steps = mutableListOf<Step>()
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Denied with no rationale means Android may not have shown a dialog at all; say why nothing happened.
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            Toast.makeText(this, R.string.onboarding_notifications_blocked, Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = OnboardingActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val commands = vmGrantCommands()
        step(
            R.string.onboarding_vm_title, R.string.onboarding_vm_body, R.string.onboarding_vm_action, R.string.onboarding_required,
            done = { missingVmPermissions().isEmpty() },
            code = commands,
        ) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", commands))
            Toast.makeText(this, R.string.settings_copied, Toast.LENGTH_SHORT).show()
        }
        step(
            R.string.onboarding_notifications_title, R.string.onboarding_notifications_body, R.string.onboarding_notifications_action,
            R.string.onboarding_recommended, done = { notificationsEnabled() },
        ) {
            // Granted but not enabled means they're off in settings, for the app or the VM channel. Settings is
            // also the only way left once Android stops showing the permission dialog.
            val prefs = prefs(this)
            val dialogExhausted = prefs.getBoolean(NOTIFICATIONS_ASKED, false) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            if (notificationPermissionGranted() || dialogExhausted) {
                openSettingsScreen(notificationSettingsIntent())
            } else {
                prefs.edit { putBoolean(NOTIFICATIONS_ASKED, true) }
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        step(
            R.string.onboarding_battery_title, R.string.onboarding_battery_body, R.string.onboarding_battery_action,
            R.string.onboarding_recommended, done = { ignoresBatteryOptimizations() },
        ) { openSettingsScreen(batteryExemptionIntent()) }
        step(
            R.string.onboarding_storage_title, R.string.onboarding_storage_body, R.string.onboarding_storage_action,
            R.string.onboarding_optional, done = { Environment.isExternalStorageManager() },
        ) { openSettingsScreen(allFilesAccessIntent()) }

        binding.onboardingDone.setOnClickListener { finishSetup() }
        binding.onboardingSkip.setOnClickListener { finishSetup() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishSetup()
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun step(title: Int, body: Int, action: Int, importance: Int, done: () -> Boolean, code: String? = null, onAction: () -> Unit) {
        val b = ItemOnboardingStepBinding.inflate(layoutInflater, binding.onboardingSteps, true)
        b.title.setText(title)
        b.body.setText(body)
        b.action.setText(action)
        b.action.setOnClickListener { onAction() }
        b.code.text = code
        steps += Step(b, importance, done)
    }

    private fun refresh() {
        var allDone = true
        steps.forEach { step ->
            val done = step.done()
            allDone = allDone && done
            step.binding.status.text = getString(if (done) R.string.onboarding_granted else step.importance)
            step.binding.status.setTextColor(
                MaterialColors.getColor(step.binding.status, if (done) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant),
            )
            step.binding.action.isVisible = !done
            step.binding.code.isVisible = !done && !step.binding.code.text.isNullOrEmpty()
        }
        binding.onboardingSkip.isVisible = !allDone
    }

    private fun finishSetup() {
        prefs(this).edit { putBoolean(ONBOARDED, true) }
        finish()
    }

    companion object {
        private const val PREFS = "ui"
        private const val ONBOARDED = "onboarded"
        private const val NOTIFICATIONS_ASKED = "notificationsAsked"

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun needed(context: Context) = !prefs(context).getBoolean(ONBOARDED, false)

        fun start(context: Context) = context.startActivity(Intent(context, OnboardingActivity::class.java))
    }
}
