package com.camremote.core.command.impl

import com.camremote.core.command.Command
import com.camremote.core.command.CommandOutcome
import com.camremote.core.logic.CameraAppChoice
import com.camremote.core.logic.CameraAppLaunch
import com.camremote.core.port.ActivityStarter
import com.camremote.core.protocol.CommandDescriptor
import com.camremote.core.protocol.Params
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Reports every camera app the device offers, and which one `camera.open` would choose.
 *
 * Purely diagnostic, and worth its keep the moment the agent meets a second handset: when
 * `camera.open` behaves differently on a Samsung than on a realme, this says exactly why, without
 * anyone having to be holding either phone.
 */
class ListCameraAppsCommand(private val activities: ActivityStarter) : Command {

    override val descriptor = CommandDescriptor(
        name = "camera.apps",
        description = "List the camera apps this device offers, and which one camera.open would use.",
    )

    override val timeout = 15.seconds

    override suspend fun execute(params: Params): CommandOutcome {
        var firstUsable: Pair<String, String>? = null

        val strategies = buildJsonArray {
            CameraAppLaunch.candidatesFor(Params.EMPTY).forEach { spec ->
                val handlers = activities.resolveAll(spec)
                val chosen = CameraAppChoice.pick(handlers)
                if (firstUsable == null && chosen != null) {
                    firstUsable = spec.strategy to chosen.component
                }
                add(
                    buildJsonObject {
                        put("strategy", JsonPrimitive(spec.strategy))
                        put("action", JsonPrimitive(spec.action))
                        put("chosen", chosen?.component?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
                        put(
                            "handlers",
                            buildJsonArray {
                                handlers.forEach { handler ->
                                    add(
                                        buildJsonObject {
                                            put("package", JsonPrimitive(handler.packageName))
                                            put("activity", JsonPrimitive(handler.activityName))
                                            put("preinstalled", JsonPrimitive(handler.isSystem))
                                            put("defaultHandler", JsonPrimitive(handler.isDefault))
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }

        return CommandOutcome.Success(
            buildJsonObject {
                put("strategies", strategies)
                put("wouldUseStrategy", firstUsable?.first?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
                put("wouldUseComponent", firstUsable?.second?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
            },
        )
    }
}
