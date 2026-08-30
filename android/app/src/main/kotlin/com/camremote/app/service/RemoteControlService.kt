package com.camremote.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import com.camremote.app.R
import com.camremote.app.adapter.LocalAddresses
import com.camremote.app.adapter.NsdServiceAdvertiser
import com.camremote.app.di.AppContainer
import com.camremote.app.setup.LaunchActivity
import com.camremote.app.transport.http.HttpCommandServer
import com.camremote.app.transport.http.commandApi
import com.camremote.core.protocol.HealthResponse

/**
 * The agent itself: a foreground service that owns the HTTP server, the mDNS advertisement and the
 * Wi-Fi lock, and runs for as long as Android lets it — there is no on/off switch, because there is
 * no screen to put one on.
 *
 * A service rather than an activity because the app has no control UI by design — there is nothing
 * for a user to look at while commands are being served; [LaunchActivity] is a permission trampoline,
 * not a dashboard. In an app with a UI this service is roughly the role a ViewModel would play: it
 * is the thing that outlives any screen and owns the coroutine scope. Extending [LifecycleService]
 * gives it the `LifecycleOwner` that CameraX needs to bind to, which is the other reason a plain
 * `Service` would not do.
 */
class RemoteControlService : LifecycleService() {

    private lateinit var container: AppContainer
    private var server: HttpCommandServer? = null
    private var advertiser: NsdServiceAdvertiser? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Builds the container and the notification channel before anything can be started. */
    override fun onCreate() {
        super.onCreate()
        container = AppContainer.from(applicationContext)
        createNotificationChannel()
    }

    /**
     * Starts the agent. There is no corresponding stop from within the app — nothing to send it
     * from, with no UI — so uninstalling is how the agent is turned off.
     *
     * Returns START_STICKY so Android brings the agent back if it reclaims the process: an
     * agent that has quietly stopped answering is worse than a service that was never running.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground()
        startServer()
        return START_STICKY
    }

    /** Releases everything the agent holds: the advertisement, the socket, and the Wi-Fi lock. */
    override fun onDestroy() {
        advertiser?.stop()
        server?.stop()
        releaseWifiLock()
        super.onDestroy()
    }

    /** Not bindable: the agent is reached over the network, never by another local component. */
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /** Brings up the HTTP server, the Wi-Fi lock and the mDNS advertisement, once. */
    private fun startServer() {
        if (server?.isRunning == true) return

        val port = container.config.port
        val dispatcher = container.dispatcherFor(this)

        server = HttpCommandServer(port) {
            commandApi(
                dispatcher = dispatcher,
                photos = container.photos,
                device = container.deviceDescription(),
            )
        }.also { it.start() }

        acquireWifiLock()

        advertiser = NsdServiceAdvertiser(applicationContext).also {
            val device = container.deviceDescription()
            it.advertise(
                serviceName = "cam-remote ${device.model}",
                port = port,
                attributes = mapOf(
                    "api" to HealthResponse.API_VERSION,
                    "model" to device.model,
                    "android" to device.androidRelease,
                ),
            )
        }

        Log.i(TAG, "cam-remote agent listening on port $port")
    }

    /**
     * Promotes the service to the foreground with its ongoing notification.
     *
     * Required for the agent to survive at all, and — from API 34 — for it to touch the camera.
     */
    private fun startInForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                getString(
                    R.string.notification_text,
                    LocalAddresses.firstLanIpv4() ?: getString(R.string.notification_no_address),
                    container.config.port,
                ),
            )
            .setSmallIcon(R.drawable.ic_agent_notification)
            .setOngoing(true)
            .setContentIntent(
                // The guaranteed route to a permission/settings prompt: a notification tap is
                // always allowed to start an activity, even from the background, unlike a direct
                // startActivity call from the service.
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, LaunchActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceTypes())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Declares only the service types this app is currently entitled to.
     *
     * From API 34 a camera-typed foreground service may not be started without the camera permission
     * already granted — asking for the type regardless would throw and take the whole agent down.
     * So the type is added only once the permission exists, and the agent still starts (serving
     * `device.getprop` and `system.status`) on a device where the user has not finished setup.
     */
    private fun foregroundServiceTypes(): Int {
        var types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (container.permissions.status().camera) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return types
    }

    /**
     * Keeps Wi-Fi awake while the agent is running.
     *
     * Without this the radio powers down with the screen and inbound connections are dropped — the
     * price of making the network the only transport. The battery-optimisation exemption requested
     * during setup covers the other half of the same problem.
     */
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifi = applicationContext.getSystemService<WifiManager>() ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, WIFI_LOCK_TAG).apply { acquire() }
    }

    /** Drops the Wi-Fi lock, if one is held. */
    private fun releaseWifiLock() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    /** Registers the low-importance channel the ongoing notification belongs to. */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RemoteControlService"
        private const val CHANNEL_ID = "cam-remote-agent"
        private const val NOTIFICATION_ID = 1
        private const val WIFI_LOCK_TAG = "cam-remote:agent"

        /** Starts the agent. Safe to call when it is already running. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, RemoteControlService::class.java))
        }
    }
}
