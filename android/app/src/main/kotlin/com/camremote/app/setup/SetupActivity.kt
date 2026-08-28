package com.camremote.app.setup

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.camremote.app.R
import com.camremote.app.di.AppContainer
import com.camremote.app.service.RemoteControlService
import com.camremote.core.protocol.PermissionStatus
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one-time setup screen, and the app's only user interface.
 *
 * The assignment asks for an application with no GUI, which this respects in the sense that matters:
 * there is nothing here for operating the camera. Every command arrives over the network. What is
 * here exists because Android offers no other route — runtime permissions can only be requested from
 * an activity, "Display over other apps" is granted through a Settings screen, and with adb
 * deliberately excluded from the design there has to be somewhere the operator can read the agent's
 * address and hand over its token.
 */
class SetupActivity : AppCompatActivity() {

    private val container by lazy { AppContainer(applicationContext) }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }

    private lateinit var serverSwitch: MaterialSwitch
    private lateinit var addressText: TextView
    private lateinit var tokenText: TextView
    private lateinit var cameraButton: Button
    private lateinit var notificationsButton: Button
    private lateinit var overlayButton: Button
    private lateinit var batteryButton: Button
    private lateinit var pairButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        serverSwitch = findViewById(R.id.switch_server)
        addressText = findViewById(R.id.text_address)
        tokenText = findViewById(R.id.text_token)
        cameraButton = findViewById(R.id.button_camera)
        notificationsButton = findViewById(R.id.button_notifications)
        overlayButton = findViewById(R.id.button_overlay)
        batteryButton = findViewById(R.id.button_battery)
        pairButton = findViewById(R.id.button_pair)

        serverSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == container.config.isEnabled) return@setOnCheckedChangeListener
            container.config.isEnabled = isChecked
            if (isChecked) RemoteControlService.start(this) else RemoteControlService.stop(this)
            render()
        }

        cameraButton.setOnClickListener { requestPermissions.launch(arrayOf(Manifest.permission.CAMERA)) }

        notificationsButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }

        overlayButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()),
            )
        }

        batteryButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()),
            )
        }

        pairButton.setOnClickListener { openPairingWindow() }

        findViewById<Button>(R.id.button_regenerate).setOnClickListener {
            container.config.regenerateToken()
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions are granted on other screens, so the state is re-read every time this one
        // comes back rather than tracked.
        render()
    }

    private fun openPairingWindow() {
        container.pairingWindow.open()
        lifecycleScope.launch {
            while (container.pairingWindow.isOpen()) {
                val seconds = (container.pairingWindow.remainingMillis() + 999) / 1000
                pairButton.text = getString(R.string.setup_pair_open, seconds)
                delay(500)
            }
            render()
        }
    }

    private fun render() {
        val status = container.permissions.status()

        serverSwitch.isChecked = container.config.isEnabled
        addressText.text = if (container.config.isEnabled) {
            getString(R.string.setup_address, LocalAddresses.firstLanIpv4() ?: "?", container.config.port)
        } else {
            getString(R.string.setup_address_off)
        }

        cameraButton.text = label(R.string.setup_grant_camera, status.camera)
        notificationsButton.text = label(R.string.setup_grant_notifications, status.notifications)
        overlayButton.text = label(R.string.setup_grant_overlay, status.canDrawOverlays)
        batteryButton.text = label(R.string.setup_grant_battery, status.ignoringBatteryOptimizations)

        pairButton.text = getString(R.string.setup_pair)
        tokenText.text = getString(R.string.setup_token, container.config.token)

        renderReadiness(status)
    }

    private fun renderReadiness(status: PermissionStatus) {
        findViewById<TextView>(R.id.text_permission_help).text = if (status.isComplete) {
            getString(R.string.setup_ready)
        } else {
            getString(R.string.setup_overlay_explainer)
        }
    }

    private fun label(resource: Int, granted: Boolean): String =
        getString(if (granted) R.string.setup_granted else R.string.setup_grant, getString(resource))

    private fun packageUri(): Uri = Uri.parse("package:$packageName")
}
