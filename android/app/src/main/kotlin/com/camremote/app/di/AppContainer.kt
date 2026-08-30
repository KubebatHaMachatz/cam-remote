package com.camremote.app.di

import android.content.Context
import android.os.Build
import androidx.lifecycle.LifecycleOwner
import com.camremote.app.adapter.AndroidPermissionInspector
import com.camremote.app.adapter.AndroidPermissionPrompt
import com.camremote.app.adapter.CameraXController
import com.camremote.app.adapter.ExecGetPropReader
import com.camremote.app.adapter.IntentActivityStarter
import com.camremote.app.adapter.MediaStorePhotoStore
import com.camremote.app.adapter.SystemPropertiesReader
import com.camremote.app.config.ServerConfig
import com.camremote.core.command.Command
import com.camremote.core.command.CommandDispatcher
import com.camremote.core.command.CommandRegistry
import com.camremote.core.command.impl.CapturePhotoCommand
import com.camremote.core.command.impl.GetPropCommand
import com.camremote.core.command.impl.ListCameraAppsCommand
import com.camremote.core.command.impl.ListCommandsCommand
import com.camremote.core.command.impl.OpenCameraCommand
import com.camremote.core.command.impl.PingCommand
import com.camremote.core.command.impl.StatusCommand
import com.camremote.core.logic.FirstAvailablePropertyReader
import com.camremote.core.port.CameraController
import com.camremote.core.port.PermissionInspector
import com.camremote.core.port.PermissionPrompt
import com.camremote.core.port.PhotoStore
import com.camremote.core.port.SystemClock
import com.camremote.core.protocol.CommandDescriptor
import com.camremote.core.protocol.DeviceDescription
import java.io.File

/**
 * Where every port is plugged into its adapter, and where the command catalog is declared.
 *
 * Wiring is by hand rather than through a dependency-injection framework. At this size — a couple of
 * dozen objects, all singletons, no scopes beyond the service's — a framework would add annotation
 * processing and indirection in exchange for saving a file that is worth reading. As it stands, the
 * whole composition of the application is one page, and [commands] is the complete answer to "what
 * can this thing do?".
 *
 * **Adding a command:** write the [Command] implementation in `:core`, then add one line to
 * [commands]. Nothing else in the project changes — not the transport, not the client, not the
 * protocol.
 *
 * The constructor is private and instances come from [from] because there must be exactly one per
 * process. An earlier version let two components each build their own, which type-checked,
 * unit-tested clean, and failed on the handset: two components disagreed about state that must be
 * shared in fact, not by coincidence, so the shape that caused it is gone rather than merely fixed.
 */
class AppContainer private constructor(private val context: Context) {

    private val clock = SystemClock

    val config: ServerConfig by lazy { ServerConfig(context) }

    val permissions: PermissionInspector by lazy { AndroidPermissionInspector(context) }

    val permissionPrompt: PermissionPrompt by lazy { AndroidPermissionPrompt(context) }

    /**
     * Two mechanisms behind one port: spawning `getprop` works almost everywhere, and reflecting on
     * `android.os.SystemProperties` covers the builds where it does not.
     */
    private val properties by lazy {
        FirstAvailablePropertyReader(listOf(ExecGetPropReader(), SystemPropertiesReader()))
    }

    /**
     * Captures land in the user's own `Documents/cam-remote`, reachable from any file manager.
     *
     * Under scoped storage an app may create files it owns anywhere in shared storage with no
     * storage permission at all, so none is declared or requested; `minSdk` is 29 so that this
     * holds on every device the app will run on. See `docs/DESIGN.md` section 8.
     *
     * The scratch directory is in the cache, because its files exist only between the shutter
     * firing and the copy into Documents completing, and Android is welcome to reclaim any that a
     * crash leaves behind. The index is not: it must survive, so it lives in `filesDir`.
     */
    val photos: PhotoStore by lazy {
        MediaStorePhotoStore(
            context = context,
            scratchDirectory = File(context.cacheDir, "captures"),
            indexFile = File(context.filesDir, "photo-index.jsonl"),
        )
    }

    /** How this handset identifies itself to a client, for discovery output and reports. */
    fun deviceDescription(): DeviceDescription = DeviceDescription(
        name = Build.DEVICE ?: "android",
        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        androidRelease = Build.VERSION.RELEASE ?: "unknown",
        apiLevel = Build.VERSION.SDK_INT,
    )

    /**
     * Builds the dispatcher for a running service.
     *
     * It takes a [LifecycleOwner] because CameraX binds its use cases to one, and the service is
     * the only thing in a UI-less app with a lifecycle worth binding to.
     */
    fun dispatcherFor(lifecycleOwner: LifecycleOwner): CommandDispatcher {
        val camera = CameraXController(context, lifecycleOwner)
        lateinit var registry: CommandRegistry
        registry = CommandRegistry(commands(camera) { registry.descriptors() })
        return CommandDispatcher(registry, clock)
    }

    /**
     * The complete command catalog. One line per capability.
     *
     * @param descriptors supplied lazily because `system.commands` reports the registry it is itself
     *   a member of.
     */
    private fun commands(
        camera: CameraController,
        descriptors: () -> List<CommandDescriptor>,
    ): List<Command> = listOf(
        PingCommand(clock),
        ListCommandsCommand(descriptors),
        StatusCommand(permissions, ::deviceDescription, camera, clock),
        GetPropCommand(properties),
        OpenCameraCommand(IntentActivityStarter(context), permissions, permissionPrompt),
        ListCameraAppsCommand(IntentActivityStarter(context)),
        CapturePhotoCommand(camera, photos, permissions, clock, permissionPrompt),
    )

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        /**
         * The one container for this process. Safe to call from any component or thread.
         *
         * Double-checked because the service, the boot receiver and LaunchActivity can each
         * reach for it from different threads at once.
         */
        fun from(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
