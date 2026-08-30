package com.camremote.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import com.camremote.app.R
import com.camremote.app.adapter.LocalAddresses
import com.camremote.app.di.AppContainer
import com.camremote.app.setup.LaunchActivity
import com.camremote.app.transport.http.HttpCommandServer
import com.camremote.app.transport.http.commandApi

/**
 * The agent itself: a foreground service that owns the HTTP server and the Wi-Fi lock, and runs for
 * as long as Android lets it. Its notification is the whole of its interface — it reports the
 * address to point a client at, keeps that address current as the device's own changes, and carries
 * the only way to switch the agent off.
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
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** The address currently on the notification, so an unchanged one is not redrawn. */
    private var shownAddress: String? = null

    /** Builds the container and the notification channel before anything can be started. */
    override fun onCreate() {
        super.onCreate()
        container = AppContainer.from(applicationContext)
        createNotificationChannel()
    }

    /**
     * Starts the agent. The way back out is the notification's "Terminate service" action, which
     * sends [ACTION_STOP] to this same service.
     *
     * Returns START_STICKY so Android brings the agent back if it reclaims the process: an
     * agent that has quietly stopped answering is worse than a service that was never running.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            shutDown()
            // Not sticky: an agent the operator has just switched off must not be handed back to
            // them by the system a moment later.
            return START_NOT_STICKY
        }

        startInForeground()
        startServer()
        return START_STICKY
    }

    /**
     * Stops the agent for good, at the operator's request.
     *
     * Clearing the flag matters as much as stopping: [BootReceiver] restarts anything that was
     * running before a reboot, so without this the agent would come back the next morning and
     * "Terminate service" would read as a lie. Opening the app sets it again, which is the way
     * back.
     *
     * Everything the service holds — the socket, the Wi-Fi lock — is released in `onDestroy`,
     * which `stopSelf` leads to.
     */
    private fun shutDown() {
        Log.i(TAG, "Terminating at the operator's request")
        container.config.isEnabled = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Releases everything the agent holds: the socket, the network watch, the Wi-Fi lock. */
    override fun onDestroy() {
        server?.stop()
        stopWatchingForAddressChanges()
        releaseWifiLock()
        super.onDestroy()
    }

    /** Not bindable: the agent is reached over the network, never by another local component. */
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /** Brings up the HTTP server and the Wi-Fi lock, once. */
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
        watchForAddressChanges()

        Log.i(TAG, "cam-remote agent listening on port $port")
    }

    /**
     * Promotes the service to the foreground with its ongoing notification.
     *
     * Required for the agent to survive at all, and — from API 34 — for it to touch the camera.
     */
    private fun startInForeground() {
        val notification = buildNotification()

        // A type may only be passed if the <service> element declares it, so "no type at all" is
        // the honest answer below API 34 when the camera permission is still missing.
        val types = foregroundServiceTypes()
        if (types == 0) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification, types)
        }
    }

    /** The ongoing notification, carrying whatever address the device answers on right now. */
    private fun buildNotification(): Notification {
        shownAddress = LocalAddresses.firstLanIpv4()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                getString(
                    R.string.notification_text,
                    shownAddress ?: getString(R.string.notification_no_address),
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
            .addAction(
                // No icon: from API 24 the standard notification template does not draw one, and
                // inventing a drawable to be ignored is not worth the asset.
                0,
                getString(R.string.notification_stop),
                PendingIntent.getService(
                    this,
                    STOP_REQUEST_CODE,
                    Intent(this, RemoteControlService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    /**
     * Watches for the device's address changing under it, and redraws the notification when it does.
     *
     * The notification is the only place an operator can learn where to point `--host`, so an
     * address that has silently moved makes the one source of truth wrong. DHCP reassigns handsets
     * more often than one would guess — this one moved three times in an afternoon — and the
     * symptom is a command failing with "connection refused" against an address the phone itself is
     * still displaying.
     */
    private fun watchForAddressChanges() {
        if (networkCallback != null) return
        val connectivity = applicationContext.getSystemService<ConnectivityManager>() ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {
            /** Fires when an interface gains, loses or changes an address — the case that matters. */
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                refreshNotification()
            }

            override fun onAvailable(network: Network) = refreshNotification()

            override fun onLost(network: Network) = refreshNotification()
        }

        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "Could not watch for address changes", it) }
    }

    /** Stops watching, if we started. */
    private fun stopWatchingForAddressChanges() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            applicationContext.getSystemService<ConnectivityManager>()
                ?.unregisterNetworkCallback(callback)
        }
    }

    /**
     * Reposts the notification, but only when the address has actually changed.
     *
     * Network callbacks fire freely — a captive-portal probe or a route change is enough — and
     * reposting an identical notification on each one would be churn the user can see.
     */
    private fun refreshNotification() {
        if (LocalAddresses.firstLanIpv4() == shownAddress) return
        runCatching {
            getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "Address changed; notification now shows ${shownAddress ?: "no address"}")
        }.onFailure { Log.w(TAG, "Could not update the notification", it) }
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
        // specialUse only exists from API 34, and is the only non-camera type this service
        // declares. Below that, no type is claimed unless the camera permission makes 'camera'
        // truthful: passing a type the manifest does not declare -- as an earlier version did with
        // dataSync -- throws IllegalArgumentException and takes the agent down at startup.
        var types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
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
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_LOCK_TAG)
            .apply { acquire() }
    }

    /** Drops the Wi-Fi lock, if one is held. */
    private fun releaseWifiLock() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    /** Registers the low-importance channel the ongoing notification belongs to. */
    private fun createNotificationChannel() {
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

        /** Asks the running service to stop. Sent only by the notification's own action. */
        private const val ACTION_STOP = "com.camremote.app.action.STOP"

        /** Distinct from the content intent's 0, or the two PendingIntents would collide. */
        private const val STOP_REQUEST_CODE = 1

        /** Starts the agent. Safe to call when it is already running. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, RemoteControlService::class.java))
        }
    }
}
