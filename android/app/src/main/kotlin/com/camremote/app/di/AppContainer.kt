package com.camremote.app.di

import android.content.Context
import android.os.Build
import androidx.lifecycle.LifecycleOwner
import com.camremote.app.adapter.AndroidPermissionInspector
import com.camremote.app.adapter.CameraXController
import com.camremote.app.adapter.ExecGetPropReader
import com.camremote.app.adapter.FileSystemPhotoStore
import com.camremote.app.adapter.IntentActivityStarter
import com.camremote.app.adapter.MediaStoreGalleryPublisher
import com.camremote.app.adapter.SystemPropertiesReader
import com.camremote.app.config.ServerConfig
import com.camremote.core.command.Command
import com.camremote.core.command.CommandDispatcher
import com.camremote.core.command.CommandRegistry
import com.camremote.core.command.impl.CapturePhotoCommand
import com.camremote.core.command.impl.GetPropCommand
import com.camremote.core.command.impl.ListCommandsCommand
import com.camremote.core.command.impl.OpenCameraCommand
import com.camremote.core.command.impl.PingCommand
import com.camremote.core.command.impl.StatusCommand
import com.camremote.core.logic.FirstAvailablePropertyReader
import com.camremote.core.port.CameraController
import com.camremote.core.port.PermissionInspector
import com.camremote.core.port.PhotoStore
import com.camremote.core.port.SystemClock
import com.camremote.core.protocol.DeviceDescription
import com.camremote.core.security.AccessControl
import com.camremote.core.security.PairingWindow
import java.io.File
import android.os.Environment

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
 */
class AppContainer(private val context: Context) {

    private val clock = SystemClock

    val config: ServerConfig by lazy { ServerConfig(context) }

    val permissions: PermissionInspector by lazy { AndroidPermissionInspector(context) }

    val accessControl: AccessControl by lazy { AccessControl { config.token } }

    val pairingWindow: PairingWindow by lazy { PairingWindow(clock = clock) { config.token } }

    /**
     * Two mechanisms behind one port: spawning `getprop` works almost everywhere, and reflecting on
     * `android.os.SystemProperties` covers the builds where it does not.
     */
    private val properties by lazy {
        FirstAvailablePropertyReader(listOf(ExecGetPropReader(), SystemPropertiesReader()))
    }

    /**
     * Captures land in the app's own external directory, which needs no storage permission from
     * API 29 and is removed cleanly when the app is uninstalled. The public Pictures directory is
     * additionally allowed so an operator can ask for somewhere they can find on the device.
     */
    val photos: PhotoStore by lazy {
        val appPictures = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir,
            "cam-remote",
        )
        FileSystemPhotoStore(
            defaultRoot = appPictures,
            allowedRoots = listOfNotNull(
                appPictures,
                context.getExternalFilesDir(null),
                context.filesDir,
            ),
            gallery = MediaStoreGalleryPublisher(context),
        )
    }

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
        descriptors: () -> List<com.camremote.core.protocol.CommandDescriptor>,
    ): List<Command> = listOf(
        PingCommand(clock),
        ListCommandsCommand(descriptors),
        StatusCommand(permissions, ::deviceDescription, camera, clock),
        GetPropCommand(properties),
        OpenCameraCommand(IntentActivityStarter(context), permissions),
        CapturePhotoCommand(camera, photos, permissions, clock),
    )
}
