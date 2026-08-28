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
import com.camremote.app.adapter.NsdServiceAdvertiser
import com.camremote.app.di.AppContainer
import com.camremote.app.setup.SetupActivity
import com.camremote.app.transport.http.HttpCommandServer
import com.camremote.app.transport.http.commandApi
import com.camremote.core.protocol.HealthResponse

/**
 * The agent itself: a foreground service that owns the HTTP server, the mDNS advertisement and the
 * Wi-Fi lock, and lives for as long as the user leaves it switched on.
 *
 * A service rather than an activity because the app has no control UI by design — there is nothing
 * for a user to look at while commands are being served. In an app with a UI this is roughly the
 * role a ViewModel would play: it is the thing that outlives any screen and owns the coroutine
 * scope. Extending [LifecycleService] gives it the `LifecycleOwner` that CameraX needs to bind to,
 * which is the other reason a plain `Service` would not do.
 */
class RemoteControlService : LifecycleService() {

    private lateinit var container: AppContainer
    private var server: HttpCommandServer? = null
    private var advertiser: NsdServiceAdvertiser? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.from(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            container.config.isEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()
        startServer()

        // Restart if Android reclaims the process: an agent that quietly stops answering is worse
        // than one that is plainly switched off.
        return START_STICKY
    }

    override fun onDestroy() {
        advertiser?.stop()
        server?.stop()
        releaseWifiLock()
        container.pairingWindow.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startServer() {
        if (server?.isRunning == true) return

        val port = container.config.port
        val dispatcher = container.dispatcherFor(this)

        server = HttpCommandServer(port) {
            commandApi(
                dispatcher = dispatcher,
                accessControl = container.accessControl,
                pairingWindow = container.pairingWindow,
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

    private fun startInForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, container.config.port))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, SetupActivity::class.java),
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

    private fun releaseWifiLock() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

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

        const val ACTION_STOP = "com.camremote.app.action.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RemoteControlService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RemoteControlService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
